#!/usr/bin/env nbb
(ns check-workflows
  (:require [clojure.string :as str]
            [scripts.lib :as lib]
            ["node:fs" :as fs]))

(def workflow-dir (lib/join lib/root ".github" "workflows"))
(def workflows
  (map #(lib/join workflow-dir %)
       (filter #(or (.endsWith % ".yml") (.endsWith % ".yaml"))
               (.readdirSync fs workflow-dir))))

(lib/ensure! (seq workflows) "workflow-lint: no workflows found")
(def action-count (volatile! 0))
(doseq [workflow workflows
        :let [text (lib/read-text workflow)]
        line (str/split-lines text)]
  (when-let [[_ action reference] (re-find #"^\s*-?\s*uses:\s*([^\s@]+)@([^\s#]+)" line)]
    (vswap! action-count inc)
    (lib/ensure! (boolean (re-matches #"[0-9a-f]{40}" reference))
                 (str "workflow-lint: action is not commit-pinned: " action "@" reference))))
(lib/ensure! (>= @action-count 6)
             "workflow-lint: expected action references were not inspected")

(let [repository-files
      (letfn [(walk [directory]
                (mapcat (fn [name]
                          (let [entry (lib/join directory name)
                                stat (.lstatSync fs entry)]
                            (cond
                              (.isSymbolicLink stat) []
                              (.isDirectory stat) (if (= name "node_modules") [] (walk entry))
                              :else [entry])))
                        (.readdirSync fs directory)))]
        (walk lib/root))
      shell-files (filter #(.endsWith % ".sh") repository-files)]
  (lib/ensure! (empty? shell-files)
               (str "workflow-lint: POSIX shell execution files found: " shell-files)))

(doseq [workflow workflows]
  (lib/ensure! (.includes (lib/read-text workflow) "node-version: \"24.12.0\"")
               (str "workflow-lint: Node runtime is not exactly pinned in " workflow)))
(let [test-workflow (lib/read-text (lib/join workflow-dir "test.yml"))]
  (lib/ensure! (.includes test-workflow "cli: 1.12.5.1654")
               "workflow-lint: Clojure CLI is not exactly pinned"))

(println (str "workflow-lint: " (count workflows) " workflows and " @action-count
              " action references use commit pins and pinned toolchains"))
