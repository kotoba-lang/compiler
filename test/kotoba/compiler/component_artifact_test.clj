(ns kotoba.compiler.component-artifact-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.component-artifact :as component]
            [kotoba.compiler.component-wit :as wit]))

(def scalar-kir
  {:format :kotoba.kir/v4 :exports ['add]
   :schemas {}
   :functions [{:name 'add :params ['left 'right]
                :param-types [:i64 :i64] :result :i64 :body '(+ left right)}]})

(deftest scalar-slice-and-unsupported-boundaries-are-explicit
  (is (true? (component/assert-scalar-slice! scalar-kir (wit/emit scalar-kir))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"structured component signatures"
                        (component/assert-scalar-slice!
                         (assoc-in scalar-kir [:functions 0 :result] :string)
                         (wit/emit (assoc-in scalar-kir [:functions 0 :result] :string)))))
  (let [cap-kir (assoc-in scalar-kir [:functions 0 :body]
                          '(typed-cap-call 4 :i64 :i64 left))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"capability imports"
                          (component/assert-scalar-slice! cap-kir (wit/emit cap-kir))))))
