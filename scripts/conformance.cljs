#!/usr/bin/env nbb
(ns kotoba-conformance
  (:require [clojure.string :as str]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def root (.resolve path (.dirname path *file*) ".."))
(def tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-conformance-")))
(def kotoba (.join path root "bin" "kotoba"))
(defn file [name] (.join path tmp name))
(defn write! [name value] (.writeFileSync fs (file name) value))
(defn read! [name] (.readFileSync fs (file name) "utf8"))
(defn ensure! [condition message] (when-not condition (throw (js/Error. message))))

(defn run
  ([command args] (run command args {}))
  ([command args {:keys [env allow-failure?]}]
   (let [result (.spawnSync child command (clj->js args)
                            #js {:cwd root :encoding "utf8" :maxBuffer 1048576
                                 :env (js/Object.assign #js {} js/process.env (clj->js env))})
         status (or (.-status result) 70)
         value {:status status :stdout (or (.-stdout result) "")
                :stderr (or (.-stderr result) "")}]
     (when (and (.-error result) (not allow-failure?)) (throw (.-error result)))
     (when (and (not allow-failure?) (not= 0 status))
       (throw (js/Error. (str "command failed: " command " " (str/join " " args)
                              "\n" (:stderr value)))))
     value)))

(defn k [& args] (run "nbb" (into [kotoba "-M"] args)))
(defn k-fail [& args]
  (run "nbb" (into [kotoba "-M"] args) {:allow-failure? true}))
(defn contains-text? [text needle] (not= -1 (.indexOf text needle)))
(defn offset [artifact isa symbol suffix]
  (let [output (file (str isa "-" symbol suffix ".bin"))
        meta (:stdout (k "extract-native" artifact "--symbol" symbol "--output" output))
        [_ value] (re-find #":offset ([0-9]+)" meta)]
    (ensure! value (str "missing native offset for " symbol))
    [output value]))

(defn native-check [artifact isa symbol expected & args]
  (let [[binary off] (offset artifact isa symbol "")
        got (str/trim (:stdout (run (file "kexe-loader")
                                   (into [binary off (str (count args)) isa "-"] args))))]
    (ensure! (= (str expected) got)
             (str "native " isa " " symbol " expected " expected ", got " got))))

(defn native-trap [artifact isa symbol signal & args]
  (let [[binary off] (offset artifact isa symbol "-trap")
        result (run (file "kexe-loader")
                    (into [binary off (str (count args)) isa "-"] args)
                    {:allow-failure? true})]
    (ensure! (not= 0 (:status result)) (str symbol " unexpectedly returned"))
    (ensure! (contains-text? (:stderr result)
                             (str "KEXE_TRAP {:kind :signal :signal :" signal "}"))
             (str symbol " trap mismatch: " (:stderr result)))))

(defn native-cap-check [artifact isa signal]
  (let [[binary off] (offset artifact isa "helper" "-cap")
        allowed (run (file "kexe-loader") [binary off "1" isa "7" "41"])
        denied (run (file "kexe-loader") [binary off "1" isa "-" "41"]
                    {:allow-failure? true})]
    (ensure! (= "42" (str/trim (:stdout allowed))) "native capability result mismatch")
    (ensure! (and (not= 0 (:status denied))
                  (contains-text? (:stderr denied) (str ":signal :" signal)))
             "native capability denial mismatch")))

(defn native-sandbox [artifact isa probe reason platform]
  (let [[binary off] (offset artifact isa "main" "-sandbox")
        result (run (file "kexe-loader") [binary off "0" isa "-"]
                    {:env {(keyword probe) "1"} :allow-failure? true})
        expected (if (= platform "linux") ":signal :SIGSYS"
                     (str ":reason :" reason "-denied"))]
    (ensure! (and (not= 0 (:status result)) (contains-text? (:stderr result) expected))
             (str "sandbox did not deny " reason))))

(defn native-timeout [artifact isa]
  (let [[binary off] (offset artifact isa "main" "-timeout")
        result (run (file "kexe-loader") [binary off "0" isa "-"]
                    {:env {:KEXE_TIMEOUT_PROBE "1"} :allow-failure? true})]
    (ensure! (and (not= 0 (:status result))
                  (contains-text? (:stderr result) ":reason :wall-timeout"))
             "native supervisor timeout mismatch")))

(defn native-report [artifact isa]
  (let [[binary off] (offset artifact isa "main" "-report")
        result (run (file "kexe-loader") [binary off "0" isa "-"]
                    {:env {:KEXE_STRUCTURED_REPORT "1"}})]
    (ensure! (= "{:status :ok :result 42 :fuel {:initial 512 :remaining 509} :heap {:capacity 4096 :used 0}}"
                (str/trim (:stdout result)))
             "native structured report mismatch")))

(defn attested-run [signed isa]
  (k "measure-runtime" "--output" (file (str isa "-runtime.edn"))
     "--loader-output" (file (str isa "-attested-loader")))
  (k "trust-runtime" (file (str isa "-runtime.edn")) "--trust" (file "trust.edn")
     "--output" (file (str isa "-runtime-trust.edn")))
  (k "run" signed "--trust" (file (str isa "-runtime-trust.edn"))
     "--runtime" (file (str isa "-runtime.edn")) "--loader" (file (str isa "-attested-loader"))
     "--policy" (file "pure-policy.edn") "--input" (file "input.edn")
     "--executor-key" (file "signing-key.edn") "--now" "1500"
     "--result-output" (file (str isa "-run-result.edn"))
     "--output" (file (str isa "-run-receipt.edn")))
  (k "verify-receipt" (file (str isa "-run-receipt.edn")) "--signed" signed
     "--trust" (file (str isa "-runtime-trust.edn")) "--policy" (file "pure-policy.edn")
     "--input" (file "input.edn") "--result" (file (str isa "-run-result.edn")) "--now" "1500")
  (let [result (read! (str isa "-run-result.edn"))
        receipt (read! (str isa "-run-receipt.edn"))]
    (doseq [needle [":status :ok" ":result 42" ":format :kotoba.native-runtime/v6"
                    ":target-profile {:format :kotoba.target-profile/v1"
                    ":loader-binary-sha256" ":assembler-binary-sha256"
                    ":linker-binary-sha256" ":compiler-resource-sha256"
                    ":system-header-closure-sha256"]]
      (ensure! (contains-text? result needle) (str "attested result missing " needle)))
    (ensure! (contains-text? receipt ":remaining 509") "receipt fuel mismatch")))

(defn compile-artifacts! []
  (k "compile" (.join path root "examples" "capability.kotoba") "--target" "wasm32"
     "--policy" (.join path root "examples" "capability-policy.edn") "--output" (file "capability.wasm"))
  (doseq [[source target output]
          [["structured.kotoba" "wasm32" "program.wasm"] ["fuel.kotoba" "wasm32" "fuel.wasm"]
           ["i64-semantics.kotoba" "wasm32" "i64.wasm"] ["heap.kotoba" "wasm32" "heap.wasm"]
           ["list.kotoba" "wasm32" "list.wasm"] ["structured.kotoba" "x86_64" "x86_64.kexe"]
           ["fuel.kotoba" "x86_64" "x86_64-fuel.kexe"] ["i64-semantics.kotoba" "x86_64" "x86_64-i64.kexe"]
           ["structured.kotoba" "aarch64" "aarch64.kexe"] ["i64-semantics.kotoba" "aarch64" "aarch64-i64.kexe"]]]
    (k "compile" (.join path root "examples" source) "--target" target "--output" (file output)))
  (doseq [artifact ["x86_64.kexe" "x86_64-fuel.kexe" "aarch64.kexe"]]
    (k "verify" (file artifact))))

(defn native-suite [platform machine]
  (let [arm? (contains? #{"arm64" "aarch64"} machine)
        isa (if arm? "aarch64" "x86_64")
        artifact (file (str isa ".kexe"))
        fuel (file (str isa "-fuel.kexe"))
        i64 (file (str isa "-i64.kexe"))
        signal (if arm? "SIGTRAP" "SIGFPE")
        fuel-signal (if arm? "SIGTRAP" "SIGILL")]
    (run "cc" ["-std=c11" "-O2" "-Wall" "-Wextra" "-Werror"
                (.join path root "tools" "kexe_loader.c") "-o" (file "kexe-loader")])
    (native-timeout artifact isa)
    (native-report artifact isa)
    (when arm?
      (k "sign" artifact "--key" (file "signing-key.edn") "--not-before" "1000"
         "--expires" "2000" "--output" (file "aarch64.signed.kexe")))
    (attested-run (file (str isa ".signed.kexe")) isa)
    (doseq [[probe reason] [["KEXE_FILESYSTEM_PROBE" "filesystem"]
                            ["KEXE_NETWORK_PROBE" "network"]
                            ["KEXE_PROCESS_PROBE" "process"]]]
      (native-sandbox artifact isa probe reason platform))
    (native-check artifact isa "score" 12 "-7" "2")
    (native-check artifact isa "calc" 21 "20" "4")
    (native-check artifact isa "relations" 10 "7" "3")
    (native-check artifact isa "relations" 13 "3" "3")
    (native-trap artifact isa "calc" signal "20" "0")
    (native-trap artifact isa "calc" signal "-9223372036854775808" "-1")
    (when arm?
      (k "compile" (.join path root "examples" "fuel.kotoba") "--target" "aarch64" "--output" fuel)
      (k "verify" fuel))
    (native-check fuel isa "fact" 3628800 "10")
    (native-trap fuel isa "forever" fuel-signal "0")
    (doseq [[name expected args]
            [["add" "-9223372036854775808" ["9223372036854775807" "1"]]
             ["subtract" "9223372036854775807" ["-9223372036854775808" "1"]]
             ["multiply" "-2" ["9223372036854775807" "2"]]
             ["negate" "-9223372036854775808" ["-9223372036854775808"]]
             ["choose" "22" ["0" "11" "22"]] ["choose" "11" ["-1" "11" "22"]]]]
      (apply native-check i64 isa name expected args))
    (let [cap (file (str isa "-cap.kexe"))]
      (k "compile" (.join path root "examples" "capability.kotoba") "--target" isa
         "--policy" (.join path root "examples" "capability-policy.edn") "--output" cap)
      (k "verify" cap)
      (native-cap-check cap isa (if arm? "SIGTRAP" "SIGILL")))
    (println (str "conformance: native " isa " runtime vector passed under W^X loader"))))

(try
  (write! "trailing-policy.edn" "{} {}\n")
  (write! "bounded-source.kotoba" "(defn main [] 0)\n")
  (let [result (k-fail "check" (file "bounded-source.kotoba") "--policy" (file "trailing-policy.edn"))]
    (ensure! (= 65 (:status result)) "trailing EDN exit must be 65")
    (ensure! (and (contains-text? (:stderr result) "EDN input contains trailing forms")
                  (= 1 (count (str/split-lines (:stderr result)))))
             "trailing EDN diagnostic mismatch")
    (ensure! (not (re-find #"Execution error|Full report|kotoba\.compiler.*\.clj" (:stderr result)))
             "CLI diagnostic leaked stack information"))
  ;; --policy structural-resource-attack rejections, mirroring
  ;; kotoba.compiler.bounded-edn-test's "rejects-structural-resource-attacks"
  ;; cases exactly (same depth/token/node/string limits, now enforced on
  ;; BOTH the JVM path -- bounded-edn/read-file -- and this repo's
  ;; nbb-native fast path -- kotoba.compiler.nbb.cli/read-edn-form! --
  ;; whichever `bin/kotoba` dispatches `check` to, since it always does for
  ;; this command; see that namespace's own comment for the specific gap
  ;; this closed).
  (write! "deep-policy.edn" (str (apply str (repeat 129 "[")) (apply str (repeat 129 "]"))))
  (let [result (k-fail "check" (file "bounded-source.kotoba") "--policy" (file "deep-policy.edn"))]
    (ensure! (= 65 (:status result)) "over-depth policy exit must be 65")
    (ensure! (contains-text? (:stderr result) "nesting exceeds limit")
             "over-depth policy diagnostic mismatch"))
  (write! "oversized-token-policy.edn" (apply str (repeat 4097 "9")))
  (let [result (k-fail "check" (file "bounded-source.kotoba") "--policy" (file "oversized-token-policy.edn"))]
    (ensure! (= 65 (:status result)) "oversized-token policy exit must be 65")
    (ensure! (contains-text? (:stderr result) "token exceeds limit")
             "oversized-token policy diagnostic mismatch"))
  (write! "too-many-nodes-policy.edn"
          (str "[" (str/join " " (repeat 200001 "1")) "]"))
  (let [result (k-fail "check" (file "bounded-source.kotoba") "--policy" (file "too-many-nodes-policy.edn"))]
    (ensure! (= 65 (:status result)) "too-many-nodes policy exit must be 65")
    (ensure! (contains-text? (:stderr result) "too many nodes")
             "too-many-nodes policy diagnostic mismatch"))
  ;; NOT tested here: kotoba.compiler.nbb.cli/validate-edn-shape!'s
  ;; max-string-chars check (mirrors bounded-edn's own `max-string-chars`,
  ;; also 1MiB) is real but PROVABLY UNREACHABLE through this CLI as
  ;; currently configured -- `nbb.io/read-text-file`'s overall file-byte
  ;; cap is ALSO exactly 1MiB (a deliberate, documented choice: "the
  ;; stricter of [bounded-edn's] two limits", see that namespace's own
  ;; comment), so any policy file containing a string anywhere near
  ;; max-string-chars in length already exceeds the file-level cap first,
  ;; failing closed with "input exceeds byte limit" instead (verified live
  ;; while writing this test: asserting "string exceeds limit" here failed
  ;; every time with the byte-limit message instead). The JVM path doesn't
  ;; have this coincidence (`bounded-edn/max-edn-bytes` is 8MiB for
  ;; `--policy`, well above its own 1MiB `max-string-chars`), so
  ;; `bounded-edn-test.clj`'s "oversized string" case there DOES exercise
  ;; the check directly (calling `read-string` on already-decoded text,
  ;; with no byte-size gate in front of it at all). Both paths fail
  ;; closed either way -- this is a documented consequence of nbb.io's
  ;; existing single-limit design, not a gap this change introduces.
  (write! "dispatch-form-policy.edn" "#inst \"2026-07-11\"")
  (let [result (k-fail "check" (file "bounded-source.kotoba") "--policy" (file "dispatch-form-policy.edn"))]
    (ensure! (= 65 (:status result)) "dispatch-form policy exit must be 65")
    (ensure! (contains-text? (:stderr result) "reader dispatch")
             "dispatch-form policy diagnostic mismatch"))
  (.writeFileSync fs (file "oversized-source.kotoba") (js/Buffer.alloc 1048577))
  (let [result (k-fail "check" (file "oversized-source.kotoba"))]
    (ensure! (and (= 65 (:status result)) (contains-text? (:stderr result) "input exceeds byte limit"))
             "oversized source was not rejected"))
  (let [denied (k-fail "check" (.join path root "examples" "capability.kotoba"))]
    (ensure! (contains-text? (:stderr denied) "capability policy denies required effects")
             "capability default denial failed"))
  (compile-artifacts!)
  (k "keygen" "--output" (file "signing-key.edn"))
  (ensure! (= 384 (bit-and 511 (.-mode (.statSync fs (file "signing-key.edn")))))
           "signing key permissions are not 0600")
  (k "public-key" (file "signing-key.edn") "--output" (file "verification-key.edn"))
  (ensure! (not (contains-text? (read! "verification-key.edn") ":private-key"))
           "verification key leaked private material")
  (k "trust-key" (file "verification-key.edn") "--output" (file "trust.edn"))
  (k "sign" (file "x86_64.kexe") "--key" (file "signing-key.edn") "--not-before" "1000"
     "--expires" "2000" "--output" (file "x86_64.signed.kexe"))
  (k "verify-signed" (file "x86_64.signed.kexe") "--trust" (file "trust.edn") "--now" "1500")
  (write! "pure-policy.edn" "{:allow #{}}\n")
  (write! "input.edn" "{:args []}\n")
  (write! "output.edn" "42\n")
  (k "receipt" "--signed" (file "x86_64.signed.kexe") "--trust" (file "trust.edn")
     "--policy" (file "pure-policy.edn") "--input" (file "input.edn") "--result" (file "output.edn")
     "--now" "1500" "--started-at" "1400" "--finished-at" "1401" "--status" "ok"
     "--target" "x86_64" "--entry" "main" "--fuel-initial" "256" "--fuel-remaining" "255"
     "--executor-key" (file "signing-key.edn") "--output" (file "run.receipt.edn"))
  (k "verify-receipt" (file "run.receipt.edn") "--signed" (file "x86_64.signed.kexe")
     "--trust" (file "trust.edn") "--policy" (file "pure-policy.edn") "--input" (file "input.edn")
     "--result" (file "output.edn") "--now" "1500")
  (write! "receipt-chain.edn" (str "[" (read! "run.receipt.edn") "]\n"))
  (ensure! (contains-text? (:stdout (k "verify-chain" (file "receipt-chain.edn")
                                      "--trust" (file "trust.edn")))
                           ":scope :executor-attested-chain/v1")
           "receipt chain scope mismatch")
  (run "node" [(.join path root "scripts" "wasm-conformance.mjs")
               (file "program.wasm") (file "fuel.wasm") (file "i64.wasm")
               (file "capability.wasm") (file "heap.wasm") (file "list.wasm")]
       {:env {}})
  (let [platform (.platform os)
        machine (.arch os)]
    (when (or (and (= platform "linux") (= machine "x64"))
              (and (contains? #{"linux" "darwin"} platform)
                   (contains? #{"arm64" "aarch64"} machine)))
      (native-suite platform machine)))
  (println "conformance: wasm32 and sealed native targets verified")
  (finally (.rmSync fs tmp #js {:recursive true :force true})))
