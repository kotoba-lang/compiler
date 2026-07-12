#!/usr/bin/env nbb
(ns check-browser-evidence
  (:require [clojure.set :as set]
            [scripts.lib :as lib]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def evidence-file (path/join lib/root "test-results" "browser-evidence.json"))
(lib/ensure! (.existsSync fs evidence-file) "browser evidence: receipt is missing")
(def evidence (js->clj (js/JSON.parse (lib/read-text evidence-file)) :keywordize-keys true))
(def expected-keys #{:format :status :commit :ciRunId :projects})
(def base-projects #{"chromium-desktop" "firefox-desktop" "webkit-desktop"
                     "chromium-mobile-emulation" "webkit-mobile-emulation"})
(def branded-projects #{"chrome-stable-linux" "edge-stable-linux"})
(def expected-projects
  (if (= "1" js/process.env.KOTOBA_BRANDED_BROWSERS)
    (set/union base-projects branded-projects) base-projects))

(lib/ensure! (= expected-keys (set (keys evidence))) "browser evidence: unknown or missing receipt field")
(lib/ensure! (= "kotoba.browser-engine-evidence/v1" (:format evidence)) "browser evidence: format mismatch")
(lib/ensure! (= "passed" (:status evidence)) "browser evidence: suite did not pass")
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
  (lib/ensure! (contains? #{"engine" "mobile-emulation" "branded-browser"} (:evidenceKind identity))
               "browser evidence: invalid evidence kind"))
(println (str "browser-evidence: verified " (count (:projects evidence))
              " versioned project identities for " (:commit evidence)))
