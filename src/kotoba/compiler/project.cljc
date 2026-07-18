(ns kotoba.compiler.project
  (:require [clojure.string :as str]
            [kotoba.compiler.frontend :as frontend]))

(def max-project-modules 256)
(def max-project-functions 1024)

(defn- reject! [message data]
  (throw (ex-info message (assoc data :phase :project-link))))

(defn- ns-info [forms]
  (let [ns-forms (filter #(and (seq? %) (= 'ns (first %))) forms)]
    (when-not (= 1 (count ns-forms))
      (reject! "project module requires exactly one namespace" {:count (count ns-forms)}))
    (let [[_ name & clauses] (first ns-forms)]
      (when-not (and (simple-symbol? name) (not (str/blank? (str name))))
        (reject! "invalid project namespace" {:namespace name}))
      (loop [remaining clauses exports nil requires []]
        (if-let [clause (first remaining)]
          (cond
            (and (seq? clause) (= :export (first clause)) (= 2 (count clause))
                 (vector? (second clause)) (nil? exports))
            (recur (next remaining) (vec (second clause)) requires)

            (and (seq? clause) (= :require (first clause)))
            (let [parsed
                  (mapv (fn [spec]
                          (when-not (and (vector? spec) (= 3 (count spec))
                                         (simple-symbol? (first spec))
                                         (= :as (second spec))
                                         (simple-symbol? (nth spec 2)))
                            (reject! "imports require [namespace :as alias]"
                                     {:namespace name :spec spec}))
                          {:namespace (first spec) :alias (nth spec 2)})
                        (rest clause))]
              (recur (next remaining) exports (into requires parsed)))

            :else
            (reject! "only one :export and alias-only :require clauses are admitted"
                     {:namespace name :clause clause}))
          (do
            (when-not (some? exports)
              (reject! "project module requires an explicit :export vector" {:namespace name}))
            (when-not (= (count requires) (count (set (map :alias requires))))
              (reject! "duplicate import alias" {:namespace name :requires requires}))
            (when-not (= (count requires) (count (set (map :namespace requires))))
              (reject! "duplicate imported namespace" {:namespace name :requires requires}))
            {:namespace name :exports exports :requires requires}))))))

(defn- without-requires [forms]
  (mapv (fn [form]
          (if (and (seq? form) (= 'ns (first form)))
            (let [[op name & clauses] form]
              (list* op name (remove #(and (seq? %) (= :require (first %))) clauses)))
            form))
        forms))

(defn- interface-of [{:keys [name params param-types result effects]} linked-name]
  {:name name :params params
   :param-types (or param-types (vec (repeat (count params) :i64)))
   :result (or result :i64) :effects effects :linked-name linked-name})

(defn- typed-params [params types]
  (if (every? #{:i64} types) params (vec (mapcat vector params types))))

(defn- stub-form [stub {:keys [params param-types result]}]
  (list 'defn- stub (typed-params params param-types) result
        (if (= :string result) "" 0)))

(defn- rewrite-import-calls [form imported]
  (cond
    (seq? form)
    (let [[op & args] form
          op' (if (and (symbol? op) (namespace op))
                (or (get imported op)
                    (reject! "qualified call is not an admitted exported import" {:call op}))
                op)]
      (list* op' (map #(rewrite-import-calls % imported) args)))
    (vector? form) (mapv #(rewrite-import-calls % imported) form)
    (map? form) (into (empty form)
                      (map (fn [[k v]] [(rewrite-import-calls k imported)
                                       (rewrite-import-calls v imported)]))
                      form)
    :else form))

(defn- rewrite-calls [form names]
  (cond
    (seq? form) (let [[op & args] form]
                  (list* (get names op op) (map #(rewrite-calls % names) args)))
    (vector? form) (mapv #(rewrite-calls % names) form)
    (map? form) (into (empty form)
                      (map (fn [[k v]] [(rewrite-calls k names) (rewrite-calls v names)]))
                      form)
    :else form))

(defn- source-text [forms]
  (str (str/join "\n" (map pr-str forms)) "\n"))

(defn- analyze-module [forms info dependencies module-index]
  (let [available
        (into {}
              (mapcat (fn [{dep-name :namespace alias :alias}]
                        (let [dependency (get dependencies dep-name)]
                          (when-not dependency
                            (reject! "imported namespace was not resolved"
                                     {:module (:namespace info) :dependency dep-name}))
                          (map (fn [[export interface]]
                                 [(symbol (str alias) (str export)) interface])
                               (:interface dependency))))
                      (:requires info)))
        stub-pairs (map-indexed (fn [index [qualified interface]]
                                  [qualified (symbol (str "kotoba_import__" index)) interface])
                                (sort-by (comp str key) available))
        import->stub (into {} (map (fn [[qualified stub _]] [qualified stub]) stub-pairs))
        stub->target (into {} (map (fn [[_ stub interface]] [stub (:linked-name interface)]) stub-pairs))
        rewritten (mapv #(rewrite-import-calls % import->stub) (without-requires forms))
        augmented (into rewritten (map (fn [[_ stub interface]] (stub-form stub interface)) stub-pairs))
        hir (frontend/analyze (source-text augmented))
        stubs (set (vals import->stub))
        locals (vec (remove #(contains? stubs (:name %)) (:functions hir)))
        local-names (into {} (map-indexed (fn [function-index {:keys [name]}]
                                            [name (symbol (str "kotoba_module__"
                                                               module-index "__"
                                                               function-index))])
                                          locals))
        call-names (merge local-names stub->target)
        functions (mapv (fn [function]
                          (-> function
                              (update :name local-names)
                              (update :body rewrite-calls call-names)))
                        locals)
        by-name (into {} (map (juxt :name identity) locals))
        interface
        (into {}
              (map (fn [export]
                     (let [function (get by-name export)]
                       (when-not function
                         (reject! "export does not name a declared function"
                                  {:module (:namespace info) :export export}))
                       [export (interface-of function (get local-names export))])))
              (:exports info))]
    {:namespace (:namespace info) :requires (:requires info)
     :functions functions :interface interface}))

(defn- wrapper-form [[export {:keys [linked-name params param-types result]}]]
  (list 'defn export (typed-params params param-types) result (list* linked-name params)))

(defn link-source
  "Link a closed namespace->source map into one bounded source unit."
  [sources root]
  (when-not (and (map? sources) (pos? (count sources))
                 (<= (count sources) max-project-modules))
    (reject! "project source map is empty or exceeds module limit"
             {:count (when (map? sources) (count sources))}))
  (let [parsed (into {}
                     (map (fn [[declared source]]
                            (let [forms (frontend/read-forms source)
                                  info (ns-info forms)]
                              (when-not (= declared (:namespace info))
                                (reject! "source-map key does not match declared namespace"
                                         {:key declared :declared (:namespace info)}))
                              [declared {:forms forms :info info}])))
                     sources)
        visiting (volatile! #{}) linked (volatile! {}) order (volatile! [])]
    (letfn [(visit [name]
              (when-not (contains? parsed name)
                (reject! "required module is outside the closed project" {:module name}))
              (when (contains? @visiting name)
                (reject! "cyclic module dependency rejected" {:module name}))
              (when-not (contains? @linked name)
                (vswap! visiting conj name)
                (doseq [{dependency :namespace} (get-in parsed [name :info :requires])]
                  (visit dependency))
                (let [module (analyze-module (get-in parsed [name :forms])
                                             (get-in parsed [name :info])
                                             @linked (count @order))]
                  (vswap! linked assoc name module)
                  (vswap! order conj name))
                (vswap! visiting disj name)))]
      (visit root))
    (let [root-module (get @linked root)
          functions (vec (mapcat #(get-in @linked [% :functions]) @order))
          exports (sort-by (comp str key) (:interface root-module))
          wrappers (mapv wrapper-form exports)
          function-count (+ (count functions) (count wrappers))]
      (when (> function-count max-project-functions)
        (reject! "linked project exceeds function limit" {:count function-count}))
      {:source
       (source-text
        (into [(list 'ns root (list :export (vec (map first exports))))]
              (concat
               (map (fn [{:keys [name params param-types result body]}]
                      (list 'defn- name (typed-params params param-types) result body))
                    functions)
               wrappers)))
       :root root :module-order @order :modules (set @order)})))
