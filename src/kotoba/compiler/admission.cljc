(ns kotoba.compiler.admission
  (:require [clojure.set :as set]
            [kotoba.security.abac :as abac]
            [kotoba.security.information-flow :as flow]
            ;; See `kotoba.compiler.ir`'s ns form for why a conditional
            ;; require needs to be the WHOLE clause, not an item inside it,
            ;; when the other feature needs none -- not an issue here since
            ;; `clojure.set` is always required, but kept consistent.
            #?(:cljs [kotoba.compiler.cljs-i64 :as i64])))

(defn- kotoba-integer?
  "See `kotoba.compiler.frontend`'s identically-named helper docstring: a
  policy's cap-id may be a bigint (this file's caller may parse
  `--policy policy.edn` via `kotoba.compiler.kotoba-reader`, the same
  reader `.kotoba` source itself uses) or a plain number, on `:cljs`."
  [form]
  #?(:clj (integer? form) :cljs (or (i64/bigint-value? form) (integer? form))))

(defn check
  "Deny-by-default capability admission. Policy shape:
  {:allow #{[:cap/call 7] ...} :abac {...} :attributes {...}}. Returns least privilege and lint facts or
  throws before any backend is selected."
  [hir policy]
  (when-not (and (map? policy)
                 (every? #{:allow :abac :attributes :information-flow} (keys policy))
                 (or (not (contains? policy :allow)) (set? (:allow policy))))
    (throw (ex-info "malformed capability policy" {:phase :admission})))
  (let [required (set (:effects hir))
        allowed (set (or (:allow policy) #{}))
        malformed (remove #(and (vector? %) (= :cap/call (first %))
                                (kotoba-integer? (second %)) (<= 0 (second %) 255)
                                (= 2 (count %))) allowed)
        missing (set/difference required allowed)
        unused (set/difference allowed required)
        supplied-attributes (:attributes policy)
        attributes (-> supplied-attributes
                       (assoc :resource (merge {:effects required}
                                               (:resource supplied-attributes)))
                       (assoc :action (merge {:id :compiler/admit
                                              :capabilities required}
                                             (:action supplied-attributes))))
        abac-result (abac/evaluate attributes (:abac policy))
        flow-policy (:information-flow policy)
        flow-result
        (when flow-policy
          (flow/evaluate-egress
           (merge {:subject (get-in attributes [:subject :id])
                   :purpose (:purpose attributes)
                   :now (get-in attributes [:environment :now])}
                  flow-policy)))]
    (when (seq malformed)
      (throw (ex-info "malformed capability policy" {:phase :admission :entries (set malformed)})))
    (when (seq missing)
      (throw (ex-info "capability policy denies required effects"
                      {:phase :admission :required required :allowed allowed :missing missing})))
    (when-not (:abac/allowed? abac-result)
      (throw (ex-info "ABAC policy denies compilation"
                      {:phase :admission
                       :abac/policy-id (:abac/policy-id abac-result)
                       :abac/violations (:abac/violations abac-result)})))
    (when (and flow-result (not (:information-flow/allowed? flow-result)))
      (throw (ex-info "information-flow policy denies compilation"
                      {:phase :admission
                       :information-flow/violations
                       (:information-flow/violations flow-result)})))
    {:admitted? true
     :required required
     :minimal-policy {:allow required}
     :unused-grants unused
     :abac abac-result
     :information-flow flow-result}))
