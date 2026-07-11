(ns kotoba.compiler.verifier-profile-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.verifier :as verifier]))

(defn- base-artifact []
  (:artifact (compiler/compile-source "(defn main [] 0)" :x86_64-kotoba-v1)))

(defn- replace-program [kexe program]
  (artifact/seal
   (-> kexe
       (dissoc :sha256)
       (assoc :program program :kir-sha256 (artifact/sha256 program)))))

(deftest verifier-budgets-let-amplification-before-reemission
  (let [kexe (base-artifact)
        body '(let [a (+ 1 1) b (+ a a) c (+ b b)] (+ c c))
        program (assoc-in (:program kexe) [:functions 0 :body] body)
        hostile (replace-program kexe program)]
    (with-redefs [verifier/max-lowered-nodes 10]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lowering budget"
                            (verifier/verify-artifact! hostile))))))

(deftest verifier-independently-rejects-hidden-effects-and-invalid-ast
  (let [kexe (base-artifact)]
    (doseq [[message body]
            [[#"function effects" '(cap-call 7 1)]
             [#"operation rejected" '(attacker-op 1)]
             [#"heap operation arity" '(pair 1)]
             [#"unbound symbol" 'attacker]]]
      (let [program (assoc-in (:program kexe) [:functions 0 :body] body)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                              (verifier/verify-artifact!
                               (replace-program kexe program))))))))

(deftest verifier-enforces-function-shape-before-backend
  (let [kexe (base-artifact)
        function {:name 'six :params '[a b c d e f] :result :i64
                  :effects #{} :body 0}
        program (update (:program kexe) :functions conj function)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"function shape"
                          (verifier/verify-artifact!
                           (replace-program kexe program))))))
