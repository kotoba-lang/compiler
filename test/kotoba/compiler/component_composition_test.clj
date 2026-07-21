(ns kotoba.compiler.component-composition-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.component-artifact :as artifact]
            [kotoba.compiler.component-composition :as composition]
            [kotoba.compiler.component-core :as component-core]
            [kotoba.compiler.component-wit :as wit]))

(def capability-kir
  {:format :kotoba.kir/v4 :exports ['invoke] :schemas {}
   :functions [{:name 'invoke :params ['request]
                :param-types [:i64] :result :i64
                :body '(typed-cap-call 4 :i64 :i64 request)}]})

(deftest scalar-provider-closes-the-application-world
  (let [world (wit/emit capability-kir)
        application (artifact/package
                     (component-core/emit capability-kir :wasm32-wasi-kotoba-v1)
                     capability-kir world)
        provider (composition/package-scalar-identity-provider :http/post :i64)
        closed (composition/compose-closed application [provider])]
    (is (= :wasm-component-provider/v1 (:format provider)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [:http/post] (:application-imports closed)))
    (is (= [{:capability :http/post :descriptor :i64}] (:providers closed)))
    (is (= [0 97 115 109 13 0 1 0]
           (mapv #(bit-and (int %) 0xff) (take 8 (:bytes closed)))))))

(deftest composition-fails-closed-without-the-required-provider
  (let [world (wit/emit capability-kir)
        application (artifact/package
                     (component-core/emit capability-kir :wasm32-wasi-kotoba-v1)
                     capability-kir world)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not exactly close"
                          (composition/compose-closed application [])))))

(deftest sealed-scalar-record-provider-closes-the-application-world
  (let [descriptor [:ref :demo/point]
        schema [:record :demo/point [[:x :i64] [:weight :f64] [:visible :bool]]]
        schemas {:demo/point schema}
        kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result descriptor
                          :body (list 'typed-cap-call 4 descriptor descriptor 'request)}]}
        world (wit/emit kir)
        application (artifact/package
                     (component-core/emit kir :wasm32-wasi-kotoba-v1) kir world)
        provider (composition/package-record-identity-provider
                  :http/post descriptor schemas)
        closed (composition/compose-closed application [provider])]
    (is (= :record-capability-call (:canonical-lowering application)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [{:capability :http/post :descriptor descriptor}]
           (:providers closed)))))

(deftest structured-capability-boundary-remains-sealed-and-scalar
  (let [descriptor [:ref :demo/point]
        schema [:record :demo/point [[:x :i64] [:label :string]]]
        kir {:format :kotoba.kir/v4 :exports ['invoke]
             :schemas {:demo/point schema}
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result descriptor
                          :body (list 'typed-cap-call 4 descriptor descriptor 'request)}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component-core/emit kir :wasm32-wasi-kotoba-v1)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sealed scalar schema"
                          (composition/package-record-identity-provider
                           :http/post descriptor (:schemas kir))))))
