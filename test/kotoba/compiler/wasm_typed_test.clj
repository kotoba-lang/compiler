(ns kotoba.compiler.wasm-typed-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.backend.wasm-typed :as typed]
            [kotoba.compiler.core :as compiler]))

(def option-source
  "(defn main [] (match-option (option-some-of [:option :i64] 7)
                                [:option :i64]
                                (none 0)
                                (some value (+ value 1))))")

(deftest typed-metadata-is-versioned-deterministic-and-bounded
  (let [kir (:kir (compiler/compile-source option-source :js-kotoba-v1))
        table (typed/descriptor-table kir)
        bytes (typed/metadata-bytes kir)]
    (is (= typed/abi-version (first bytes)))
    (is (= bytes (typed/metadata-bytes kir)))
    (is (= (count table) (count (typed/descriptor-indices kir))))
    (is (empty? (typed/literal-table kir)))
    (is (some #{[:option :i64]} table))
    (is (every? #(<= 0 % 255) bytes))))

(deftest typed-custom-section-is-emitted-only-for-kir-v4
  (let [i64-kir (:kir (compiler/compile-source "(defn main [] 7)" :wasm32-kotoba-v1))
        typed-kir (assoc i64-kir :format :kotoba.kir/v4)
        typed-bytes (vec (map #(bit-and (int %) 0xff)
                              (wasm/emit typed-kir :wasm32-kotoba-v1)))
        i64-bytes (vec (map #(bit-and (int %) 0xff)
                            (wasm/emit i64-kir :wasm32-kotoba-v1)))
        marker (mapv int (.getBytes typed/custom-section-name "UTF-8"))]
    (testing "custom section identity is present in typed modules"
      (is (some #(= marker %) (partition (count marker) 1 typed-bytes))))
    (testing "legacy i64 modules do not acquire a typed ABI claim"
      (is (not-any? #(= marker %) (partition (count marker) 1 i64-bytes))))))
