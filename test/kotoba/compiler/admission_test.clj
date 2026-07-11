(ns kotoba.compiler.admission-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]))

(def effect-source
  "(defn audit [x] (cap-call 7 x))
   (defn helper [x] (audit x))
   (defn main [] 42)")

(deftest effects-propagate-and-all-exports-are-covered
  (let [{:keys [hir admission]}
        (compiler/check-source effect-source {:allow #{[:cap/call 7] [:cap/call 9]}})
        by-name (into {} (map (juxt :name identity) (:functions hir)))]
    (is (= #{[:cap/call 7]} (:effects hir)))
    (is (= #{[:cap/call 7]} (:effects (get by-name 'audit))))
    (is (= #{[:cap/call 7]} (:effects (get by-name 'helper))))
    (is (= #{} (:effects (get by-name 'main))))
    (is (= {:allow #{[:cap/call 7]}} (:minimal-policy admission)))
    (is (= #{[:cap/call 9]} (:unused-grants admission)))))

(deftest admission-is-deny-by-default
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denies required effects"
                        (compiler/check-source effect-source)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denies required effects"
                        (compiler/check-source effect-source {:allow #{[:cap/call 8]}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed capability policy"
                        (compiler/check-source effect-source {:allow #{[:network "*"]}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed capability policy"
                        (compiler/check-source effect-source {:allow #{[:cap/call 999]}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed capability policy"
                        (compiler/check-source effect-source
                                               {:allow #{[:cap/call 7]} :ignored true})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed capability policy"
                        (compiler/check-source effect-source {:allow [[:cap/call 7]]}))))

(deftest dynamic-capability-identifiers-and-native-codegen-fail-closed
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal capability id"
                        (compiler/check-source
                         "(defn f [cap x] (cap-call cap x)) (defn main [] 0)"
                         {:allow #{[:cap/call 7]}})))
  (let [wasm (compiler/compile-source effect-source :wasm32-kotoba-v1
                                      {:allow #{[:cap/call 7]}})]
    (is (= #{[:cap/call 7]} (get-in wasm [:admission :required])))
    (is (= [0 97 115 109] (mapv #(bit-and (int %) 0xff) (take 4 (:bytes wasm))))))
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (let [artifact (:artifact (compiler/compile-source effect-source target
                                                       {:allow #{[:cap/call 7]}}))]
      (is (= #{[:cap/call 7]} (:effects artifact)))
      (is (= {:version 1 :fuel-offset 8 :allow-bitmap-offset 16
              :allow-bitmap-bytes 32 :cap-call-offset 48}
             (:context-abi artifact))))))

(deftest mutual-call-effects-reach-fixpoint
  (let [source "(defn left [x] (if (= x 0) (cap-call 3 x) (right (- x 1))))
                (defn right [x] (left x))
                (defn main [] 0)"
        hir (:hir (compiler/check-source source {:allow #{[:cap/call 3]}}))
        effects (into {} (map (juxt :name :effects) (:functions hir)))]
    (is (= #{[:cap/call 3]} (get effects 'left)))
    (is (= #{[:cap/call 3]} (get effects 'right)))))

(deftest effects-cannot-hide-in-lexical-bindings
  (let [source "(defn hidden [x] (let [y (cap-call 5 x)] (+ y 1)))
                (defn main [] 0)"
        hir (:hir (compiler/check-source source {:allow #{[:cap/call 5]}}))]
    (is (= #{[:cap/call 5]} (:effects hir)))))
