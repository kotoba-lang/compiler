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

(let [ndk (or (aget js/process.env "ANDROID_NDK_HOME")
              (aget js/process.env "ANDROID_NDK_ROOT"))
      _ (lib/ensure! (and ndk (= ndk-version (.basename path ndk)))
                     "android-ndk: pinned NDK 27.3.13750724 is required")
      bin (.join path ndk "toolchains" "llvm" "prebuilt" "linux-x86_64" "bin")
      clang (.join path bin "aarch64-linux-android26-clang")
      readelf (.join path bin "llvm-readelf")
      nm (.join path bin "llvm-nm")
      tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-android-ndk-"))
      first-lib (.join path tmp "libkotoba-host-first.so")
      second-lib (.join path tmp "libkotoba-host-second.so")
      source (.join path lib/root "runtime" "android" "kotoba_android_host.c")
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
          artifact (.join path tmp "android.kexe")]
      (run! js/process.execPath [nbb-cli kotoba "-M" "compile"
                                 (.join path lib/root "examples" "structured.kotoba")
                                 "--target" "aarch64-android" "--output" artifact])
      (run! js/process.execPath [nbb-cli kotoba "-M" "verify" artifact])
      (let [text (.readFileSync fs artifact "utf8")]
        (doseq [needle [":target :aarch64-android-kotoba-v1" ":os :android"
                        ":runtime :kotoba-android-isolated-host-v1"]]
          (lib/ensure! (.includes text needle) (str "android-ndk: artifact missing " needle)))))
    (println "android-ndk: reproducible AArch64 host, hardened ELF, and sealed target passed")
    (finally (.rmSync fs tmp #js {:recursive true :force true}))))
