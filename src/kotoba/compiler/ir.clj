(ns kotoba.compiler.ir)

(defn- checked [n]
  (when-not (<= Long/MIN_VALUE n Long/MAX_VALUE)
    (throw (ex-info "integer overflow during constant evaluation" {:phase :ir :value n})))
  (long n))

(defn eval-constant [form]
  (if (integer? form)
    (checked form)
    (let [[op & args] form
          xs (mapv eval-constant args)]
      (checked
       (case op
         + (reduce +' xs)
         - (if (= 1 (count xs)) (-' (first xs)) (reduce -' xs))
         * (reduce *' xs)
         quot (do (when (zero? (second xs))
                    (throw (ex-info "division by zero" {:phase :ir})))
                  (quot (first xs) (second xs))))))))

(defn lower [hir]
  {:format :kotoba.kir/v1
   :entry (:entry hir)
   :signature {:params [] :result :i64}
   :effects (:effects hir)
   :blocks [{:id 0 :instructions [[:const.i64 (eval-constant (:body hir))]
                                 [:return]]}]})
