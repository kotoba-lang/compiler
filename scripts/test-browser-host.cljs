#!/usr/bin/env nbb
(ns test-browser-host
  (:require ["node:child_process" :as child]
            ["node:path" :as path]))

;; NBB owns repository orchestration. The child is intentionally a plain ES
;; module test because it exercises the exact dependency-free browser artifact.
(let [root (.resolve path (.dirname path *file*) "..")
      test-file (.join path root "scripts" "test-browser-host.mjs")
      result (.spawnSync child js/process.execPath #js [test-file]
                         #js {:cwd root :encoding "utf8" :maxBuffer 1048576})]
  (when (seq (or (.-stdout result) ""))
    (.write js/process.stdout (.-stdout result)))
  (when (seq (or (.-stderr result) ""))
    (.write js/process.stderr (.-stderr result)))
  (when (.-error result) (throw (.-error result)))
  (when-not (zero? (or (.-status result) 70))
    (throw (js/Error. "browser host ES module test failed"))))
