#!/usr/bin/env nbb
(ns test-review-fuzz-corpus
  (:require [scripts.lib :as lib]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def tmp (lib/temp-dir "kotoba-review-corpus-test-"))
(def review (lib/join lib/root "scripts" "review-fuzz-corpus.cljs"))
(defn run-review [directory]
  (lib/run "npx" ["--no-install" "nbb" review directory "--dry-run"]
           {:allow-failure? true}))

(try
  (doseq [name ["valid" "unsafe" "oversize" "empty"]]
    (.mkdirSync fs (lib/join tmp name)))
  (let [valid-file (lib/join tmp "valid" "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
    (lib/write-text! valid-file "review candidate")
    (let [report (run-review (lib/join tmp "valid"))]
      (lib/ensure! (and (= 0 (:status report))
                        (.includes (:stdout report) ":files 1")
                        (.includes (:stdout report) ":new 1"))
                   "review-corpus valid report mismatch"))
    (let [unsafe (lib/join tmp "unsafe" "not-a-content-hash")]
      (lib/write-text! unsafe "hostile")
      (lib/ensure! (not= 0 (:status (run-review (lib/join tmp "unsafe"))))
                   "review-corpus accepted unsafe filename")
      (.unlinkSync fs unsafe))
    (.symlinkSync fs valid-file (lib/join tmp "unsafe" "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
    (lib/ensure! (not= 0 (:status (run-review (lib/join tmp "unsafe"))))
                 "review-corpus accepted symlink")
    (.writeFileSync fs (lib/join tmp "oversize" "cccccccccccccccccccccccccccccccccccccccc")
                    (js/Buffer.alloc 1025))
    (lib/ensure! (not= 0 (:status (run-review (lib/join tmp "oversize"))))
                 "review-corpus accepted oversized input")
    (lib/ensure! (not= 0 (:status (run-review (lib/join tmp "empty"))))
                 "review-corpus accepted empty input")
    (println "review-corpus: validation self-test passed"))
  (finally (lib/remove-tree! tmp)))
