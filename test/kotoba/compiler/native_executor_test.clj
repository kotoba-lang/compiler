(ns kotoba.compiler.native-executor-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.atomic-output :as atomic-output]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.native-executor :as executor]
            [kotoba.compiler.runtime-identity :as runtime-identity]
            [kotoba.compiler.signing :as signing]))

(defn- target []
  (if (contains? #{"aarch64" "arm64"} (.toLowerCase (System/getProperty "os.arch")))
    :aarch64-kotoba-v1
    :x86_64-kotoba-v1))

(defn- signed [source policy]
  (let [artifact (:artifact (compiler/compile-source source (target) policy))
        key (signing/generate-keypair)
        envelope (signing/sign artifact key {:not-before 1000 :expires 2000})
        trust {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
               :revoked-signers #{} :revoked-artifacts #{}}]
    {:artifact artifact :key key :envelope envelope :trust trust}))

(defonce measured-runtime
  (delay
    (let [{:keys [runtime loader-bytes]} (executor/measure-runtime)
          loader (doto (java.io.File/createTempFile "kotoba-test-loader-" "")
                   (.deleteOnExit))]
      (atomic-output/write-bytes! (.getPath loader) loader-bytes {:executable? true})
      {:runtime runtime :loader-path (.getPath loader)})))

(defn- execution-options [trust]
  (let [{:keys [runtime loader-path]} @measured-runtime]
    {:trust (assoc trust :trusted-runtime-sha256
                   #{(runtime-identity/identity-sha256 runtime)})
     :options {:now 1500 :entry 'main :runtime runtime :loader-path loader-path}}))

(deftest verified-native-execution-produces-measured-evidence
  (let [{:keys [envelope trust]} (signed "(defn main [] 42)" {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []}
                                 options)]
    (is (= {:status :ok :result 42} (select-keys (:evidence result) [:status :result])))
    (is (= :kotoba.native-runtime/v6 (get-in result [:evidence :runtime :format])))
    (is (= :native (get-in result [:evidence :runtime :target-profile :execution])))
    (is (= executor/loader-source-sha256
           (get-in result [:evidence :runtime :loader-source-sha256])))
    (is (every? #(re-matches #"[0-9a-f]{64}" %)
                (vals (dissoc (get-in result [:evidence :runtime])
                              :format :target-profile))))
    (is (= {:status :ok :result 42
            :fuel {:initial 256 :remaining 255}
            :heap {:capacity 4096 :used 0}}
           (:report result)))
    (is (<= (:started-at result) (:finished-at result)))))

(deftest execution-rejects-before-entering-untrusted-or-unauthorized-code
  (let [{:keys [envelope trust]} (signed "(defn main [] 42)" {:allow #{}})
        tampered (assoc-in envelope [:artifact :code 0] 255)
        {:keys [runtime loader-path]} @measured-runtime]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runtime identity is not trusted"
                          (executor/execute envelope trust {:allow #{}} {:args []}
                                            {:now 1500 :entry 'main :runtime runtime
                                             :loader-path loader-path})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"artifact integrity mismatch"
                          (executor/execute tampered trust {:allow #{}} {:args []}
                                            {:now 1500 :entry 'main})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"entry arity"
                          (executor/execute envelope trust {:allow #{}} {:args [1]}
                                            {:now 1500 :entry 'main}))))
  (let [policy {:allow #{[:cap/call 7]}}
        {:keys [envelope trust]} (signed "(defn main [] (cap-call 7 41))" policy)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denies required effects"
                          (executor/execute envelope trust {:allow #{}} {:args []}
                                            {:now 1500 :entry 'main})))))

(deftest execution-rejects-a-valid-artifact-sealed-for-another-os
  (let [isa (if (= (target) :aarch64-kotoba-v1) "aarch64" "x86_64")
        other-os (if (.contains (.toLowerCase (System/getProperty "os.name")) "mac")
                   "linux" "macos")
        explicit-target (keyword (str isa "-" other-os "-kotoba-v1"))
        artifact (:artifact (compiler/compile-source "(defn main [] 42)" explicit-target))
        key (signing/generate-keypair)
        envelope (signing/sign artifact key {:not-before 1000 :expires 2000})
        trust {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
               :revoked-signers #{} :revoked-artifacts #{}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match execution host"
                          (executor/execute envelope trust {:allow #{}} {:args []}
                                            {:now 1500 :entry 'main})))))

(deftest execution-rejects-a-trusted-runtime-measured-for-another-platform
  (let [{:keys [envelope trust]} (signed "(defn main [] 42)" {:allow #{}})
        {:keys [runtime loader-path]} @measured-runtime
        other-os (if (= :macos (get-in runtime [:target-profile :os])) :linux :macos)
        other-runtime (-> runtime
                          (assoc-in [:target-profile :os] other-os)
                          (assoc-in [:target-profile :runtime]
                                    (if (= other-os :linux)
                                      :kotoba-linux-supervisor-v1
                                      :kotoba-macos-supervisor-v1)))
        pinned (assoc trust :trusted-runtime-sha256
                      #{(runtime-identity/identity-sha256 other-runtime)})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runtime target profile"
                          (executor/execute envelope pinned {:allow #{}} {:args []}
                                            {:now 1500 :entry 'main
                                             :runtime other-runtime
                                            :loader-path loader-path})))))

(deftest windows-runtime-identity-requires-the-windows-loader-source
  (let [{:keys [runtime]} @measured-runtime
        windows-profile (compiler/compile-source "(defn main [] 42)"
                                                 :x86_64-windows-kotoba-v1)
        windows-runtime (-> runtime
                            (assoc :target-profile (get-in windows-profile [:artifact :target-profile]))
                            (assoc :loader-source-sha256
                                   runtime-identity/windows-loader-source-sha256))]
    (is (= windows-runtime (runtime-identity/validate! windows-runtime)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runtime identity rejected"
                          (runtime-identity/validate!
                           (assoc windows-runtime :loader-source-sha256
                                  runtime-identity/loader-source-sha256))))))

(deftest execution-rejects-a-loader-that-does-not-match-the-approved-bytes
  (let [{:keys [envelope trust]} (signed "(defn main [] 42)" {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        changed (doto (java.io.File/createTempFile "kotoba-changed-loader-" "")
                  (.deleteOnExit))]
    (atomic-output/write-bytes! (.getPath changed) (byte-array [0 1 2 3])
                                {:executable? true})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match runtime identity"
                          (executor/execute envelope trust {:allow #{}} {:args []}
                                            (assoc options :loader-path (.getPath changed)))))))

(deftest host-process-boundary-is-time-and-output-bounded
  (let [run-process @#'executor/run-process
        test-env {"PATH" "/usr/bin:/bin"}
        normal (run-process ["/bin/sh" "-c" "printf ok"] test-env
                            {:timeout-ms 1000 :output-limit 1024})
        timeout (run-process ["/bin/sh" "-c" "sleep 10"] test-env
                             {:timeout-ms 100 :output-limit 1024})
        flood (run-process ["/bin/sh" "-c" "yes x"] test-env
                           {:timeout-ms 2000 :output-limit 1024})
        isolated (run-process ["/bin/sh" "-c" "printf %s \"${HOME-unset}\""] {}
                              {:timeout-ms 1000 :output-limit 1024})]
    (is (= {:exit 0 :stdout "ok" :stderr "" :timed-out? false
            :output-exceeded? false}
           normal))
    (is (:timed-out? timeout))
    (is (:output-exceeded? flood))
    (is (<= (count (:stdout flood)) 1024))
    (is (= "unset" (:stdout isolated)))))

(deftest compiler-executable-is-resolved-to-a-hashed-real-file
  (let [resolve-executable @#'executor/resolve-executable
        file-sha256 @#'executor/file-sha256
        path (resolve-executable "cc")]
    (is (.isAbsolute path))
    (is (java.nio.file.Files/isRegularFile
         path (make-array java.nio.file.LinkOption 0)))
    (is (java.nio.file.Files/isExecutable path))
    (is (re-matches #"[0-9a-f]{64}" (file-sha256 (.toFile path))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid toolchain"
                          (resolve-executable "./cc")))))

(deftest compiler-reported-tools-require-canonical-executable-paths
  (let [resolve-tool @#'executor/resolve-reported-tool
        env {"PATH" "/usr/bin:/bin"}]
    (is (.isAbsolute (resolve-tool "/bin/sh\n" env)))
    (is (.isAbsolute (resolve-tool "sh\n" env)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an executable"
                          (resolve-tool "relative/tool\n" env)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed tool path"
                          (resolve-tool "as\nld\n" env)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed tool path"
                          (resolve-tool (str "as" \u0000) env)))))

(deftest compiler-resource-manifest-is-deterministic-bounded-and-symlink-free
  (let [manifest @#'executor/directory-manifest-sha256
        delete-tree @#'executor/delete-tree!
        root (java.nio.file.Files/createTempDirectory
              "kotoba-resource-test-" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (let [nested (java.nio.file.Files/createDirectory
                    (.resolve root "nested")
                    (make-array java.nio.file.attribute.FileAttribute 0))
            first-file (.resolve root "a.h")
            second-file (.resolve nested "b.h")]
        (java.nio.file.Files/writeString first-file "alpha"
                                         (make-array java.nio.file.OpenOption 0))
        (java.nio.file.Files/writeString second-file "beta"
                                         (make-array java.nio.file.OpenOption 0))
        (let [first-hash (manifest root)]
          (is (= first-hash (manifest root)))
          (java.nio.file.Files/writeString second-file "changed"
                                           (make-array java.nio.file.OpenOption 0))
          (is (not= first-hash (manifest root))))
        (java.nio.file.Files/createSymbolicLink
         (.resolve root "link") first-file
         (make-array java.nio.file.attribute.FileAttribute 0))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"contains a symlink"
                              (manifest root)))
        (java.nio.file.Files/delete (.resolve root "link"))
        (with-open [large (java.io.RandomAccessFile. (.toFile (.resolve root "large")) "rw")]
          (.setLength large (inc (* 64 1024 1024))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bytes exceed limit"
                              (manifest root))))
      (finally (delete-tree (.toFile root))))))

(deftest compiler-dependency-closure-parser-and-manifest-fail-closed
  (let [parse-deps @#'executor/parse-dependency-file
        manifest @#'executor/dependency-manifest-sha256
        delete-tree @#'executor/delete-tree!
        root (java.nio.file.Files/createTempDirectory
              "kotoba-dependency-test-" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (is (= ["path with space.h" "next.h"]
             (parse-deps "output.o: path\\ with\\ space.h \\\n  next.h\n")))
      (is (= ["C:\\Program Files\\SDK\\windows.h" "D:\\a\\source.h"]
             (parse-deps (str "D:\\a\\output.o: C:\\Program\\ Files\\SDK\\windows.h \\\r\n"
                              " D:\\a\\source.h\r\n"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no target separator"
                            (parse-deps "missing target")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ends in an escape"
                            (parse-deps "out: path\\")))
      (let [first-file (.resolve root "first.h")
            second-file (.resolve root "second.h")]
        (java.nio.file.Files/writeString first-file "alpha"
                                         (make-array java.nio.file.OpenOption 0))
        (java.nio.file.Files/writeString second-file "beta"
                                         (make-array java.nio.file.OpenOption 0))
        (let [first-hash (manifest [(str second-file) (str first-file)])]
          (is (= first-hash (manifest [(str first-file) (str second-file)])))
          (java.nio.file.Files/writeString second-file "changed"
                                           (make-array java.nio.file.OpenOption 0))
          (is (not= first-hash (manifest [(str first-file) (str second-file)]))))
        (java.nio.file.Files/delete second-file)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a regular file"
                              (manifest [(str second-file)])))
        (let [large (.resolve root "large.h")]
          (with-open [file (java.io.RandomAccessFile. (.toFile large) "rw")]
            (.setLength file (inc (* 64 1024 1024))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bytes exceed limit"
                                (manifest [(str large)])))))
      (finally (delete-tree (.toFile root))))))

(deftest native-trap-is-returned-as-measured-evidence
  (let [{:keys [envelope trust]}
        (signed "(defn forever [x] (forever x)) (defn main [] 0)" {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args [0]}
                                 (assoc options :entry 'forever))
        expected-signal (if (= (target) :aarch64-kotoba-v1) :SIGTRAP :SIGILL)]
    (is (= :trap (get-in result [:evidence :status])))
    (is (= {:kind :signal :signal expected-signal}
           (get-in result [:evidence :trap])))
    (is (= 0 (get-in result [:report :fuel :remaining])))
    (is (= 120 (get-in result [:report :exit])))))

(deftest bounded-pair-arena-executes-and-rejects-forged-handles
  (let [{:keys [envelope trust]}
        (signed "(defn bad [handle] (pair-first handle))
                 (defn main [] (+ (pair-first (pair 20 99))
                                  (pair-second (pair 1 22))))" {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)
        forged (executor/execute envelope trust {:allow #{}} {:args [1]}
                                 (assoc options :entry 'bad))
        expected-signal (if (= (target) :aarch64-kotoba-v1) :SIGILL :SIGILL)]
    (is (= 42 (get-in result [:evidence :result])))
    (is (= {:capacity 4096 :used 2} (get-in result [:report :heap])))
    (is (= :trap (get-in forged [:evidence :status])))
    (is (= {:kind :signal :signal expected-signal}
           (get-in forged [:evidence :trap])))))
