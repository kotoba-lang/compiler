#!/usr/bin/env nbb
(ns release-conformance
  (:require [scripts.lib :as lib]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-release-")))
(def nbb-cli (.join path lib/root "node_modules" "nbb" "cli.js"))
(def kotoba (.join path lib/root "bin" "kotoba"))
(defn file [name] (.join path tmp name))
(defn run-k! [args allow-failure?]
  (let [result (.spawnSync child js/process.execPath (clj->js (into [nbb-cli kotoba "-M"] args))
                           #js {:cwd lib/root :encoding "utf8" :maxBuffer 1048576})]
    (when (.-error result) (throw (.-error result)))
    (when-not (or allow-failure? (zero? (or (.-status result) 70)))
      (throw (js/Error. (str "release-conformance: command failed: " (.-stderr result)))))
    result))
(defn k [& args] (run-k! args false))
(defn k-fail [& args]
  (let [result (run-k! args true)]
    (lib/ensure! (not= 0 (or (.-status result) 70))
                 "release-conformance: negative vector succeeded")
    result))

(try
  (k "compile" (.join path lib/root "examples" "structured.kotoba")
     "--target" "wasm32-wasi" "--output" (file "service.wasm"))
  (k "keygen" "--output" (file "key.edn"))
  (k "public-key" (file "key.edn") "--output" (file "public.edn"))
  (k "trust-key" (file "public.edn") "--output" (file "trust.edn"))
  (k "sbom" (file "service.wasm") "--output" (file "service.spdx"))
  (k "attest-release" (file "service.wasm") "--sbom" (file "service.spdx")
     "--target" "wasm32-wasi" "--key" (file "key.edn")
     "--not-before" "1000" "--expires" "2000" "--output" (file "release.edn"))
  (k "verify-release" (file "release.edn") "--artifact" (file "service.wasm")
     "--sbom" (file "service.spdx") "--trust" (file "trust.edn") "--now" "1500")
  (let [spdx (.readFileSync fs (file "service.spdx") "utf8")]
    (doseq [needle ["SPDXVersion: SPDX-2.3" "DataLicense: CC0-1.0"
                    "FileChecksum: SHA256:" "Created: 1970-01-01T00:00:00Z"]]
      (lib/ensure! (.includes spdx needle) (str "release-conformance: SBOM missing " needle))))
  (k-fail "verify-release" (file "release.edn") "--artifact" (file "service.wasm")
          "--sbom" (file "service.spdx") "--trust" (file "trust.edn") "--now" "2000")
  (.appendFileSync fs (file "service.wasm") "x")
  (k-fail "verify-release" (file "release.edn") "--artifact" (file "service.wasm")
          "--sbom" (file "service.spdx") "--trust" (file "trust.edn") "--now" "1500")
  (println "release: deterministic SPDX, signed provenance, trust, expiry, and mutation passed")
  (finally (.rmSync fs tmp #js {:recursive true :force true})))
