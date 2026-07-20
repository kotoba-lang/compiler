(ns kotoba.compiler.schema
  "Closed, bounded nominal schema graphs for structured application values."
  (:require [kotoba.compiler.artifact :as artifact]))

(def max-schemas 32)
(def max-schema-nodes 64)
(def max-schema-depth 8)

(def ^:private primitives
  #{:i64 :f32 :f64 :string :keyword :bool :vector-i64 :vector-f64})
(def ^:private unary-tags #{:option :set})
(def ^:private binary-tags #{:result :map})
(def ^:private productive-tags #{:option :set :result :map :vector :record :variant})

(defn ref-type? [value]
  (and (vector? value) (= 2 (count value)) (= :ref (first value))))

(defn- fail! [message data]
  (throw (ex-info message (assoc data :phase :schema))))

(defn validate-table!
  "Validates and returns a closed schema table. Recursive references are
  admitted only through a productive value constructor."
  [table]
  (when-not (and (map? table) (seq table) (<= (count table) max-schemas)
                 (every? #(and (keyword? %) (namespace %)) (keys table)))
    (fail! "schema table must contain bounded qualified names" {:table table}))
  (letfn [(walk [root node path productive? depth budget]
            (vswap! budget inc)
            (when (> @budget max-schema-nodes)
              (fail! "schema graph exceeds node limit" {:root root}))
            (when (> depth max-schema-depth)
              (fail! "schema graph exceeds depth limit" {:root root}))
            (cond
              (primitives node) nil
              (ref-type? node)
              (let [target (second node)]
                (when-not (contains? table target)
                  (fail! "schema reference is not declared" {:root root :ref target}))
                (if (contains? path target)
                  (when-not productive?
                    (fail! "schema alias cycle is not productive" {:root root :ref target}))
                  (walk root (get table target) (conj path target) false (inc depth) budget)))
              (and (vector? node) (unary-tags (first node)) (= 2 (count node)))
              (walk root (second node) path true (inc depth) budget)
              (and (vector? node) (binary-tags (first node)) (= 3 (count node)))
              (doseq [child (rest node)]
                (walk root child path true (inc depth) budget))
              (and (vector? node) (= :vector (first node)) (= 2 (count node))
                   (vector? (second node)))
              (doseq [child (second node)]
                (walk root child path true (inc depth) budget))
              (and (vector? node) (#{:record :variant} (first node)) (= 3 (count node))
                   (= root (second node)) (vector? (nth node 2)))
              (doseq [[member child] (nth node 2)]
                (when-not (keyword? member)
                  (fail! "schema member name must be a keyword" {:root root :member member}))
                (walk root child path true (inc depth) budget))
              :else (fail! "schema descriptor is outside the closed profile"
                           {:root root :descriptor node})))]
    (doseq [[root descriptor] table]
      (walk root descriptor #{root} false 0 (volatile! 0))))
  table)

(defn identities
  "Returns stable content identities. Each identity binds its nominal root to
  the complete closed table, so changing any reachable definition changes it."
  [table]
  (validate-table! table)
  (into (sorted-map)
        (map (fn [root]
               [root (artifact/sha256 {:root root :schemas table})]))
        (sort-by str (keys table))))
