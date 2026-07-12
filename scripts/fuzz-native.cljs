#!/usr/bin/env nbb
(ns kotoba-native-fuzz
  (:require [clojure.string :as str]
            [scripts.lib :as lib]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def tmp (lib/temp-dir "kotoba-native-fuzz-"))
(def corpus (lib/join tmp "corpus"))
(def source-corpus (lib/join lib/root "fuzz" "corpus" "parser"))
(def output-dir (aget js/process.env "KOTOBA_FUZZ_ARTIFACT_DIR"))
(def import-dir (aget js/process.env "KOTOBA_FUZZ_IMPORT_DIR"))
(def runs (or (aget js/process.env "KOTOBA_NATIVE_FUZZ_RUNS") "20000"))
(def seed (or (aget js/process.env "KOTOBA_NATIVE_FUZZ_SEED") "424242"))
(defn exit! [message]
  (.error js/console message)
  (.exit js/process 2))
(defn entries [directory]
  (if (and directory (.existsSync fs directory))
    (map #(lib/join directory %) (.readdirSync fs directory)) []))

(defn import-corpus! []
  (when import-dir
    (loop [remaining (entries import-dir) count 0 total 0]
      (when-let [candidate (first remaining)]
        (let [stat (.lstatSync fs candidate)
              name (.basename path candidate)
              committed (lib/join source-corpus name)]
          (when-not (and (.isFile stat) (not (.isSymbolicLink stat)))
            (exit! "native-fuzz: imported corpus contains a non-regular file"))
          (let [legacy? (not (boolean (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}" name)))]
            (if legacy?
              (do
                (when-not (and (.existsSync fs committed)
                               (.equals (.readFileSync fs candidate) (.readFileSync fs committed)))
                  (exit! (str "native-fuzz: unsafe corpus name: " name)))
                (recur (next remaining) count total))
              (let [size (.-size stat)
                    next-count (inc count)
                    next-total (+ total size)]
                (when (> size 1024) (exit! "native-fuzz: corpus input exceeds 1024 bytes"))
                (when (or (> next-count 10000) (> next-total 1048576))
                  (exit! "native-fuzz: imported corpus exceeds review limits"))
                (.copyFileSync fs candidate (lib/join corpus name))
                (recur (next remaining) next-count next-total)))))))))

(defn number-field [text pattern label]
  (let [[_ value] (re-find pattern text)]
    (when-not value (throw (js/Error. (str "native-fuzz: missing " label))))
    (js/parseInt value 10)))

(defn linux-fuzz! [binary artifact-prefix]
  (lib/run "clang" ["-std=c11" "-O1" "-g" "-Wall" "-Wextra"
                    "-fsanitize=fuzzer,address,undefined" "-fno-omit-frame-pointer"
                    (str "-I" (lib/join lib/root "tools"))
                    (lib/join lib/root "tools" "kexe_parser_fuzz.c") "-o" binary])
  (let [seconds (aget js/process.env "KOTOBA_NATIVE_FUZZ_SECONDS")
        limit (if seconds (str "-max_total_time=" seconds) (str "-runs=" runs))
        label (if seconds (str seconds "s") runs)
        result (lib/run binary [corpus limit "-max_len=1024" "-timeout=2"
                                (str "-seed=" seed) (str "-artifact_prefix=" artifact-prefix)
                                "-print_final_stats=1" "-verbosity=1"]
                        {:env {:ASAN_OPTIONS "detect_leaks=0:abort_on_error=1"
                               :UBSAN_OPTIONS "halt_on_error=1:print_stacktrace=1"}
                         :allow-failure? true :max-buffer (* 16 1024 1024)})
        log (str (:stdout result) (:stderr result))]
    (when-not (= 0 (:status result)) (throw (js/Error. log)))
    (let [done (last (filter #(.includes % "cov:") (str/split-lines log)))
          cov (number-field done #"cov: ([0-9]+)" "coverage")
          features (number-field done #"ft: ([0-9]+)" "features")
          corpus-count (number-field done #"corp: ([0-9]+)/" "corpus")
          baseline (lib/read-text (lib/join lib/root "fuzz" "baselines" "native-parser.edn"))
          min-cov (number-field baseline #":min-cov ([0-9]+)" "minimum coverage")
          min-features (number-field baseline #":min-features ([0-9]+)" "minimum features")
          min-corpus (number-field baseline #":min-corpus ([0-9]+)" "minimum corpus")
          [_ expected] (re-find #":loader-source-sha256 \"([0-9a-f]{64})\"" baseline)
          actual (lib/sha256 (lib/join lib/root "tools" "kexe_loader.c"))]
      (lib/ensure! (= expected actual) "native-fuzz: coverage baseline does not match loader source")
      (lib/ensure! (and (>= cov min-cov) (>= features min-features)
                        (>= corpus-count min-corpus))
                   (str "native-fuzz: coverage regression: cov=" cov "/" min-cov
                        " features=" features "/" min-features
                        " corpus=" corpus-count "/" min-corpus))
      [(str "{:format :kotoba.fuzz-coverage/v1 :engine :libfuzzer :seed " seed
            " :cov " cov " :features " features " :corpus " corpus-count
            " :limit \"" label "\"}") label "coverage-guided"])))

(defn macos-fuzz! [binary]
  (lib/run "clang" ["-std=c11" "-O1" "-g" "-Wall" "-Wextra" "-DKEXE_STANDALONE_FUZZ"
                    "-fsanitize=address,undefined" "-fno-omit-frame-pointer"
                    (str "-I" (lib/join lib/root "tools"))
                    (lib/join lib/root "tools" "kexe_parser_fuzz.c") "-o" binary])
  (lib/run binary (into [runs] (entries corpus))
           {:env {:ASAN_OPTIONS "detect_leaks=0:abort_on_error=1"
                  :UBSAN_OPTIONS "halt_on_error=1:print_stacktrace=1"}
            :max-buffer (* 16 1024 1024)})
  [(str "{:format :kotoba.fuzz-coverage/v1 :engine :deterministic-sanitized :cases " runs "}")
   runs "deterministic-sanitized"])

(try
  (.mkdirSync fs corpus #js {:recursive true})
  (doseq [candidate (entries source-corpus)]
    (.copyFileSync fs candidate (lib/join corpus (.basename path candidate))))
  (import-corpus!)
  (when output-dir (.mkdirSync fs output-dir #js {:recursive true}))
  (let [binary (lib/join tmp "kexe-parser-fuzz")
        prefix (if output-dir (lib/join output-dir "crash-") (lib/join tmp "crash-"))
        [summary label mode] (if (= "linux" (.platform os))
                               (linux-fuzz! binary prefix) (macos-fuzz! binary))]
    (println summary)
    (when output-dir (lib/write-text! (lib/join output-dir "coverage.edn") (str summary "\n")))
    (println (str "native-fuzz: " label " " mode " parser fuzz passed")))
  (finally
    (when output-dir
      (.mkdirSync fs (lib/join output-dir "corpus") #js {:recursive true})
      (.cpSync fs corpus (lib/join output-dir "corpus") #js {:recursive true :force true}))
    (lib/remove-tree! tmp)))
