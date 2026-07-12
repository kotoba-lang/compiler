#!/usr/bin/env nbb
(ns windows-profile-conformance
  (:require [scripts.lib :as lib]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-windows-profile-")))
(def nbb-cli (.join path lib/root "node_modules" "nbb" "cli.js"))
(def kotoba (.join path lib/root "bin" "kotoba"))
(def source (.join path lib/root "examples" "structured.kotoba"))
(defn artifact [name] (.join path tmp name))
(defn run-k! [args]
  (let [result (.spawnSync child js/process.execPath (clj->js (into [nbb-cli kotoba "-M"] args))
                           #js {:cwd lib/root :encoding "utf8" :maxBuffer 1048576})]
    (when (.-error result) (throw (.-error result)))
    (lib/ensure! (zero? (or (.-status result) 70))
                 (str "windows-profile: command failed: " (.-stderr result)))
    result))
(defn run-external
  ([command args] (run-external command args {} false))
  ([command args env allow-failure?]
   (let [result (.spawnSync child command (clj->js args)
                            #js {:cwd lib/root :encoding "utf8" :maxBuffer 1048576
                                 :env (js/Object.assign #js {} js/process.env (clj->js env))})]
     (when (.-error result) (throw (.-error result)))
     (when-not (or allow-failure? (zero? (or (.-status result) 70)))
       (throw (js/Error. (str "windows-profile: external command failed: " (.-stderr result)))))
     result)))
(defn extract! [kexe symbol output]
  (let [result (run-k! ["extract-native" kexe "--symbol" symbol "--output" output])
        text (.-stdout result)
        [_ offset] (re-find #":offset ([0-9]+)" text)
        [_ arity] (re-find #":arity ([0-9]+)" text)]
    (lib/ensure! (and offset arity) (str "windows-profile: missing export metadata for " symbol))
    [offset arity]))
(defn loader-check! [loader raw offset arity expected & args]
  (let [result (run-external loader (into [raw offset arity "x86_64" "-"] args))]
    (lib/ensure! (= expected (.trim (.-stdout result)))
                 (str "windows-profile: runtime result mismatch, expected " expected))))

(try
  (run-k! ["compile" source "--target" "x86_64-windows" "--output" (artifact "first.kexe")])
  (run-k! ["compile" source "--target" "x86_64-windows" "--output" (artifact "second.kexe")])
  (let [first (.readFileSync fs (artifact "first.kexe"))
        second (.readFileSync fs (artifact "second.kexe"))
        text (.toString first "utf8")]
    (lib/ensure! (.equals first second) "windows-profile: artifact is not reproducible")
    (doseq [needle [":target :x86_64-windows-kotoba-v1"
                    ":os :windows" ":abi :kotoba-sysv-v1"
                    ":runtime :kotoba-windows-supervisor-v1"
                    ":mode :hidden-context-r9"]]
      (lib/ensure! (.includes text needle) (str "windows-profile: missing binding " needle))))
  (run-k! ["verify" (artifact "first.kexe")])
  (println "windows-profile: reproducible x86_64 Windows KEXE verified")
  (when (= "win32" (.-platform js/process))
    (let [loader (artifact "kexe-loader-windows.exe")
          raw (artifact "program.bin")
          _ (run-external "clang" ["-std=c11" "-O2" "-Wall" "-Wextra" "-Werror"
                                    (.join path lib/root "tools/kexe_loader_windows.c")
                                    "-o" loader "-ladvapi32"])
          [main-offset main-arity] (extract! (artifact "first.kexe") "main" raw)
          [score-offset score-arity] (extract! (artifact "first.kexe") "score" raw)
          [calc-offset calc-arity] (extract! (artifact "first.kexe") "calc" raw)]
      (loader-check! loader raw main-offset main-arity "42")
      (loader-check! loader raw score-offset score-arity "12" "-7" "2")
      (loader-check! loader raw calc-offset calc-arity "21" "20" "4")
      (let [structured (run-external loader [raw main-offset main-arity "x86_64" "-"]
                                             {:KEXE_STRUCTURED_REPORT "1"} false)]
        (lib/ensure! (= "{:status :ok :result 42 :fuel {:initial 256 :remaining 253} :heap {:capacity 4096 :used 0}}"
                        (.trim (.-stdout structured)))
                     "windows-profile: structured supervisor report mismatch"))
      (doseq [[probe reason] [[:KEXE_FILESYSTEM_PROBE "filesystem-denied"]
                              [:KEXE_PROCESS_PROBE "process-denied"]]]
        (let [result (run-external loader [raw main-offset main-arity "x86_64" "-"]
                                   {probe "1"} true)]
          (lib/ensure! (= 77 (.-status result)) (str "windows-profile: " reason " exit mismatch"))
          (lib/ensure! (.includes (.-stderr result) (str ":reason :" reason))
                       (str "windows-profile: " reason " report mismatch"))))
      (run-k! ["compile" (.join path lib/root "examples/heap.kotoba")
               "--target" "x86_64-windows" "--output" (artifact "heap.kexe")])
      (let [[offset arity] (extract! (artifact "heap.kexe") "main" (artifact "heap.bin"))
            report (run-external loader [(artifact "heap.bin") offset arity "x86_64" "-"]
                                 {:KEXE_STRUCTURED_REPORT "1"} false)]
        (lib/ensure! (= "{:status :ok :result 42 :fuel {:initial 256 :remaining 255} :heap {:capacity 4096 :used 2}}"
                        (.trim (.-stdout report)))
                     "windows-profile: bounded heap report mismatch"))
      (run-k! ["compile" (.join path lib/root "tests/browser/capability.kotoba")
               "--target" "x86_64-windows" "--policy" (.join path lib/root "examples/capability-policy.edn")
               "--output" (artifact "capability.kexe")])
      (let [[offset arity] (extract! (artifact "capability.kexe") "main" (artifact "capability.bin"))]
        (let [allowed (run-external loader [(artifact "capability.bin") offset arity "x86_64" "7"])]
          (lib/ensure! (= "42" (.trim (.-stdout allowed))) "windows-profile: capability result mismatch"))
        (let [denied (run-external loader [(artifact "capability.bin") offset arity "x86_64" "-"] {} true)]
          (lib/ensure! (not= 0 (.-status denied)) "windows-profile: denied capability executed")))
      (println "windows-profile: W^X supervisor, SysV adapter, sandbox, capability, fuel, and heap vectors passed")
      (.writeFileSync fs (artifact "policy.edn") "{:allow #{}}\n")
      (.writeFileSync fs (artifact "input.edn") "{:args []}\n")
      (run-k! ["keygen" "--output" (artifact "key.edn")])
      (run-k! ["public-key" (artifact "key.edn") "--output" (artifact "public.edn")])
      (run-k! ["trust-key" (artifact "public.edn") "--output" (artifact "trust.edn")])
      (run-k! ["measure-runtime" "--output" (artifact "runtime.edn")
               "--loader-output" (artifact "measured-loader.exe")])
      (run-k! ["trust-runtime" (artifact "runtime.edn") "--trust" (artifact "trust.edn")
               "--output" (artifact "runtime-trust.edn")])
      (run-k! ["sign" (artifact "first.kexe") "--key" (artifact "key.edn")
               "--not-before" "1000" "--expires" "2000" "--output" (artifact "signed.kexe")])
      (run-k! ["run" (artifact "signed.kexe") "--trust" (artifact "runtime-trust.edn")
               "--runtime" (artifact "runtime.edn") "--loader" (artifact "measured-loader.exe")
               "--policy" (artifact "policy.edn") "--input" (artifact "input.edn")
               "--executor-key" (artifact "key.edn") "--now" "1500"
               "--result-output" (artifact "result.edn") "--output" (artifact "receipt.edn")])
      (run-k! ["verify-receipt" (artifact "receipt.edn")
               "--signed" (artifact "signed.kexe") "--trust" (artifact "runtime-trust.edn")
               "--policy" (artifact "policy.edn") "--input" (artifact "input.edn")
               "--result" (artifact "result.edn") "--now" "1500"])
      (let [result (.readFileSync fs (artifact "result.edn") "utf8")]
        (lib/ensure! (and (.includes result ":status :ok")
                          (.includes result ":result 42")
                          (.includes result ":format :kotoba.native-runtime/v6"))
                     "windows-profile: measured product evidence mismatch"))
      (lib/ensure! (.includes (.readFileSync fs (artifact "runtime.edn") "utf8")
                              ":runtime :kotoba-windows-supervisor-v1")
                   "windows-profile: measured runtime profile mismatch")
      (println "windows-profile: measured trust-runtime and signed product run passed")))
  (finally (.rmSync fs tmp #js {:recursive true :force true})))
