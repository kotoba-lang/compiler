(ns kotoba.compiler.value-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.value :as value]))

(deftest exact-utf8-count-and-malformed-unicode-rejection
  (is (= 3 (value/utf8-byte-count! "abc")))
  (is (= 6 (value/utf8-byte-count! "言葉")))
  (is (= 4 (value/utf8-byte-count! "😀")))
  (is (= "安全" (value/bounded-string! "安全" 6)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds UTF-8 byte limit"
                        (value/bounded-string! "安全" 5)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unpaired high surrogate"
                        (value/utf8-byte-count!
                         (String. (char-array [(char 0xd800)])))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unpaired low surrogate"
                        (value/utf8-byte-count!
                         (String. (char-array [(char 0xdc00)]))))))

(deftest bounded-keyword-and-map-values-are-owned-and-typed
  (is (= :安全/確認 (value/bounded-keyword! :安全/確認 32)))
  (is (= {:a 1 :b 2} (value/bounded-map! {:a 1 :b 2})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a keyword"
                        (value/bounded-keyword! "a" 32)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a signed i64"
                        (value/bounded-map! {:a "unsafe"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds entry limit"
                        (value/bounded-map!
                         (into {} (map (fn [index] [(keyword (str "k" index)) index])
                                       (range 129)))))))

(deftest option-i64-has-an-explicit-bounded-tagged-representation
  (is (= [false] (value/bounded-option-i64! [false])))
  (is (= [true 7] (value/bounded-option-i64! [true 7])))
  (doseq [invalid [nil 0 false [] [false 1] [true] [true "7"] [nil 7]]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-option-i64! invalid)))))

(deftest result-i64-has-two-closed-payload-bearing-variants
  (is (= [true 7] (value/bounded-result-i64! [true 7])))
  (is (= [false -3] (value/bounded-result-i64! [false -3])))
  (doseq [invalid [[true] [false] [true 1 2] [:ok 1] [true "1"] nil]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-result-i64! invalid)))))

(deftest parametric-result-types-share-fixed-depth-and-node-budgets
  (let [type [:result :string [:result :i64 :bool]]]
    (is (= [true "安全"] (value/bounded-typed-value! type [true "安全"])))
    (is (= [false [true 7]] (value/bounded-typed-value! type [false [true 7]])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-typed-value! type [true 7])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-typed-value! type [false [true "7"]]))))
  (let [too-deep (nth (iterate (fn [t] [:result :i64 t]) :bool) 9)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"depth limit"
                          (value/validate-value-type! too-deep)))))

(deftest closed-variant-values-carry-their-complete-type-identity
  (let [type [:variant :demo/status [[:ready :i64] [:failed :string]]]]
    (is (= [type :ready 7]
           (value/bounded-typed-value! type [type :ready 7])))
    (is (= [type :failed "安全"]
           (value/bounded-typed-value! type [type :failed "安全"])))
    (doseq [invalid [[[:variant :other/status [[:ready :i64]]] :ready 7]
                     [type :unknown 7] [type :ready "7"] [type :failed 7]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (value/bounded-typed-value! type invalid)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"qualified keyword"
                        (value/validate-value-type! [:variant :status [[:ready :i64]]])))
  (is (thrown? clojure.lang.ExceptionInfo
               (value/validate-value-type! [:variant :demo/dup [[:same :i64] [:same :bool]]]))))

(deftest generic-option-none-retains-its-complete-type-identity
  (let [string-option [:option :string]
        i64-option [:option :i64]
        nested-option [:option [:result :i64 :bool]]]
    (is (= [string-option false]
           (value/bounded-typed-value! string-option [string-option false])))
    (is (= [string-option true "安全"]
           (value/bounded-typed-value! string-option [string-option true "安全"])))
    (is (= [nested-option true [true 7]]
           (value/bounded-typed-value! nested-option [nested-option true [true 7]])))
    (doseq [invalid [[i64-option false]
                     [string-option]
                     [string-option false "extra"]
                     [string-option true]
                     [string-option true 7]
                     [string-option nil]
                     nil]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (value/bounded-typed-value! string-option invalid))))))

(deftest heterogeneous-vector-values-seal-position-types-and-exact-length
  (let [type [:vector [:i64 :string :bool]]
        nested-type [:vector [[:option :string] [:result :i64 :bool]]]]
    (is (= [type 7 "安全" true]
           (value/bounded-typed-value! type [type 7 "安全" true])))
    (is (= [nested-type [[:option :string] false] [true 9]]
           (value/bounded-typed-value!
            nested-type [nested-type [[:option :string] false] [true 9]])))
    (doseq [invalid [[type 7 "安全"]
                     [type 7 "安全" true :extra]
                     [type "7" "安全" true]
                     [[ :vector [:string :string :bool]] 7 "安全" true]
                     nil]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (value/bounded-typed-value! type invalid)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"heterogeneous vector types"
                        (value/validate-value-type!
                         [:vector (vec (repeat 33 :i64))]))))

(deftest vector-i64-is-bounded-and-homogeneous
  (is (= [1 2 3] (value/bounded-vector-i64! [1 2 3])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a vector-i64"
                        (value/bounded-vector-i64! '(1 2))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a signed i64"
                        (value/bounded-vector-i64! [1 "2"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds item limit"
                        (value/bounded-vector-i64! (vec (range 129))))))
