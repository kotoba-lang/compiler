(ns kotoba.compiler.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.schema :as schema]))

(def node-table
  {:app/node
   [:variant :app/node
    [[:leaf :i64]
     [:branch [:vector [[:ref :app/node] [:ref :app/node]]]]]]})

(deftest recursive-schema-has-stable-nominal-content-identity
  (let [first-id (schema/identities node-table)
        second-id (schema/identities node-table)]
    (is (= first-id second-id))
    (is (re-matches #"[0-9a-f]{64}" (get first-id :app/node)))
    (is (not= first-id
              (schema/identities
               (assoc-in node-table [:app/node 2 0 1] :f64))))))

(deftest recursive-schema-fails-closed
  (doseq [[label table message]
          [[:missing {:app/a [:option [:ref :app/missing]]} #"not declared"]
           [:alias-cycle {:app/a [:ref :app/b] :app/b [:ref :app/a]} #"not productive"]
           [:unqualified {:a [:option :i64]} #"qualified"]
           [:wrong-nominal {:app/a [:record :app/b [[:x :i64]]]} #"outside"]]]
    (testing (name label)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                            (schema/validate-table! table))))))
