(ns kotoba.compiler.reference-runtime
  "Portable CLJ/CLJS reference application runtime. This is the executable
  language oracle; AOT/JIT backends qualify against the same KIR and vectors."
  (:require [kotoba.compiler.ir :as ir]))

(def runtime-format :kotoba.reference-runtime/v1)
(def max-providers 256)

(defn- fail! [message data]
  (throw (ex-info message (assoc data :phase :reference-runtime))))

(defn capability-contracts [kir]
  (let [contracts (->> (:functions kir)
                       (mapcat #(tree-seq coll? seq (:body %)))
                       (keep (fn [form]
                               (when (and (seq? form) (= 'typed-cap-call (first form)))
                                 (let [[_ id request-type result-type] form]
                                   [id {:request-type request-type
                                        :result-type result-type}]))))
                       (into {}))]
    contracts))

(defn instantiate
  "Instantiates checked KIR with an exact, deny-by-default typed provider
  registry. Providers are maps containing :request-type, :result-type and
  :invoke. Their declared contract must equal the guest's sealed contract."
  ([kir] (instantiate kir {}))
  ([kir {:keys [allow providers] :or {allow #{} providers {}} :as options}]
   (when-not (every? #{:allow :providers} (keys options))
     (fail! "reference runtime options are not exact" {:keys (set (keys options))}))
   (when-not (and (set? allow) (every? #(and (integer? %) (<= 0 % 255)) allow))
     (fail! "allow must be a set of capability ids" {:allow allow}))
   (when-not (and (map? providers) (<= (count providers) max-providers))
     (fail! "providers must be a bounded map" {}))
   (let [contracts (capability-contracts kir)]
     (doseq [[id provider] providers]
       (when-not (and (contains? allow id)
                      (= #{:request-type :result-type :invoke} (set (keys provider)))
                      (ifn? (:invoke provider)))
         (fail! "provider is not exactly admitted" {:capability id})))
     (doseq [[id contract] contracts]
       (when-let [provider (get providers id)]
         (when-not (= contract (select-keys provider [:request-type :result-type]))
           (fail! "provider contract does not match guest contract"
                  {:capability id :guest contract
                   :provider (select-keys provider [:request-type :result-type])}))))
     (let [typed-dispatch
           (fn [id request-type result-type request]
             (when-not (contains? allow id)
               (fail! "capability denied" {:capability id}))
             (let [provider (or (get providers id)
                                (fail! "capability provider is not installed"
                                       {:capability id}))]
               ((:invoke provider) request)))]
       {:format runtime-format
        :contracts contracts
        :exports (set (:exports kir))
        :invoke (fn [function-name args]
                  (ir/execute kir function-name args
                              {:typed-cap-call typed-dispatch}))}))))
