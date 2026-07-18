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

(deftest vector-i64-is-bounded-and-homogeneous
  (is (= [1 2 3] (value/bounded-vector-i64! [1 2 3])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a vector-i64"
                        (value/bounded-vector-i64! '(1 2))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a signed i64"
                        (value/bounded-vector-i64! [1 "2"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds item limit"
                        (value/bounded-vector-i64! (vec (range 129))))))
