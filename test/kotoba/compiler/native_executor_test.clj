(ns kotoba.compiler.native-executor-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.atomic-output :as atomic-output]
            [kotoba.compiler.backend.aarch64 :as aarch64]
            [kotoba.compiler.backend.x86-64 :as x86-64]
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
            :fuel {:initial 512 :remaining 511}
            :heap {:capacity 4096 :used 0}}
           (:report result)))
    (is (<= (:started-at result) (:finished-at result)))))

(deftest typed-i64-capability-call-is-qualified-on-native
  (let [source "(defn main [] :i64 (typed-cap-call 4 :i64 :i64 41))"
        policy {:allow #{[:cap/call 4]}}
        {:keys [envelope trust]} (signed source policy)
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust policy {:args []} options)]
    (is (= {:status :ok :result 42}
           (select-keys (:evidence result) [:status :result])))
    (is (= #{[:cap/call 4]} (get-in envelope [:artifact :effects])))
    (is (= '(typed-cap-call 4 :i64 :i64 41)
           (get-in envelope [:artifact :program :functions 0 :body])))))

(deftest typed-string-capability-call-validates-native-pointer-length-boundary
  (let [source "(defn main [] :i64
                  (string-byte-length
                    (typed-cap-call 4 :string :string \"hello😀\")))"
        policy {:allow #{[:cap/call 4]}}
        {:keys [envelope trust]} (signed source policy)
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust policy {:args []} options)]
    (is (= {:status :ok :result 9}
           (select-keys (:evidence result) [:status :result])))
    (is (= '(string-byte-length
              (typed-cap-call 4 :string :string "hello😀"))
           (get-in envelope [:artifact :program :functions 0 :body])))
    (is (= 128 (get-in envelope [:artifact :context-abi
                                 :typed-cap-call-offset])))))

(deftest typed-option-and-result-capability-calls-validate-native-tagged-boundaries
  (let [source "(defn main [] :i64
                  (+ (option-value
                       (typed-cap-call 4 :option-i64 :option-i64 (some 41)) 0)
                     (option-value
                       (typed-cap-call 4 :option-i64 :option-i64 nil) 5)
                     (result-value
                       (typed-cap-call 4 :result-i64 :result-i64 (result-ok 7)) 0)
                     (result-error
                       (typed-cap-call 4 :result-i64 :result-i64 (result-err 9)) 0)))"
        policy {:allow #{[:cap/call 4]}}
        {:keys [envelope trust]} (signed source policy)
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust policy {:args []} options)]
    (is (= {:status :ok :result 62}
           (select-keys (:evidence result) [:status :result])))
    (is (= #{[:cap/call 4]} (get-in envelope [:artifact :effects])))
    (is (= :kotoba.kir/v4 (get-in envelope [:artifact :program :format])))))

(deftest typed-option-and-result-capability-calls-emit-on-both-native-isas
  (let [source "(defn main [] :i64
                  (+ (option-value
                       (typed-cap-call 4 :option-i64 :option-i64 (some 3)) 0)
                     (result-error
                       (typed-cap-call 4 :result-i64 :result-i64 (result-err 4)) 0)))"
        policy {:allow #{[:cap/call 4]}}]
    (doseq [native-target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
      (let [artifact (:artifact (compiler/compile-source source native-target policy))]
        (is (seq (:code artifact)) (name native-target))
        (is (= #{[:cap/call 4]} (:effects artifact)) (name native-target))))))

(def generic-option-result-source
  "(defn main [] :i64
     (+ (match-option
          (option-some-of [:option :string] \"abc\") [:option :string]
          (none 100)
          (some text (string-byte-length text)))
        (string-byte-length
          (result-value-of
            [:result :string [:option :i64]]
            (result-ok-of [:result :string [:option :i64]] \"hello\")
            \"fallback\"))
        (option-value-of
          [:option :i64]
          (result-error-of
            [:result :string [:option :i64]]
            (result-err-of
              [:result :string [:option :i64]]
              (option-some-of [:option :i64] 7))
            (option-none-of [:option :i64]))
          0)
        (match-option
          (option-none-of [:option [:result :i64 :bool]])
          [:option [:result :i64 :bool]]
          (none 11)
          (some nested 100))
        (match-result
          (result-err-of [:result :bool :i64] 13)
          [:result :bool :i64]
          (ok value 100)
          (err error error))))")

(deftest generic-option-and-result-values-execute-through-real-native-loader
  (let [{:keys [envelope trust]} (signed generic-option-result-source {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 39 (get-in result [:evidence :result])))
    (is (= :kotoba.kir/v4 (get-in envelope [:artifact :program :format])))
    (is (= {:capacity 4096 :used 8} (get-in result [:report :heap])))))

(deftest generic-option-and-result-values-emit-on-both-native-isas
  (doseq [native-target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (let [artifact (:artifact
                    (compiler/compile-source generic-option-result-source
                                             native-target))]
      (is (seq (:code artifact)) (name native-target))
      (is (= :kotoba.kir/v4 (get-in artifact [:program :format]))
          (name native-target)))))

(deftest native-generic-option-still-rejects-non-word-payloads
  (let [record-type
        "[:record :demo/person [[:age :i64]]]"
        source
        (str "(defn main [] :i64 "
             "(match-option "
             "(option-some-of [:option " record-type "] "
             "(record-new " record-type " 7)) "
             "[:option " record-type "] "
             "(none 0) (some person (record-get " record-type " person :age))))")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"typed values currently require"
         (compiler/compile-source source (target))))))

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
  (let [{:keys [runtime]} @measured-runtime]
    (doseq [target [:x86_64-windows-kotoba-v1 :aarch64-windows-kotoba-v1]]
      (let [windows-profile (compiler/compile-source "(defn main [] 42)" target)
            windows-runtime (-> runtime
                                (assoc :target-profile (get-in windows-profile [:artifact :target-profile]))
                                (assoc :loader-source-sha256
                                       runtime-identity/windows-loader-source-sha256))]
        (is (= windows-runtime (runtime-identity/validate! windows-runtime)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runtime identity rejected"
                              (runtime-identity/validate!
                               (assoc windows-runtime :loader-source-sha256
                                      runtime-identity/loader-source-sha256))))))))

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

(deftest windows-loader-failure-class-is-path-free
  (let [failure-class @#'executor/loader-failure-class]
    (is (= "CreateAppContainerProfile/win32=5"
           (failure-class
            "kexe-loader-windows: CreateAppContainerProfile: win32=5\n")))
    (is (= "child contract requires an AppContainer process token"
           (failure-class
            "kexe-loader-windows: child contract requires an AppContainer process token\n")))
    (is (nil? (failure-class "unable to open C:\\secret\\program.bin\n")))))

(deftest native-runtime-environment-does-not-inherit-ambient-authority
  (is (= {"KEXE_STRUCTURED_REPORT" "1"}
         ((deref #'executor/runtime-environment) :linux))))

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

;; ADR-2607198300 / ADR-2607198200: the native (JVM/Node/browser-free) analog
;; of the com-stripe Customer create/get/list pilot -- entity/attribute/value
;; are caller-assigned integers rather than EDN strings (this backend has no
;; addressable buffer), but the EAVT mechanism (assert, point-get, count
;; distinct entities, index into them) is proven end to end via the real
;; kexe-loader native process, no JVM/JS engine involved once the artifact
;; is compiled.
(deftest kgraph-native-customer-pilot-asserts-and-queries-through-real-kexe-loader
  ;; NOTE: this backend's `let` (aarch64.clj/x86-64.clj `normalize-expr`) is a
  ;; compile-time substitution pass, not an imperative sequence -- a binding
  ;; whose value is never referenced in the body is never emitted at all, so
  ;; a side-effecting call (kgraph-assert!, create-customers itself) MUST
  ;; have its return value actually consumed (here, summed) or it silently
  ;; never executes. No `let` is used below for exactly this reason.
  (let [{:keys [envelope trust]}
        (signed "(defn create-customers []
                   (+ (kgraph-assert! 1 1 1001)
                      (kgraph-assert! 1 2 2001)
                      (kgraph-assert! 1 3 0)
                      (kgraph-assert! 2 1 1002)
                      (kgraph-assert! 2 2 2002)
                      (kgraph-assert! 2 3 500)))
                 (defn main []
                   (+ (= (create-customers) 6)
                      (= (kgraph-get 1 1) 1001)
                      (= (kgraph-get 1 2) 2001)
                      (= (kgraph-get 1 3) 0)
                      (= (kgraph-get 2 1) 1002)
                      (= (kgraph-get 2 2) 2002)
                      (= (kgraph-get 2 3) 500)
                      (= (kgraph-count 1) 2)
                      (= (kgraph-entity-at 1 0) 1)
                      (= (kgraph-entity-at 1 1) 2)))
                 (defn get-unknown-field [] (+ (create-customers) (kgraph-get 1 999)))
                 (defn entity-at-out-of-range [] (+ (create-customers) (kgraph-entity-at 1 5)))"
               {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)
        unknown (executor/execute envelope trust {:allow #{}} {:args []}
                                  (assoc options :entry 'get-unknown-field))
        out-of-range (executor/execute envelope trust {:allow #{}} {:args []}
                                       (assoc options :entry 'entity-at-out-of-range))
        expected-signal (if (= (target) :aarch64-kotoba-v1) :SIGILL :SIGILL)]
    (is (= 10 (get-in result [:evidence :result]))
        "create-customers returned 6, and all 9 get/count/entity-at checks matched -- genuinely native EAVT assert/query")
    (is (= (+ 6 Long/MIN_VALUE) (get-in unknown [:evidence :result]))
        "kgraph-get on an unasserted (entity,attribute) returns the not-found sentinel, not a trap")
    (is (= :trap (get-in out-of-range [:evidence :status]))
        "kgraph-entity-at past the distinct-entity count traps closed, like a forged pair handle")
    (is (= {:kind :signal :signal expected-signal}
           (get-in out-of-range [:evidence :trap])))))

;; ADR-2607198300 follow-up: `let` genuinely sequences (evaluates each binding
;; exactly once, in order, before the body) instead of the prior compile-time
;; substitution pass, which silently dropped an unreferenced side-effecting
;; binding, silently duplicated a repeatedly-referenced one, and silently made
;; an unconditionally-intended effect conditional if its one reference sat
;; inside an `if` branch. Each deftest below exercises one of those three
;; failure modes and would have failed before the fix (either wrong `:result`,
;; wrong `:report`, or -- for the unused-binding case -- an unchanged not-found
;; sentinel proving the assert never ran).

(deftest let-runs-an-unreferenced-side-effecting-binding-exactly-once
  (let [{:keys [envelope trust]}
        (signed "(defn main []
                   (let [_unused (kgraph-assert! 1 1 42)]
                     (kgraph-get 1 1)))"
               {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 42 (get-in result [:evidence :result]))
        "an unreferenced kgraph-assert! binding still runs -- a real let, not inlining-by-reference")))

(deftest let-runs-a-repeatedly-referenced-side-effecting-binding-exactly-once
  (let [{:keys [envelope trust]}
        (signed "(defn main []
                   (let [x (pair 1 1)]
                     (+ x x)))"
               {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 2 (get-in result [:evidence :result]))
        "x is the same handle both times (1+1=2) -- pair ran once and was reused, not
         re-evaluated per reference (which would return the first call's handle (1)
         plus the second call's handle (2), summing to 3)")
    (is (= {:capacity 4096 :used 1} (get-in result [:report :heap]))
        "exactly one pair allocation happened")))

(deftest let-runs-a-side-effecting-binding-unconditionally-even-when-its-one-reference-is-in-a-dead-if-branch
  (let [{:keys [envelope trust]}
        (signed "(defn main []
                   (let [x (pair 1 1)]
                     (if 0 x 999)))"
               {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 999 (get-in result [:evidence :result]))
        "the else branch is taken (test is 0/falsy)")
    (is (= {:capacity 4096 :used 1} (get-in result [:report :heap]))
        "pair still ran -- a real let evaluates its binding before the if is even
         reached, unlike substitution, which would inline `pair` only into the
         (never-executed) then-branch and never run it at all")))

(deftest nested-lets-compose-with-correct-depth-relative-addressing
  (let [{:keys [envelope trust]}
        (signed "(defn main []
                   (let [a (pair 10 20)]
                     (let [b (pair 30 40)]
                       (+ (pair-first a) (pair-second b)))))"
               {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 50 (get-in result [:evidence :result]))
        "pair-first of the outer let's binding (10) + pair-second of the inner let's
         binding (40) -- proves the outer binding is still reachable at the correct
         stack depth after the inner let has pushed its own slot")
    (is (= {:capacity 4096 :used 2} (get-in result [:report :heap])))))

(deftest let-composes-with-recursion-within-the-fuel-budget
  (let [{:keys [envelope trust]}
        (signed "(defn count-down [n acc]
                   (let [_touch (pair n acc)]
                     (if (= n 0) acc (count-down (- n 1) (+ acc 1)))))
                 (defn main [] (count-down 50 0))"
               {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 50 (get-in result [:evidence :result]))
        "50 levels of ordinary (non-tail-optimized self-call from inside a let,
         intentionally falling back off the tail-call fast path -- see emit-call's
         zero-temp-depth guard) recursion, well within the 512-call fuel budget")
    (is (= {:capacity 4096 :used 51} (get-in result [:report :heap]))
        "one pair per call: the initial call plus 50 recursive calls")))

;; ADR-2607198300 follow-up: string values are pair(offset,length) handles
;; whose bytes live either in the artifact's own code+literal-data region
;; (compile-time literals, embedded once per distinct content past the last
;; function's code) or in a runtime string pool (string-concat results),
;; uniformly resolved host-side by sign. Proven end to end through the real
;; kexe-loader native process -- no JVM, no JS engine.
(deftest string-literal-byte-length-round-trips-through-real-kexe-loader
  (let [{:keys [envelope trust]}
        (signed "(defn main [] (string-byte-length \"hello\"))" {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 5 (get-in result [:evidence :result]))
        "string-byte-length is exactly pair-second of the literal's own (offset,length) handle")))

(deftest string-equal-compares-content-not-handle-identity
  (let [{:keys [envelope trust]}
        (signed "(defn same [] (string=? \"same\" \"same\"))
                 (defn different-content [] (string=? \"abc\" \"xyz\"))
                 (defn different-length [] (string=? \"ab\" \"abc\"))
                 (defn main [] (+ (same) (+ (different-content) (different-length))))"
               {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 1 (get-in result [:evidence :result]))
        "\"same\"=\"same\" (1) is the ONLY true comparison of the three -- two
         SEPARATE literal occurrences of identical content get two DIFFERENT
         pair handles (pair_new allocates a fresh slot every call), so this
         proves string=? compares the addressed BYTES, not handle identity")))

(deftest string-concat-produces-a-pool-handle-comparable-to-a-literal
  (let [{:keys [envelope trust]}
        (signed "(defn main []
                   (+ (string-byte-length (string-concat \"foo\" \"bar\"))
                      (string=? (string-concat \"foo\" \"bar\") \"foobar\")))"
               {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 7 (get-in result [:evidence :result]))
        "6 (byte-length of the concatenated \"foobar\") + 1 (string=? against
         the literal \"foobar\" is true) -- proves string=? correctly compares
         a runtime string-pool handle (concat's own output, negative-encoded
         offset) against a code-region literal handle (non-negative offset)")))

;; ADR 0062: the first native (x86-64/aarch64) value-representation
;; increment -- a sealed, all-scalar (`:i64`/`:bool` fields only, no
;; `:f64`; see `ir/only-native-word-typed-features?`'s own
;; doc comment for why) record, construction + field projection only, real
;; native-process evidence matching every other deftest in this file (no
;; synthetic byte-level check). The record schema itself has no independent
;; runtime representation at all: `(record-get schema (record-new schema
;; ...) field)` desugars to the SAME `let`/`load-let` stack machinery this
;; file's own `let`-sequencing deftests above already prove correct -- see
;; `emit-record-get-of-new` in both `backend/x86-64.cljc` and
;; `backend/aarch64.cljc`.
(def ^:private native-record-schema
  '[:record :native/scalar-record [[:a :i64] [:b :i64] [:c :bool]]])

(deftest native-scalar-record-construction-and-field-projection-round-trips-through-real-kexe-loader
  (let [schema (pr-str native-record-schema)
        source (str
                "(defn checks [a b]
                   (+ (= (record-get " schema " (record-new " schema " a b true) :a) a)
                      (+ (= (record-get " schema " (record-new " schema " a b true) :b) b)
                         (+ (if (record-get " schema " (record-new " schema " a b true) :c) 1 0)
                            (if (record-get " schema " (record-new " schema " a b false) :c) 0 1)))))
                 (defn main [] (checks 11 22))")
        {:keys [envelope trust]} (signed source {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 4 (get-in result [:evidence :result]))
        "all four checks passed (1 each): field :a projects back the i64
         constructor argument unchanged, field :b likewise for its own
         (different) argument -- proving fields are not aliased or
         off-by-one -- and field :c (the :bool field) projects back TRUE
         when constructed with a literal `true` and FALSE when constructed
         with a literal `false`, through a REAL native process (not a
         JVM-side oracle-value check)")))

;; ADR 0062 fail-closed requirement: a record field type this native
;; increment does not admit (`:string`, disjoint from
;; `ir/native-scalar-record-field-types` = `#{:i64 :bool}`) must be
;; rejected at COMPILE TIME with a clear error, never silently miscompiled.
;; The function's own declared result type is annotated `:string` (matching
;; the field it projects) so this negative vector exercises ONLY the
;; native-target record-field-type gate, not an unrelated generic
;; function-result type mismatch that would fire even before that gate is
;; reached.
(deftest native-record-with-an-unsupported-field-type-is-rejected-at-compile-time
  (let [schema (pr-str '[:record :native/string-field-record [[:s :string]]])
        source (str
                "(defn project-s [] :string
                   (record-get " schema " (record-new " schema " \"x\") :s))
                 (defn main [] 0)")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"qualified native"
                          (compiler/compile-source source (target))))
    ;; Confirms the rejection is native-specific admission, not a generic
    ;; type error: the identical source compiles fine on the Wasm target,
    ;; which already supports string-bearing records (ADR 0053).
    (is (= :wasm/v1 (:format (compiler/compile-source source :wasm32-kotoba-v1))))))

;; ADR 0062 fail-closed requirement (second vector): `record-get`'s value
;; operand must be a DIRECTLY-nested, same-schema `record-new` --
;; `emit-record-get-of-new` has no runtime record representation to fall
;; back on, so anything else must be rejected with a clear compiler error,
;; not silently miscompiled. This source passes ordinary frontend type
;; checking (both `if` branches construct the SAME record schema, so the
;; `if` expression's own inferred type is that schema, same as a bare
;; `record-new` would be) specifically so it reaches the native backend's
;; OWN narrow-shape check rather than being rejected earlier by an
;; unrelated generic type error.
(deftest native-record-get-over-a-computed-non-nested-value-is-rejected-at-compile-time
  (let [schema (pr-str native-record-schema)
        source (str
                "(defn project-a [flag a b]
                   (record-get " schema "
                     (if (= flag 1)
                       (record-new " schema " a b true)
                       (record-new " schema " a b false))
                     :a))
                 (defn main [] 0)")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"record-get is only supported directly over a matching record-new construction on the native backend"
                          (compiler/compile-source source (target))))))

;; ADR 0063: the second native (x86-64/aarch64) value-representation
;; increment, immediately following ADR 0062's record -- a sealed variant
;; whose cases carry a bare `:i64`, a bare `:bool` (both `true` and `false`,
;; the SAME both-directions proof ADR 0062's own record `:bool` field
;; established), or nothing meaningfully read at all (a tag-only/"unit"-like
;; case whose branch body never references its own bound payload symbol --
;; see `native.scalar-variant-type?`'s own doc comment in `ir.cljc` for why
;; this ADR does not introduce a genuine zero-payload marker type). Real
;; native-process evidence matching every other deftest in this file: the
;; variant has NO independent runtime representation -- `(variant-match
;; schema (variant-new schema tag payload) branches)` is rewritten into TWO
;; synthetic stack slots (discriminant, payload) on the same `emit-let`/
;; `load-let` machinery this file's own `let`-sequencing deftests above
;; already prove correct, and dispatch is a REAL runtime compare-and-branch
;; chain over the stored discriminant, never a compile-time selection -- see
;; `emit-variant-dispatch` in both `backend/x86-64.cljc` and
;; `backend/aarch64.cljc`.
(def ^:private native-variant-schema
  '[:variant :native/traffic-signal [[:count :i64] [:enabled :bool] [:disabled :bool] [:idle :bool]]])

(deftest native-scalar-variant-construction-and-dispatch-round-trips-through-real-kexe-loader
  (let [schema (pr-str native-variant-schema)
        source (str
                "(defn check-count [n]
                   (variant-match " schema " (variant-new " schema " :count n)
                     [[:count v (= v n)] [:enabled v 0] [:disabled v 0] [:idle v 0]]))
                 (defn check-enabled []
                   (variant-match " schema " (variant-new " schema " :enabled true)
                     [[:count v 0] [:enabled v (if v 1 0)] [:disabled v 0] [:idle v 0]]))
                 (defn check-disabled []
                   (variant-match " schema " (variant-new " schema " :disabled false)
                     [[:count v 0] [:enabled v 0] [:disabled v (if v 0 1)] [:idle v 0]]))
                 (defn check-idle []
                   (variant-match " schema " (variant-new " schema " :idle false)
                     [[:count v 0] [:enabled v 0] [:disabled v 0] [:idle v 1]]))
                 (defn main [] (+ (check-count 42) (+ (check-enabled) (+ (check-disabled) (check-idle)))))")
        {:keys [envelope trust]} (signed source {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []} options)]
    (is (= 4 (get-in result [:evidence :result]))
        "all four checks passed (1 each): the :i64-payload case (:count)
         round-trips a genuinely runtime, parameter-derived value through
         construction+dispatch; the :bool-payload case round-trips TRUE
         (:enabled) and, separately, FALSE (:disabled); and the tag-only
         case (:idle) dispatches to the exact correct branch WITHOUT that
         branch ever reading its own bound payload symbol -- all four
         through a REAL native process (not a JVM-side oracle-value check),
         and each construction site emits the SAME full compare-and-branch
         chain over all four declared cases regardless of which one that
         particular site happens to construct (see `emit-variant-dispatch`'s
         own doc comment)")))

;; ADR 0063 fail-closed requirement (first vector, mirroring ADR 0062's own
;; first vector exactly): a variant case payload type this increment does
;; not admit (`:string`, disjoint from `ir/native-scalar-variant-type?`'s
;; `:i64`/`:bool` universe) is rejected at COMPILE TIME with the expected
;; native-admission error message, confirmed to be the native-specific gate
;; and not an unrelated generic type error by additionally confirming the
;; IDENTICAL source compiles successfully on `:wasm32-kotoba-v1` (whose
;; typed backend admits arbitrary typed values, including a string-cased
;; variant, unconditionally -- see `core.clj`'s own comment on why
;; `:wasm32-kotoba-v1`/`:js-kotoba-v1` need no content-based ir check at
;; all).
(deftest native-variant-with-an-unsupported-case-payload-type-is-rejected-at-compile-time
  (let [schema (pr-str '[:variant :native/string-case-variant [[:s :string]]])
        source (str
                "(defn project-s [] :string
                   (variant-match " schema " (variant-new " schema " :s \"x\") [[:s v v]]))
                 (defn main [] 0)")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"qualified native"
                          (compiler/compile-source source (target))))
    (is (= :wasm/v1 (:format (compiler/compile-source source :wasm32-kotoba-v1))))))

;; ADR 0063 fail-closed requirement (second vector, mirroring ADR 0062's own
;; second vector exactly): `variant-match`'s value operand must be a
;; DIRECTLY-nested, same-schema `variant-new` -- `emit-variant-match-of-new`
;; has no runtime variant representation to fall back on, so anything else
;; must be rejected with a clear compiler error, not silently miscompiled.
;; This source passes ordinary frontend type checking (both `if` branches
;; construct the SAME variant schema and case, so the `if` expression's own
;; inferred type is that schema, same as a bare `variant-new` would be)
;; specifically so it reaches the native backend's OWN narrow-shape check
;; rather than being rejected earlier by an unrelated generic type error.
(deftest native-variant-match-over-a-computed-non-nested-value-is-rejected-at-compile-time
  (let [schema (pr-str native-variant-schema)
        source (str
                "(defn project [flag n]
                   (variant-match " schema "
                     (if (= flag 1)
                       (variant-new " schema " :count n)
                       (variant-new " schema " :count n))
                     [[:count v v] [:enabled v 0] [:disabled v 0] [:idle v 0]]))
                 (defn main [] 0)")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"variant-match is only supported directly over a matching variant-new construction on the native backend"
                          (compiler/compile-source source (target))))))

;; ADR 0063 fail-closed requirement (third vector -- REAL native-process
;; trap evidence, not just a compile-time rejection): an out-of-range/
;; unrecognized variant discriminant must never silently do something
;; undefined. Reading `emit-variant-dispatch`'s own doc comment: this
;; repository's own pipeline provably CANNOT ever produce such a value --
;; frontend's shared, unchanged `variant-new` grammar rejects an undeclared
;; tag at compile time (`infer-expression-type`'s existing "variant
;; constructor tag is not declared" check); this backend's own codegen
;; independently re-derives the tag-to-ordinal lookup a second time and
;; throws if it does not resolve; `kotoba.compiler.verifier`'s OWN
;; independent re-derivation (the new `variant-new`/`variant-match` cases in
;; `verify-expr!` added by this ADR) enforces the identical narrow shape a
;; THIRD time; and -- unique to this repository's native track -- BOTH
;; `kotoba.compiler.signing/sign` and `signing/verify` unconditionally
;; re-run `verifier/verify-artifact!` (confirmed by reading `signing.clj`),
;; and `signing/verify` runs on EVERY execution (`native-executor/execute`
;; calls it, not just once at compile time) -- so there is no way, at any
;; layer, INCLUDING a hand-crafted artifact that bypasses `frontend/analyze`
;; entirely, to reach real `kexe-loader` execution with a variant
;; discriminant the type system did not itself validate as a declared
;; case's ordinal.
;;
;; Given that, this deftest does not attempt to smuggle a bad discriminant
;; through the compile/sign/execute pipeline (that pipeline's own defense in
;; depth makes it impossible by design, which is itself the point). Instead
;; it directly exercises `emit-variant-dispatch`'s own defensive UD2/BRK
;; fallback -- present in the compiled machine code as insurance, matching
;; this codebase's own `kernel-load-u8`/`kgraph-entity-at` defense-in-depth
;; style and the WASM Component Model track's own `variant-wat` discriminant
;; range check -- by calling the PRIVATE dispatch primitive directly (the
;; SAME technique this file already uses for other internals, e.g.
;; `@#'executor/run-process` above) with a literal ordinal (99) no admitted
;; `.kotoba` program could ever produce for a 3-case dispatch, wraps the
;; result in a minimal hand-assembled function (bypassing `emit-function`/
;; `emit-program`/`frontend/analyze`/`verifier/verify-artifact!` entirely),
;; and runs the resulting bytes through the SAME measured, real `kexe-
;; loader` native process every other deftest in this file uses -- proving
;; the fallback trap is REAL, present, byte-correct machine code, not merely
;; a code comment's claim.
(defn- raw-out-of-range-dispatch-code []
  (if (= (target) :aarch64-kotoba-v1)
    (let [emit-variant-dispatch (deref #'aarch64/emit-variant-dispatch)
          fuel-charge (deref #'aarch64/fuel-charge)
          insn (deref #'aarch64/insn)
          branch-specs [{:binder '_a :body 10} {:binder '_b :body 20} {:binder '_c :body 30}]
          body (emit-variant-dispatch 99 0 branch-specs {} 0)]
      ;; Mirrors `emit-function`'s own n=0-parameter prologue/epilogue
      ;; exactly (register-frame = 16*(quot 1 2) = 0, so no save/restore
      ;; frame at all): fuel-charge; stp fp,lr,[sp,#-16]!; mov fp,sp; <body>;
      ;; ldp fp,lr,[sp],#16; ret.
      (vec (concat fuel-charge (insn 0xa9bf7bfd) (insn 0x910003fd)
                   body
                   (insn 0xa8c17bfd) (insn 0xd65f03c0))))
    (let [emit-variant-dispatch (deref #'x86-64/emit-variant-dispatch)
          fuel-charge (deref #'x86-64/fuel-charge)
          le32 (deref #'x86-64/le32)
          branch-specs [{:binder '_a :body 10} {:binder '_b :body 20} {:binder '_c :body 30}]
          body (emit-variant-dispatch 99 0 branch-specs {}
                                      {:param-count 0 :pad? true :temp-depth 0
                                       :function-name 'raw-dispatch-trap :tail? true})]
      ;; Mirrors `emit-function`'s own n=0-parameter prologue/epilogue
      ;; exactly (pad? = (even? 0) = true, frame-bytes = 8*(0+1) = 8):
      ;; fuel-charge; push rax (alignment padding); <body>; add rsp,8; ret.
      (vec (concat fuel-charge [0x50] body [0x48 0x81 0xc4] (le32 8) [0xc3])))))

(deftest variant-dispatch-fallback-traps-on-a-discriminant-no-admitted-program-can-ever-produce
  (let [{:keys [loader-path]} @measured-runtime
        host-os ((deref #'executor/host-os))
        run-process (deref #'executor/run-process)
        runtime-environment (deref #'executor/runtime-environment)
        delete-tree! (deref #'executor/delete-tree!)
        isa (if (= (target) :aarch64-kotoba-v1) "aarch64" "x86_64")
        code (raw-out-of-range-dispatch-code)
        directory (java.nio.file.Files/createTempDirectory
                   "kotoba-raw-native-" (make-array java.nio.file.attribute.FileAttribute 0))
        code-file (java.io.File. (.toFile directory) "program.bin")]
    (try
      (atomic-output/write-bytes! (.getPath code-file) (byte-array (map unchecked-byte code)))
      (let [command [loader-path (.getPath code-file) "0" "0" isa "-"]
            process (run-process command (runtime-environment host-os)
                                 {:timeout-ms 5000 :output-limit 65536})
            report (edn/read-string (str/trim (:stdout process)))]
        (is (= :trap (:status report))
            "the dispatch chain's defensive fallback (UD2 on x86-64, BRK on
             aarch64) fired as real, executed machine code for a
             discriminant (99) outside the declared [0,3) case range --
             fail-closed, not silently undefined -- confirmed via the SAME
             real `kexe-loader` native process every other deftest in this
             file uses"))
      (finally (delete-tree! (.toFile directory))))))
