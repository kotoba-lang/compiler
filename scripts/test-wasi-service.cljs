#!/usr/bin/env nbb
(ns test-wasi-service
  (:require [scripts.lib :as lib]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-wasi-service-"))
      nbb-cli (.join path lib/root "node_modules" "nbb" "cli.js")
      kotoba (.join path lib/root "bin" "kotoba")
      compile! (fn [source output & args]
                 (let [result (.spawnSync child js/process.execPath
                                          (clj->js (into [nbb-cli kotoba "-M" "compile" source
                                                          "--target" "wasm32-wasi"
                                                          "--output" output] args))
                                          #js {:cwd lib/root :encoding "utf8" :maxBuffer 1048576})]
                   (when (.-error result) (throw (.-error result)))
                   (lib/ensure! (zero? (or (.-status result) 70))
                                (str "WASI service compilation failed: " (.-stderr result)))))]
  (try
    (compile! (.join path lib/root "examples" "structured.kotoba")
              (.join path tmp "structured.wasm"))
    (compile! (.join path lib/root "tests" "browser" "capability.kotoba")
              (.join path tmp "capability.wasm")
              "--policy" (.join path lib/root "examples" "capability-policy.edn"))
    (let [result (.spawnSync child js/process.execPath
                             #js [(.join path lib/root "scripts" "test-wasi-service.mjs")
                                  (.join path tmp "structured.wasm")
                                  (.join path tmp "capability.wasm")]
                             #js {:cwd lib/root :encoding "utf8" :maxBuffer 1048576})]
      (when (seq (or (.-stdout result) "")) (.write js/process.stdout (.-stdout result)))
      (when (seq (or (.-stderr result) "")) (.write js/process.stderr (.-stderr result)))
      (when (.-error result) (throw (.-error result)))
      (lib/ensure! (zero? (or (.-status result) 70)) "WASI service test failed"))
    (finally (.rmSync fs tmp #js {:recursive true :force true}))))
