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
         zero-temp-depth guard) recursion, well within the 256-call fuel budget")
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
