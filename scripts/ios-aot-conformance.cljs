#!/usr/bin/env nbb
(ns ios-aot-conformance
  (:require [scripts.lib :as lib]
            [clojure.string :as str]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def expected-xcode "Xcode 16.2\nBuild version 16C5032a")
(defn run! [command args]
  (let [result (.spawnSync child command (clj->js args)
                           #js {:cwd lib/root :encoding "utf8" :maxBuffer 4194304})]
    (when (.-error result) (throw (.-error result)))
    (lib/ensure! (zero? (or (.-status result) 70))
                 (str "ios-aot: command failed: " command "\n" (.-stderr result)))
    result))

(defn build! [directory]
  (.mkdirSync fs directory #js {:recursive true})
  (let [nbb-cli (.join path lib/root "node_modules" "nbb" "cli.js")
        kotoba (.join path lib/root "bin" "kotoba")
        kexe (.join path directory "program.kexe")
        assembly (.join path directory "program.S")
        manifest (.join path directory "program.edn")
        program-object (.join path directory "program.o")
        host-object (.join path directory "host.o")
        archive (.join path directory "libkotoba-ios.a")]
    (run! js/process.execPath [nbb-cli kotoba "-M" "compile"
                               (.join path lib/root "examples" "structured.kotoba")
                               "--target" "aarch64-ios" "--output" kexe])
    (run! js/process.execPath [nbb-cli kotoba "-M" "package-ios" kexe
                               "--entry" "main" "--output" assembly
                               "--manifest-output" manifest])
    (run! "xcrun" ["--sdk" "iphoneos" "clang" "-arch" "arm64"
                    "-miphoneos-version-min=15.0" "-c" assembly "-o" program-object])
    (run! "xcrun" ["--sdk" "iphoneos" "clang" "-arch" "arm64"
                    "-miphoneos-version-min=15.0" "-std=c11" "-O2"
                    "-Wall" "-Wextra" "-Werror" "-fvisibility=hidden"
                    (str "-I" (.join path lib/root "runtime" "ios"))
                    "-c" (.join path lib/root "runtime" "ios" "kotoba_ios_host.c")
                    "-o" host-object])
    (run! "xcrun" ["libtool" "-static" "-D" "-o" archive program-object host-object])
    {:assembly assembly :manifest manifest :object program-object :archive archive}))

(lib/ensure! (= "darwin" (.-platform js/process)) "ios-aot: macOS host is required")
(let [identity (str/trim (.-stdout (run! "xcodebuild" ["-version"])))
      _ (lib/ensure! (= expected-xcode identity) "ios-aot: pinned Xcode 16.2 is required")
      tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-ios-aot-"))]
  (try
    (let [first-build (build! (.join path tmp "first"))
          second-build (build! (.join path tmp "second"))]
      (doseq [key [:assembly :manifest :object :archive]]
        (lib/ensure! (= (lib/sha256 (get first-build key))
                        (lib/sha256 (get second-build key)))
                     (str "ios-aot: " (name key) " build is not reproducible")))
      (let [object-info (.-stdout (run! "file" [(:object first-build)]))
            load-info (.-stdout (run! "xcrun" ["otool" "-l" (:object first-build)]))
            symbols (.-stdout (run! "xcrun" ["nm" "-gU" (:archive first-build)]))
            manifest (.readFileSync fs (:manifest first-build) "utf8")]
        (lib/ensure! (and (.includes object-info "Mach-O 64-bit object arm64")
                          (.includes load-info "sectname __text")
                          (.includes load-info "flags 0x80000000"))
                     "ios-aot: executable Mach-O text section rejected")
        (doseq [symbol ["_kotoba_ios_code_start" "_kotoba_ios_entry"
                        "_kotoba_ios_target_profile" "_kotoba_ios_execute_static_v1"]]
          (lib/ensure! (.includes symbols symbol) (str "ios-aot: missing symbol " symbol)))
        (doseq [needle [":format :kotoba.ios-aot/v1" ":target :aarch64-ios-kotoba-v1"
                        ":artifact-sha256" ":code-sha256" ":assembly-sha256"]]
          (lib/ensure! (.includes manifest needle) (str "ios-aot: manifest missing " needle)))))
    (println "ios-aot: verified KEXE, reproducible Mach-O text, and static host archive passed")
    (finally (.rmSync fs tmp #js {:recursive true :force true}))))
