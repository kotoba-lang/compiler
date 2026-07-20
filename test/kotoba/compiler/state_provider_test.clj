(ns kotoba.compiler.state-provider-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.state :as state]
            [kotoba.compiler.reference-runtime :as runtime]))

(def source
  (str "(ns app.state (:export [transact]) (:capabilities #{:state/transact}))"
       "(defn transact [request " (pr-str state/request-type) "] "
       (pr-str state/result-type) " (typed-cap-call :state/transact "
       (pr-str state/request-type) " " (pr-str state/result-type) " request))"))

(defn- host []
  (let [kir (ir/lower (:hir (compiler/check-source source {:allow #{[:cap/call 8]}})))]
    (runtime/instantiate kir {:allow #{8} :providers {8 (state/provider)}})))

(deftest state-provider-round-trips-versioned-values
  (let [runtime (host)
        invoke #((:invoke runtime) 'transact [%])
        put [state/request-type :put [state/put-type :profile/name "Kotoba"]]
        get [state/request-type :get [state/get-type :profile/name]]
        delete [state/request-type :delete [state/delete-type :profile/name]]]
    (is (= :written (second (invoke put))))
    (is (= [state/entry-type :profile/name "Kotoba" 2]
           (nth (invoke get) 2)))
    (is (= [state/result-type :deleted true] (invoke delete)))
    (is (= [state/result-type :missing false] (invoke get)))))

(deftest state-provider-instances-are-isolated
  (let [left (host) right (host)
        put [state/request-type :put [state/put-type :scope/key "left"]]
        get [state/request-type :get [state/get-type :scope/key]]]
    ((:invoke left) 'transact [put])
    (is (= :found (second ((:invoke left) 'transact [get]))))
    (is (= :missing (second ((:invoke right) 'transact [get]))))))
