#!/usr/bin/env nbb
(ns safari-matrix
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def root (.resolve path (.dirname path *file*) ".."))
(def tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-safari-matrix-")))
(def nbb-cli (.join path root "node_modules" "nbb" "cli.js"))
(def kotoba (.join path root "bin" "kotoba"))
(defn run! [args env]
  (let [result (.spawnSync child js/process.execPath (clj->js args)
                           #js {:cwd root :stdio "inherit"
                                :env (js/Object.assign #js {} js/process.env (clj->js env))})]
    (when (.-error result) (throw (.-error result)))
    (when-not (zero? (or (.-status result) 70))
      (throw (js/Error. "Safari matrix command failed")))))
(defn compile! [source output & more]
  (run! (into [nbb-cli kotoba "-M" "compile" (.join path root source)
               "--target" "wasm32-browser" "--output" (.join path tmp output)] more) {}))

(if (= "1" js/process.env.KOTOBA_SAFARI_STATIC_CHECK)
  (do (.rmSync fs tmp #js {:recursive true :force true})
      (println "safari-matrix: orchestration parsed"))
  (try
    (compile! "examples/structured.kotoba" "program.wasm")
    (compile! "examples/heap.kotoba" "heap.wasm")
    (compile! "tests/browser/capability.kotoba" "capability.wasm"
              "--policy" (.join path root "examples/capability-policy.edn"))
    (run! [(.join path root "scripts/safari-webdriver.mjs") root tmp] {})
    (run! [nbb-cli (.join path root "scripts/check-browser-evidence.cljs")]
          {:KOTOBA_SAFARI_ONLY "1"})
    (finally (.rmSync fs tmp #js {:recursive true :force true}))))
