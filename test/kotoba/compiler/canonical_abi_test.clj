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
