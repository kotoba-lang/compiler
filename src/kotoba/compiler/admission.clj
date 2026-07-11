(ns kotoba.compiler.admission
  (:require [clojure.set :as set]))

(defn check
  "Deny-by-default capability admission. Policy shape:
  {:allow #{[:cap/call 7] ...}}. Returns least privilege and lint facts or
  throws before any backend is selected."
  [hir policy]
  (when-not (and (map? policy)
                 (every? #{:allow} (keys policy))
                 (or (not (contains? policy :allow)) (set? (:allow policy))))
    (throw (ex-info "malformed capability policy" {:phase :admission})))
  (let [required (set (:effects hir))
        allowed (set (or (:allow policy) #{}))
        malformed (remove #(and (vector? %) (= :cap/call (first %))
                                (integer? (second %)) (<= 0 (second %) 255)
                                (= 2 (count %))) allowed)
        missing (set/difference required allowed)
        unused (set/difference allowed required)]
    (when (seq malformed)
      (throw (ex-info "malformed capability policy" {:phase :admission :entries (set malformed)})))
    (when (seq missing)
      (throw (ex-info "capability policy denies required effects"
                      {:phase :admission :required required :allowed allowed :missing missing})))
    {:admitted? true
     :required required
     :minimal-policy {:allow required}
     :unused-grants unused}))
