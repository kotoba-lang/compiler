#!/usr/bin/env nbb
(ns test-wasmtime
  (:require [scripts.lib :as lib]
            [scripts.wasmtime-tool :as tool]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(defn run! [command args allow-failure?]
  (let [result (.spawnSync child command (clj->js args)
                           #js {:cwd lib/root :encoding "utf8" :maxBuffer 1048576})]
    (when (.-error result) (throw (.-error result)))
    (when (and (not allow-failure?) (not= 0 (or (.-status result) 70)))
      (throw (js/Error. (str "Wasmtime command failed: " (.-stderr result)))))
    result))

(defn compile! [source output]
  (let [nbb-cli (.join path lib/root "node_modules" "nbb" "cli.js")]
    (run! js/process.execPath
          [nbb-cli (.join path lib/root "bin" "kotoba") "-M" "compile" source
           "--target" "wasm32-wasi" "--output" output] false)))

(-> (tool/ensure!)
    (.then
     (fn [wasmtime]
       (let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-wasmtime-"))
             structured (.join path tmp "structured.wasm")
             fuel (.join path tmp "fuel.wasm")]
         (try
           (compile! (.join path lib/root "examples" "structured.kotoba") structured)
           (compile! (.join path lib/root "examples" "fuel.kotoba") fuel)
           (doseq [[module function args expected]
                   [[structured "main" [] "42"]
                    [structured "score" ["-7" "2"] "12"]
                    [structured "calc" ["20" "4"] "21"]
                    [fuel "fact" ["10"] "3628800"]]]
             (let [result (run! wasmtime (into ["run" "--invoke" function module] args) false)]
               (lib/ensure! (= expected (.trim (.-stdout result)))
                            (str "Wasmtime result mismatch for " function))))
           (let [trapped (run! wasmtime ["run" "--invoke" "forever" fuel "0"] true)]
             (lib/ensure! (not= 0 (or (.-status trapped) 70))
                          "Wasmtime fuel exhaustion did not trap"))
           (println (str "wasmtime: independent engine semantics and fuel trap passed on "
                         (.-platform js/process) "/" (.-arch js/process)))
           (finally (.rmSync fs tmp #js {:recursive true :force true})))))))
