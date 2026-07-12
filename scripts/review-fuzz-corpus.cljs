#!/usr/bin/env nbb
(ns review-fuzz-corpus
  (:require [scripts.lib :as lib]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def destination (lib/join lib/root "fuzz" "corpus" "parser"))
(defn exit! [message]
  (.error js/console message)
  (.exit js/process 2))
(defn regular-files [directory]
  (->> (.readdirSync fs directory)
       (map #(lib/join directory %))))
(defn same-bytes? [left right]
  (.equals (.readFileSync fs left) (.readFileSync fs right)))
(defn destination-hashes []
  (set (map lib/sha256 (filter #(.isFile (.lstatSync fs %)) (regular-files destination)))))

(let [[source mode-value] *command-line-args*
      mode (or mode-value "--dry-run")]
  (when-not (and source (.existsSync fs source) (.isDirectory (.statSync fs source)))
    (exit! "usage: review-fuzz-corpus.cljs <artifact-corpus-dir> [--dry-run|--apply]"))
  (when-not (contains? #{"--dry-run" "--apply"} mode) (exit! "invalid review mode"))
  (let [existing (destination-hashes)
        candidates (regular-files source)]
    (when (empty? candidates) (exit! "review-corpus: empty corpus rejected"))
    (loop [remaining candidates file-count 0 total 0 fresh [] duplicate 0]
      (if-let [candidate (first remaining)]
        (let [stat (.lstatSync fs candidate)
              name (.basename path candidate)
              destination-file (lib/join destination name)]
          (when-not (and (.isFile stat) (not (.isSymbolicLink stat)))
            (exit! "review-corpus: non-regular input rejected"))
          (let [legacy? (not (boolean (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}" name)))]
            (when (and legacy?
                       (not (and (.existsSync fs destination-file)
                                 (same-bytes? candidate destination-file))))
              (exit! (str "review-corpus: unsafe input name: " name)))
            (let [size (.-size stat)
                  next-count (inc file-count)
                  next-total (+ total size)]
              (when (> size 1024) (exit! (str "review-corpus: input exceeds 1024 bytes: " name)))
              (when (or (> next-count 10000) (> next-total 1048576))
                (exit! "review-corpus: artifact exceeds file or byte limit"))
              (let [digest (lib/sha256 candidate)
                    duplicate? (or legacy? (contains? existing digest))]
                (recur (next remaining) next-count next-total
                       (if duplicate? fresh (conj fresh [candidate digest]))
                       (if duplicate? (inc duplicate) duplicate))))))
        (do
          (println (str "{:mode :" (subs mode 2) " :files " file-count " :bytes " total
                        " :new " (count fresh) " :duplicate " duplicate "}"))
          (when (and (= mode "--apply") (seq fresh))
            (lib/run "npx" (into ["--no-install" "nbb" (lib/join lib/root "scripts" "fuzz-native.cljs")]
                                 [])
                     {:env {:KOTOBA_FUZZ_IMPORT_DIR source
                            :KOTOBA_NATIVE_FUZZ_RUNS "20000"}})
            (doseq [[candidate digest] fresh]
              (.copyFileSync fs candidate (lib/join destination digest)))
            (println (str "review-corpus: promoted " (count fresh) " sanitized inputs"))))))))
