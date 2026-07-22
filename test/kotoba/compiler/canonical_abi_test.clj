(ns kotoba.compiler.canonical-abi-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.canonical-abi :as canonical]))

(deftest scalar-and-bounded-string-layouts-are-closed
  (is (= {:descriptor :i64 :size 8 :alignment 8 :flat [:i64]}
         (canonical/layout :i64)))
  (is (= {:descriptor :string :size 8 :alignment 4 :flat [:i32 :i32]
          :encoding :utf8 :max-bytes 65536
          :validation [:checked-pointer-range :valid-utf8]}
         (canonical/layout :string)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical ABI layout"
                        (canonical/layout [:vector :i64]))))

(deftest string-export-uses-standard32-indirect-result
  (is (= {:profile :component-model/standard32-v1
          :function 'echo
          :parameters [{:name 'value
                        :layout {:descriptor :string :size 8 :alignment 4
                                 :flat [:i32 :i32] :encoding :utf8
                                 :max-bytes 65536
                                 :validation [:checked-pointer-range :valid-utf8]}}]
          :core-params [:i32 :i32]
          :result-layout {:descriptor :string :size 8 :alignment 4
                          :flat [:i32 :i32] :encoding :utf8 :max-bytes 65536
                          :validation [:checked-pointer-range :valid-utf8]}
          :indirect-result? true
          :core-results [:i32]
          :post-return-params [:i32]}
         (canonical/export-plan
          {:name 'echo :params ['value] :param-types [:string] :result :string}))))

(deftest named-scalar-record-layout-preserves-identity-offsets-and-flattening
  (let [descriptor [:ref :demo/point]
        schemas {:demo/point
                 [:record :demo/point [[:x :i64] [:weight :f64] [:visible :bool]]]}
        value (canonical/layout descriptor schemas)]
    (is (= 24 (:size value)))
    (is (= 8 (:alignment value)))
    (is (= [:i64 :f64 :i32] (:flat value)))
    (is (= [0 8 16] (mapv :offset (:fields value))))
    (is (= [:i32]
           (:core-results
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matching schema identity"
                          (canonical/layout descriptor
                                            {:demo/point [:record :demo/other [[:x :i64]]]})))))

(deftest one-level-nested-record-layout-flattens-recursively-with-absolute-offsets
  (let [descriptor [:ref :demo/outer]
        schemas {:demo/inner [:record :demo/inner [[:code :i64] [:ratio :f64]]]
                 :demo/outer [:record :demo/outer
                              [[:id :i64] [:inner [:ref :demo/inner]] [:active :bool]]]}
        outer-layout (canonical/layout descriptor schemas)
        inner-layout (canonical/layout [:ref :demo/inner] schemas)]
    (is (= 32 (:size outer-layout)))
    (is (= 8 (:alignment outer-layout)))
    (is (= [:i64 :i64 :f64 :i32] (:flat outer-layout)))
    (is (= [0 8 24] (mapv :offset (:fields outer-layout))))
    (is (= inner-layout (get-in outer-layout [:fields 1 :layout])))
    (is (= [{:offset 0 :descriptor :i64}
            {:offset 8 :descriptor :i64}
            {:offset 16 :descriptor :f64}
            {:offset 24 :descriptor :bool}]
           (canonical/layout-leaves outer-layout)))
    (is (= [:i32]
           (:core-results
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))
    (is (= [:i64 :i64 :f64 :i32]
           (:core-params
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"recursive schema has no bounded"
                          (canonical/layout [:ref :demo/self]
                                            {:demo/self [:record :demo/self
                                                         [[:child [:ref :demo/self]]]]})))))
