#!/usr/bin/env nbb
(ns check-browser-evidence
  (:require [clojure.set :as set]
            [scripts.lib :as lib]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def evidence-file (path/join lib/root "test-results" "browser-evidence.json"))
(lib/ensure! (.existsSync fs evidence-file) "browser evidence: receipt is missing")
(def evidence (js->clj (js/JSON.parse (lib/read-text evidence-file)) :keywordize-keys true))
(def expected-keys #{:format :status :commit :ciRunId :platform :securityProperties :projects})
(def base-projects #{"chromium-desktop" "firefox-desktop" "webkit-desktop"
                     "chromium-mobile-emulation" "webkit-mobile-emulation"})
(def platform (.-platform js/process))
(def platform-name (if (= "win32" platform) "windows" platform))
(def branded-projects #{(str "chrome-stable-" platform-name) (str "edge-stable-" platform-name)})
;; Beta-channel projects are the same branded Chrome/Edge builds pinned to the beta
;; release channel instead of stable. This is a forward-looking pre-stable signal, not a
;; backward previous-version pin (Playwright's `channel` option has no historical-version
;; mechanism) -- see playwright.config.mjs for the full rationale.
(def beta-projects #{(str "chrome-beta-" platform-name) (str "edge-beta-" platform-name)})
(def expected-projects
  (if (= "1" js/process.env.KOTOBA_SAFARI_ONLY)
    #{"safari-stable-macos"}
    (cond-> base-projects
      (= "1" js/process.env.KOTOBA_BRANDED_BROWSERS) (set/union branded-projects)
      (= "1" js/process.env.KOTOBA_BETA_BROWSERS) (set/union beta-projects))))

(lib/ensure! (= expected-keys (set (keys evidence))) "browser evidence: unknown or missing receipt field")
(lib/ensure! (= "kotoba.browser-engine-evidence/v2" (:format evidence)) "browser evidence: format mismatch")
(lib/ensure! (= "passed" (:status evidence)) "browser evidence: suite did not pass")
(lib/ensure! (= platform (:platform evidence)) "browser evidence: platform mismatch")
(lib/ensure! (= #{:cspWasmEnforced} (set (keys (:securityProperties evidence))))
             "browser evidence: security property schema mismatch")
(lib/ensure! (= (not= "1" js/process.env.KOTOBA_SAFARI_ONLY)
                (get-in evidence [:securityProperties :cspWasmEnforced]))
             "browser evidence: CSP Wasm enforcement observation mismatch")
(when js/process.env.CI
  (lib/ensure! (= js/process.env.GITHUB_SHA (:commit evidence)) "browser evidence: commit mismatch")
  (lib/ensure! (= js/process.env.GITHUB_RUN_ID (:ciRunId evidence)) "browser evidence: CI run mismatch"))
(lib/ensure! (= expected-projects (set (map :project (:projects evidence))))
             "browser evidence: project coverage mismatch")
(doseq [identity (:projects evidence)]
  (lib/ensure! (= #{:project :browserName :version :evidenceKind} (set (keys identity)))
               "browser evidence: identity schema mismatch")
  (lib/ensure! (boolean (re-matches #"[0-9]+(?:\.[0-9]+)+" (:version identity)))
               "browser evidence: invalid browser version")
  (lib/ensure! (contains? #{"engine" "mobile-emulation" "branded-browser" "branded-browser-beta"} (:evidenceKind identity))
               "browser evidence: invalid evidence kind"))
(println (str "browser-evidence: verified " (count (:projects evidence))
              " versioned project identities for " (:commit evidence)))
