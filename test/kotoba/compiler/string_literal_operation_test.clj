(ns kotoba.compiler.string-literal-operation-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.frontend :as frontend]))

(deftest string-substring-folds-by-unicode-code-point
  (let [module (frontend/analyze
                 "(ns string.literal (:export [middle]))
                 (defn middle [] :string
                   (string-substring \"a😀語z\" 1 3))")
        middle (first (filter #(= 'middle (:name %)) (:functions module)))]
    (is (= "😀語" (:body middle)))))

(deftest string-substring-rejects-dynamic-and-invalid-bounds
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires a literal string"
       (frontend/analyze
        "(ns string.dynamic (:export [middle]))
         (defn middle [s :string] :string
           (string-substring s 0 1))")))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"out of bounds"
       (frontend/analyze
        "(ns string.bounds (:export [middle]))
         (defn middle [] :string
           (string-substring \"abc\" 1 4))"))))
