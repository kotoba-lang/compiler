(ns kotoba.compiler.frontend-equality-diagnostic-test
  "The safe equality profile rejects `=` on :string and :f64 operands. Those
  rejections must NAME the admitted alternative (string=? / f64-eq or
  f64-to-bits) instead of only saying the type is outside the profile --
  fleet-migration field evidence (com-junkawasaki/root ADR-2607241100 D2)
  showed the bare message costs a compile-debug cycle per project."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.frontend :as frontend]))

(defn- rejection-message [source]
  (try
    (frontend/analyze source)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-message error))))

(deftest string-equality-rejection-names-the-admitted-operator
  (let [message (rejection-message
                 (str "(ns pilot.eq-str (:export [check])) "
                      "(defn check [] (if (= \"a\" \"b\") 1 0))"))]
    (is (some? message))
    (is (clojure.string/includes? message "outside the safe value profile"))
    (is (clojure.string/includes? message "string=?"))))

(deftest f64-equality-rejection-names-both-admitted-alternatives
  (let [message (rejection-message
                 (str "(ns pilot.eq-f64 (:export [check])) "
                      "(defn check [] (if (= 1.5 2.5) 1 0))"))]
    (is (some? message))
    (is (clojure.string/includes? message "outside the safe value profile"))
    (is (clojure.string/includes? message "f64-eq"))
    (is (clojure.string/includes? message "f64-to-bits"))))

(deftest admitted-equality-types-are-unaffected
  (testing "i64 equality still analyzes"
    (is (some? (frontend/analyze
                (str "(ns pilot.eq-i64 (:export [check])) "
                     "(defn check [] (if (= 1 2) 1 0))")))))
  (testing "keyword equality still analyzes"
    (is (some? (frontend/analyze
                (str "(ns pilot.eq-kw (:export [check])) "
                     "(defn check [] (if (= :a :b) 1 0))")))))
  (testing "mismatched operand types still use the same-type rejection"
    (let [message (rejection-message
                   (str "(ns pilot.eq-mixed (:export [check])) "
                        "(defn check [] (if (= 1 :a) 1 0))"))]
      (is (some? message))
      (is (clojure.string/includes? message "same value type")))))
