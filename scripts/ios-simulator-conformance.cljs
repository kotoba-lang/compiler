#!/usr/bin/env nbb
(ns ios-simulator-conformance
  "Real EXECUTION of compiled `.kotoba` iOS-target code, inside the iOS
  Simulator (`xcrun simctl spawn`) -- something `ios-aot-conformance.cljs`
  (device-targeted, `--sdk iphoneos`) has never done: that script only
  checks static Mach-O/symbol/manifest shape, since GH Actions' macOS
  runners have no attached iPhone to run anything on. The iOS Simulator
  needs neither a physical device nor an Apple Developer Program /
  code-signing certificate (Simulator binaries run unsigned) -- it's the
  free, CI-reachable execution path this repo's own ADR-0001 Phase 3 gap
  list left unexplored (that list only distinguishes 'app integration /
  codesigning / store packaging / physical iPhone execution', not which of
  those specifically needs paid/physical resources -- Simulator execution
  needs none of them).

  On an Apple Silicon host (arm64), the Simulator runs arm64 code
  NATIVELY (no Rosetta) -- `kotoba_ios_execute_static_v1`'s own
  `#if defined(__APPLE__) && defined(__aarch64__)` guard (kotoba_ios_host.c)
  takes the real execution path here, not the KOTOBA_IOS_UNSUPPORTED_HOST
  stub, so this genuinely proves the compiled code runs correctly, not just
  that it links.

  Deliberately no .app bundle / Info.plist / XCTest target: `simctl spawn`
  runs a plain Mach-O executable built against the simulator SDK directly,
  which is sufficient to prove real execution and needs no code signing at
  all -- building a full app bundle would add real complexity (bundle
  structure, entitlements, install step) for no additional verification
  value over what a spawned command-line binary already gives.

  Run from this repo's root: `nbb scripts/ios-simulator-conformance.cljs`"
  (:require [scripts.lib :as lib]
            [clojure.string :as str]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

;; Same pinned-Xcode discipline `ios-aot-conformance.cljs` already applies
;; to the device build -- kept as its own local constant (not shared/
;; refactored into scripts/lib.cljs) since that script already duplicates
;; this pattern locally rather than centralizing it, and the two scripts'
;; version requirements are independent even though numerically identical
;; today.
(def expected-xcode "Xcode 16.2\nBuild version 16C5032a")

(defn run! [command args]
  (let [result (.spawnSync child command (clj->js args)
                           #js {:cwd lib/root :encoding "utf8" :maxBuffer 4194304})]
    (when (.-error result) (throw (.-error result)))
    (lib/ensure! (zero? (or (.-status result) 70))
                 (str "ios-simulator: command failed: " command " " (str/join " " args)
                      "\n" (.-stdout result) (.-stderr result)))
    result))

(defn- simulator-devices []
  (let [data (js/JSON.parse (.-stdout (run! "xcrun" ["simctl" "list" "devices" "available" "-j"])))
        by-runtime (js->clj (.-devices data) :keywordize-keys false)]
    (->> by-runtime
         (filter (fn [[runtime _]] (str/includes? runtime "SimRuntime.iOS")))
         (mapcat (fn [[_ devices]] devices))
         (map (fn [d] {:name (get d "name") :udid (get d "udid") :state (get d "state")}))
         (sort-by :name))))

(defn- find-or-boot-device!
  "Prefers an ALREADY-booted iOS simulator (never force-restarts a device
  some other process -- a concurrent local session, an IDE -- might be
  using); otherwise boots the first available iOS device deterministically
  (sorted by name) and waits for it to report Booted. Returns
  {:udid :booted-by-us?} -- the caller only shuts down a device it booted
  itself, never one that was already running before this script touched
  anything."
  []
  (let [devices (simulator-devices)
        _ (lib/ensure! (seq devices) "ios-simulator: no available iOS simulator devices found")
        already-booted (first (filter #(= "Booted" (:state %)) devices))]
    (if already-booted
      {:udid (:udid already-booted) :booted-by-us? false}
      (let [target (first devices)]
        (run! "xcrun" ["simctl" "boot" (:udid target)])
        (run! "xcrun" ["simctl" "bootstatus" (:udid target)])
        {:udid (:udid target) :booted-by-us? true}))))

(defn build! [directory source policy]
  (.mkdirSync fs directory #js {:recursive true})
  (let [nbb-cli (.join path lib/root "node_modules" "nbb" "cli.js")
        kotoba (.join path lib/root "bin" "kotoba")
        kexe (.join path directory "program.kexe")
        assembly (.join path directory "program.S")
        manifest (.join path directory "program.edn")
        program-object (.join path directory "program.o")
        host-object (.join path directory "host.o")
        harness (.join path directory "kotoba-ios-simulator-harness")
        sim-flags ["--sdk" "iphonesimulator" "clang" "-arch" "arm64"
                   "-mios-simulator-version-min=15.0"]]
    (run! js/process.execPath
          (cond-> [nbb-cli kotoba "-M" "compile" source
                   "--target" "aarch64-ios" "--output" kexe]
            policy (into ["--policy" policy])))
    (run! js/process.execPath [nbb-cli kotoba "-M" "package-ios" kexe
                               "--entry" "main" "--output" assembly
                               "--manifest-output" manifest])
    (run! "xcrun" (into sim-flags ["-c" assembly "-o" program-object]))
    (run! "xcrun" (into sim-flags ["-std=c11" "-O2" "-Wall" "-Wextra" "-Werror"
                                   "-fvisibility=hidden"
                                   (str "-I" (.join path lib/root "runtime" "ios"))
                                   "-c" (.join path lib/root "runtime" "ios" "kotoba_ios_host.c")
                                   "-o" host-object]))
    (run! "xcrun" (into sim-flags ["-std=c11" "-O2" "-Wall" "-Wextra" "-Werror"
                                   (str "-I" (.join path lib/root "runtime" "ios"))
                                   (.join path lib/root "runtime" "ios" "kotoba_ios_harness.c")
                                   program-object host-object "-o" harness]))
    {:harness harness :manifest manifest}))

(lib/ensure! (= "darwin" (.-platform js/process)) "ios-simulator: macOS host is required")
(let [identity (str/trim (.-stdout (run! "xcodebuild" ["-version"])))
      _ (lib/ensure! (= expected-xcode identity) "ios-simulator: pinned Xcode 16.2 is required")
      tmp (lib/temp-dir "kotoba-ios-simulator-")
      typed-source (.join path tmp "typed-capability.kotoba")
      typed-policy (.join path tmp "typed-policy.edn")]
  (try
    (.writeFileSync
     fs typed-source
     "(defn main [] :i64\n  (+ (string-byte-length (typed-cap-call 4 :string :string \"hello😀\"))\n     (option-value (typed-cap-call 4 :option-i64 :option-i64 (some 41)) 0)\n     (option-value (typed-cap-call 4 :option-i64 :option-i64 (option-none)) 5)\n     (result-value (typed-cap-call 4 :result-i64 :result-i64 (result-ok 7)) 0)\n     (result-error (typed-cap-call 4 :result-i64 :result-i64 (result-err 9)) 0)))\n")
    (.writeFileSync fs typed-policy "{:allow #{[:cap/call 4]}}\n")
    (let [{:keys [harness manifest]}
          (build! (.join path tmp "structured")
                  (.join path lib/root "examples" "structured.kotoba") nil)
          typed-build (build! (.join path tmp "typed") typed-source typed-policy)
          object-info (.-stdout (run! "file" [harness]))
          build-info (.-stdout (run! "xcrun" ["vtool" "-show-build" harness]))]
      (lib/ensure! (.includes object-info "Mach-O 64-bit executable arm64")
                   "ios-simulator: harness is not an arm64 Mach-O executable")
      (lib/ensure! (str/includes? build-info "platform IOSSIMULATOR")
                   "ios-simulator: harness was not linked against the Simulator platform")
      (doseq [needle [":format :kotoba.ios-aot/v1" ":target :aarch64-ios-kotoba-v1"]]
        (lib/ensure! (str/includes? (lib/read-text manifest) needle)
                     (str "ios-simulator: manifest missing " needle)))
      (let [{:keys [udid booted-by-us?]} (find-or-boot-device!)]
        (try
          (let [spawned (run! "xcrun" ["simctl" "spawn" udid harness])
                out (str/trim (.-stdout spawned))
                typed-spawned
                (run! "xcrun" ["simctl" "spawn" udid (:harness typed-build) "typed"])
                typed-out (str/trim (.-stdout typed-spawned))]
            (lib/ensure! (str/includes? out ":status :ok")
                         (str "ios-simulator: harness did not report :status :ok: " out))
            (lib/ensure! (str/includes? out ":result 42")
                         (str "ios-simulator: structured.kotoba's main() must equal 42, got: " out))
            (lib/ensure! (str/includes? typed-out ":result 71")
                         (str "ios-simulator: typed callback mismatch: " typed-out))
            (println (str "ios-simulator: real Simulator execution -- " out)))
          (finally
            (when booted-by-us? (run! "xcrun" ["simctl" "shutdown" udid]))))))
    (println "ios-simulator: verified real arm64 Simulator execution of compiled .kotoba code")
    (finally (lib/remove-tree! tmp))))
