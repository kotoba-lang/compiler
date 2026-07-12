#!/usr/bin/env nbb
(ns kotoba-sanitizer
  (:require [clojure.string :as str]
            [scripts.lib :as lib]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def tmp (lib/temp-dir "kotoba-loader-sanitizer-"))
(def kotoba (lib/join lib/root "bin" "kotoba"))
(def sanitized (lib/join tmp "kexe-loader-sanitized"))
(def env {:ASAN_OPTIONS "detect_leaks=0:abort_on_error=1"
          :UBSAN_OPTIONS "halt_on_error=1:print_stacktrace=1"})

(defn k [& args] (lib/run kotoba (into ["-M"] args)))
(defn offset [text]
  (let [[_ value] (re-find #":offset ([0-9]+)" text)]
    (lib/ensure! value "sanitizer: native export offset missing") value))
(defn reject! [& args]
  (let [result (lib/run sanitized args {:env env :allow-failure? true})]
    (lib/ensure! (not= 0 (:status result))
                 (str "sanitizer: malformed loader input was accepted: " (str/join " " args)))
    (lib/ensure! (not (re-find #"AddressSanitizer|runtime error:|UndefinedBehaviorSanitizer"
                               (:stderr result)))
                 (str "sanitizer detected memory error:\n" (:stderr result)))))

(try
  (let [platform (.platform os)
        machine (.arch os)
        isa (cond
              (and (= platform "linux") (= machine "x64")) "x86_64"
              (and (contains? #{"darwin" "linux"} platform) (= machine "arm64")) "aarch64"
              :else nil)]
    (lib/ensure! isa "sanitizer: unsupported host")
    (lib/run "cc" ["-std=c11" "-O1" "-g" "-Wall" "-Wextra" "-Werror"
                   "-DKEXE_SANITIZER_TEST" "-fsanitize=address,undefined"
                   "-fno-omit-frame-pointer" (lib/join lib/root "tools" "kexe_loader.c")
                   "-o" sanitized])
    (let [source (lib/join tmp "program.kotoba")
          artifact (lib/join tmp "program.kexe")
          binary (lib/join tmp "program.bin")]
      (lib/write-text! source "(defn main [] 42)\n")
      (k "compile" source "--target" isa "--output" artifact)
      (let [off (offset (:stdout (k "extract-native" artifact "--symbol" "main" "--output" binary)))
            valid (lib/run sanitized [binary off "0" isa "-"] {:env env})]
        (lib/ensure! (= "42" (str/trim (:stdout valid))) "sanitizer: valid result mismatch")
        (lib/ensure! (str/blank? (:stderr valid)) "sanitizer: valid execution wrote stderr")
        (apply reject! [])
        (doseq [args [[binary "" "0" isa "-"] [binary "-1" "0" isa "-"]
                      [binary "+1" "0" isa "-"] [binary "18446744073709551616" "0" isa "-"]
                      [binary off "18446744073709551616" isa "-"]
                      [binary off "0" "invalid-isa" "-"] [binary off "0" isa "256"]
                      [binary off "0" isa "7,"]
                      [binary off "1" isa "-" "9223372036854775808"]
                      [binary off "1" isa "-" "-9223372036854775809"]
                      [binary off "1" isa "-" "+1"]]]
          (apply reject! args))
        (println (str "sanitizer: " isa " valid execution and malformed input corpus passed")))))
  (finally (lib/remove-tree! tmp)))
