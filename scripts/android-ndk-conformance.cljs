#!/usr/bin/env nbb
(ns android-ndk-conformance
  (:require [scripts.lib :as lib]
            [clojure.string :as str]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ndk-version "27.3.13750724")
(defn run! [command args]
  (let [result (.spawnSync child command (clj->js args)
                           #js {:cwd lib/root :encoding "utf8" :maxBuffer 4194304})]
    (when (.-error result) (throw (.-error result)))
    (lib/ensure! (zero? (or (.-status result) 70))
                 (str "android-ndk: command failed: " command "\n" (.-stderr result)))
    result))

(def sdk-root (or (aget js/process.env "ANDROID_SDK_ROOT")
                  (aget js/process.env "ANDROID_HOME")))
(def sdk-ndk (when sdk-root (.join path sdk-root "ndk" ndk-version)))
(when (and sdk-ndk (not (.existsSync fs sdk-ndk))
           (= "1" (aget js/process.env "KOTOBA_ANDROID_INSTALL_NDK")))
  (let [sdkmanager (if (= "win32" (.-platform js/process))
                     (.join path sdk-root "cmdline-tools" "latest" "bin" "sdkmanager.bat")
                     (.join path sdk-root "cmdline-tools" "latest" "bin" "sdkmanager"))]
    (run! sdkmanager [(str "ndk;" ndk-version)])))

(let [ndk (or (when (and sdk-ndk (.existsSync fs sdk-ndk)) sdk-ndk)
              (aget js/process.env "ANDROID_NDK_HOME")
              (aget js/process.env "ANDROID_NDK_ROOT"))
      _ (lib/ensure! (and ndk (= ndk-version (.basename path ndk)))
                     "android-ndk: pinned NDK 27.3.13750724 is required")
      prebuilt-root (.join path ndk "toolchains" "llvm" "prebuilt")
      prebuilts (->> (.readdirSync fs prebuilt-root) (map #(str %)) sort vec)
      _ (lib/ensure! (= 1 (count prebuilts))
                     (str "android-ndk: ambiguous host toolchain " prebuilts))
      bin (.join path prebuilt-root (first prebuilts) "bin")
      clang (.join path bin "aarch64-linux-android26-clang")
      readelf (.join path bin "llvm-readelf")
      nm (.join path bin "llvm-nm")
      tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-android-ndk-"))
      first-lib (.join path tmp "libkotoba-host-first.so")
      second-lib (.join path tmp "libkotoba-host-second.so")
      harness (.join path tmp "kotoba-android-harness")
      source (.join path lib/root "runtime" "android" "kotoba_android_host.c")
      harness-source (.join path lib/root "runtime" "android" "kotoba_android_harness.c")
      include (.join path lib/root "runtime" "android")
      build! (fn [output]
               (run! clang ["--target=aarch64-linux-android26" "-std=c11" "-O2"
                            "-Wall" "-Wextra" "-Werror" "-fPIC" "-fvisibility=hidden"
                            "-ffile-prefix-map=.=." "-fdebug-prefix-map=.=."
                            "-shared" (str "-I" include) source "-o" output
                            "-Wl,-z,relro,-z,now,-z,noexecstack,--build-id=none"]))]
  (try
    (build! first-lib)
    (build! second-lib)
    (run! clang ["--target=aarch64-linux-android26" "-std=c11" "-O2"
                 "-Wall" "-Wextra" "-Werror" "-fPIE" (str "-I" include)
                 source harness-source "-o" harness "-pie"
                 "-Wl,-z,relro,-z,now,-z,noexecstack,--build-id=none"])
    (lib/ensure! (= (lib/sha256 first-lib) (lib/sha256 second-lib))
                 "android-ndk: host library build is not reproducible")
    (let [header (.-stdout (run! readelf ["-h" first-lib]))
          program (.-stdout (run! readelf ["-lW" first-lib]))
          dynamic (.-stdout (run! readelf ["-dW" first-lib]))
          exports (->> (str/split-lines (.-stdout (run! nm ["-D" "--defined-only" first-lib])))
                       (remove str/blank?) vec)]
      (lib/ensure! (.includes header "AArch64") "android-ndk: ELF machine is not AArch64")
      (lib/ensure! (and (.includes program "GNU_STACK")
                        (not (re-find #"GNU_STACK[^\n]*RWE" program)))
                   "android-ndk: executable stack was admitted")
      (lib/ensure! (.includes program "GNU_RELRO") "android-ndk: RELRO is absent")
      (lib/ensure! (.includes dynamic "BIND_NOW") "android-ndk: immediate binding is absent")
      (lib/ensure! (and (= 1 (count exports))
                        (str/ends-with? (first exports) " kotoba_android_execute_verified_v1"))
                   (str "android-ndk: export surface rejected: " exports)))
    (let [nbb-cli (.join path lib/root "node_modules" "nbb" "cli.js")
          kotoba (.join path lib/root "bin" "kotoba")
          artifact (.join path tmp "android.kexe")
          raw (.join path tmp "android.bin")]
      (run! js/process.execPath [nbb-cli kotoba "-M" "compile"
                                 (.join path lib/root "examples" "structured.kotoba")
                                 "--target" "aarch64-android" "--output" artifact])
      (run! js/process.execPath [nbb-cli kotoba "-M" "verify" artifact])
      (let [extracted (run! js/process.execPath [nbb-cli kotoba "-M" "extract-native"
                                                 artifact "--symbol" "main" "--output" raw])
            [_ offset] (re-find #":offset ([0-9]+)" (.-stdout extracted))
            [_ arity] (re-find #":arity ([0-9]+)" (.-stdout extracted))]
        (lib/ensure! (and offset arity) "android-ndk: missing entry metadata")
        (when (= "1" (aget js/process.env "KOTOBA_ANDROID_EXECUTE"))
          (run! "adb" ["push" harness "/data/local/tmp/kotoba-android-harness"])
          (run! "adb" ["push" raw "/data/local/tmp/kotoba-android.bin"])
          (run! "adb" ["shell" "chmod" "700" "/data/local/tmp/kotoba-android-harness"])
          (let [executed (run! "adb" ["shell" "/data/local/tmp/kotoba-android-harness"
                                       "/data/local/tmp/kotoba-android.bin" offset arity])]
            (lib/ensure! (= "{:status :ok :result 42 :fuel {:initial 256 :remaining 253} :heap {:used 0}}"
                              (.trim (.-stdout executed)))
                         (str "android-ndk: emulator result mismatch: " (.-stdout executed)))
            (println "android-ndk: Arm64 emulator executed verified code under RW-to-RX host")))
      (let [text (.readFileSync fs artifact "utf8")]
        (doseq [needle [":target :aarch64-android-kotoba-v1" ":os :android"
                        ":runtime :kotoba-android-isolated-host-v1"]]
          (lib/ensure! (.includes text needle) (str "android-ndk: artifact missing " needle))))))
    (println "android-ndk: reproducible AArch64 host, hardened ELF, and sealed target passed")
    (finally (.rmSync fs tmp #js {:recursive true :force true}))))
