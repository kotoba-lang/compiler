(ns kotoba.compiler.frontend-multimethod-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]))

(defn- checked [source]
  (compiler/check-source source))

(defn- oracle [source]
  (ir/execute (ir/lower (:hir (checked source))) 'main []))

(defn- rejection-message [source]
  (try (checked source) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest closed-multimethod-dispatches-and-defaults
  (let [prefix "(defn kind [x] x)
                (defmulti render kind)
                (defmethod render 1 [x] (+ x 10))
                (defmethod render 2 [x] (+ x 20))
                (defmethod render :default [x] 99)"]
    (is (= 11 (oracle (str prefix " (defn main [] (render 1))"))))
    (is (= 22 (oracle (str prefix " (defn main [] (render 2))"))))
    (is (= 99 (oracle (str prefix " (defn main [] (render 7))"))))))

(deftest defmethod-order-is-closed-world-and-declaration-independent
  (is (= 12
         (oracle "(defmethod render 2 [x] (+ x 10))
                  (defn kind [x] x)
                  (defmulti render kind)
                  (defmethod render 1 [x] 0)
                  (defn main [] (render 2))"))))

(deftest closed-multimethod-without-default-traps-on-a-miss
  (is (thrown? clojure.lang.ExceptionInfo
               (oracle "(defn kind [x] x)
                        (defmulti render kind)
                        (defmethod render 1 [x] 10)
                        (defn main [] (render 2))"))))

(deftest closed-multimethod-validates-static-shapes
  (doseq [[source message]
          [["(defn kind [x] x) (defmulti render (fn [x] x))
             (defmethod render 1 [x] x) (defn main [] 0)"
            "defmulti requires an unqualified name and one unqualified dispatch function symbol"]
           ["(defn kind [x] x) (defmulti render kind)
             (defmethod render 1 [x] x) (defmethod render 1 [x] x)
             (defn main [] 0)"
            "duplicate defmethod dispatch value"]
           ["(defn kind [x] x) (defmulti render kind)
             (defmethod render 1 [x] x) (defmethod render 2 [y] y)
             (defn main [] 0)"
            "all defmethods must use the same parameter vector"]
           ["(defmethod render 1 [x] x) (defn main [] 0)"
            "defmethod requires a matching defmulti declaration"]]]
    (testing message
      (is (= message (rejection-message source))))))
