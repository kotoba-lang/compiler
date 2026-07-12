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
(defn run! [args]
  (let [result (.spawnSync child js/process.execPath (clj->js (into [nbb-cli kotoba "-M"] args))
                           #js {:cwd lib/root :encoding "utf8" :maxBuffer 1048576})]
    (when (.-error result) (throw (.-error result)))
    (lib/ensure! (zero? (or (.-status result) 70))
                 (str "windows-profile: command failed: " (.-stderr result)))
    result))

(try
  (run! ["compile" source "--target" "x86_64-windows" "--output" (artifact "first.kexe")])
  (run! ["compile" source "--target" "x86_64-windows" "--output" (artifact "second.kexe")])
  (let [first (.readFileSync fs (artifact "first.kexe"))
        second (.readFileSync fs (artifact "second.kexe"))
        text (.toString first "utf8")]
    (lib/ensure! (.equals first second) "windows-profile: artifact is not reproducible")
    (doseq [needle [":target :x86_64-windows-kotoba-v1"
                    ":os :windows" ":abi :kotoba-sysv-v1"
                    ":runtime :kotoba-windows-supervisor-v1"
                    ":mode :hidden-context-r9"]]
      (lib/ensure! (.includes text needle) (str "windows-profile: missing binding " needle))))
  (run! ["verify" (artifact "first.kexe")])
  (println "windows-profile: reproducible x86_64 Windows KEXE verified")
  (finally (.rmSync fs tmp #js {:recursive true :force true})))
