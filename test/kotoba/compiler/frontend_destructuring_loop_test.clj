(ns kotoba.compiler.frontend-destructuring-loop-test
  "Tests for ADR-2607150000's remaining language extensions: destructuring
  (let + defn params, one level only), bounded vector-i64 literals, and
  loop/recur (compiled to a synthesized recursive
  helper with purely-syntactic free-variable capture). Also regression-covers
  two bugs found and fixed while implementing these: (1) `let`'s bindings
  vector previously bypassed desugar-expr entirely (vectors aren't `seq?`),
  silently skipping map/keyword desugaring in binding VALUES; (2) the
  loop-helper's synthesized function name previously used `gensym`, which is
  safe for let-local temp names (erased to WASM local indices) but NOT for
  exported top-level function names (this compiler's byte-for-byte
  reproducible-build goal requires deterministic naming, not a JVM-process-
  global counter)."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]))

(defn- oracle [source]
  (let [kir (ir/lower (:hir (compiler/check-source source)))]
    (ir/execute kir 'main [])))

(defn- rejection-message [source]
  (try (compiler/check-source source) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; ───────────────────────── let bug-fix regression ─────────────────────────

(deftest let-binding-values-are-desugared-not-passed-through-opaquely
  ;; Before the fix, desugar-expr's generic default case called itself on the
  ;; WHOLE bindings vector as one opaque argument; since vectors aren't
  ;; `seq?`, it passed through unchanged, silently skipping map/keyword
  ;; desugaring of binding VALUES. Live-confirmed broken before this fix:
  ;; "value type is outside the safe profile".
  (is (= 1 (oracle "(defn main [] (let [m {:a 1}] (get m :a)))")))
  (is (= 1 (oracle "(defn main [] (if (let [k :a] (= k :a)) 1 0))"))
      "a keyword literal as a let binding value must also desugar")
  (is (= 3 (oracle "(defn main [] (let [v [1 2 3]] (vector-at v 2)))"))
      "a vector literal as a let binding value must also desugar"))

;; ───────────────────────── vector-as-data ─────────────────────────

(deftest vector-literals-use-the-bounded-typed-vector-profile
  (is (= 1 (oracle "(defn main [] (vector-at [1 2 3] 0))")))
  (is (= 2 (oracle "(defn main [] (vector-at [1 2 3] 1))")))
  (is (= 3 (oracle "(defn main [] (vector-at [1 2 3] 2))"))))

(deftest vector-and-list-have-distinct-owned-representations
  (let [v (:hir (compiler/check-source "(defn main [] (vector-at [1 2 3] 0))"))
        l (:hir (compiler/check-source "(defn main [] (pair-first (list 1 2 3)))"))]
    (is (not= (:body (first (:functions v))) (:body (first (:functions l)))))))

(deftest lets-own-bindings-vector-never-reaches-generic-vector-as-data-dispatch
  ;; A malformed (odd-length) bindings vector must still surface `let`'s OWN
  ;; "even binding vector" error, not be silently reinterpreted as vector-as-
  ;; data by the generic dispatch.
  (is (= "let requires an even binding vector"
         (rejection-message "(defn main [] (let [a] a))"))))

;; ───────────────────────── destructuring: let ─────────────────────────

(deftest vector-destructuring-in-let-binds-positional-and-rest
  (is (= 3 (oracle "(defn main [] (let [[a b] [1 2]] (+ a b)))")))
  (is (= 3 (oracle "(defn main [] (let [[a b & r] [1 2 3 4]] (+ a b)))")))
  (is (= 3 (oracle "(defn main [] (let [[a b & r] [1 2 3 4]] (vector-at r 0)))"))
      "the rest binding collects everything after the named positions"))

(deftest vector-destructuring-missing-position-fails-closed
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"vector-index-out-of-range"
                        (oracle "(defn main [] (let [[a b] [1]] (+ a b)))"))))

(deftest map-destructuring-in-let-binds-via-keys
  (is (= 30 (oracle "(defn main [] (let [{:keys [a b]} {:a 10 :b 20}] (+ a b)))")))
  (is (= 0 (oracle "(defn main [] (let [{:keys [a c]} {:a 10 :b 20}] (- a a c)))"))
      "a missing key in {:keys [...]} defaults to 0, same as bare `get`"))

(deftest destructuring-value-expr-is-evaluated-exactly-once
  ;; Every destructure-binding pattern binds a single gensym'd temp to the
  ;; value first -- verifies a side-effecting-ish (recursive, fuel-consuming)
  ;; value expression is not silently re-evaluated once per bound name.
  (is (= 3 (oracle "(defn one [] 1)
                     (defn main [] (let [[a b] [(one) (+ (one) 1)]] (+ a b)))"))))

(deftest nested-destructuring-patterns-are-rejected-one-level-only
  (is (some? (rejection-message "(defn main [] (let [[[a] b] [[1] 2]] a))"))
      "a vector pattern nested inside a vector pattern is not supported")
  (is (some? (rejection-message "(defn main [] (let [[a & b & c] [1 2 3]] a))"))
      "more than one rest-binding symbol after `&` is rejected")
  (is (some? (rejection-message "(defn main [] (let [{:keys [a] :or {a 1}} {}] a))"))
      "map destructuring supports only {:keys [...]}, no :or/:as/:strs"))

(deftest first-map-profile-rejects-nested-map-values
  (is (= "expression type mismatch: expected i64, got map"
         (rejection-message "(defn main [] (let [{:keys [a]} {:a {:b 1}}] (get a :b)))"))))

;; ───────────────────────── destructuring: defn params ─────────────────────────

(deftest defn-params-support-vector-destructuring
  (is (= 15 (oracle "(ns t) (defn addpair [[a b] :vector-i64] (+ a b)) (defn main [] (addpair [7 8]))"))))

(deftest defn-params-support-map-destructuring
  (is (= 7 (oracle "(ns t) (defn addkv [{:keys [a b]} :map] (+ a b)) (defn main [] (addkv {:a 3 :b 4}))"))))

(deftest defn-params-mix-plain-symbols-and-destructuring-patterns
  (is (= 16 (oracle "(ns t) (defn f [x :i64 [a b] :vector-i64] (+ x (+ a b))) (defn main [] (f 10 [3 3]))"))))

;; ───────────────────────── loop/recur ─────────────────────────

(deftest loop-recur-accumulates-across-iterations
  (is (= 10 (oracle "(defn main [] (loop [i 0 acc 0] (if (= i 5) acc (recur (+ i 1) (+ acc i)))))"))
      "0+1+2+3+4 = 10"))

(deftest loop-with-zero-iterations-returns-the-initial-bindings
  (is (= 0 (oracle "(defn main [] (loop [i 0] (if (= i 0) i (recur (+ i 1)))))"))))

(deftest loop-captures-free-variables-from-the-enclosing-scope
  (is (= 15 (oracle "(defn main [] (let [step 5] (loop [i 0 acc 0] (if (= i 3) acc (recur (+ i 1) (+ acc step))))))"))
      "the loop body references `step`, bound in main's own `let`, not one of loop's own bindings"))

(deftest nested-loops-each-get-their-own-independent-helper
  (is (= 5 (oracle "(defn main [] (+ (loop [i 0] (if (= i 3) i (recur (+ i 1)))) (loop [j 0] (if (= j 2) j (recur (+ j 1))))))"))
      "3 + 2 = 5, two independently-recurring loops in one function"))

(deftest loops-across-separate-defns-get-uniquely-named-helpers
  (let [{:keys [functions]} (:hir (compiler/check-source
                                    "(ns t) (defn f1 [] (loop [i 0] (if (= i 3) i (recur (+ i 1)))))
                                     (defn main [] (+ (f1) (loop [j 0] (if (= j 2) j (recur (+ j 1))))))"))]
    (is (= 4 (count functions)) "f1 + its loop helper + main + its loop helper")
    (is (= 4 (count (distinct (map :name functions))))
        "every synthesized loop-helper name must be unique across the whole source")))

(deftest recur-argument-count-must-match-loop-bindings
  (is (some? (rejection-message "(defn main [] (loop [i 0] (recur i i)))"))))

(deftest loop-bindings-must-be-plain-symbols-not-destructuring-patterns
  (is (some? (rejection-message "(defn main [] (loop [[a b] [1 2]] a))"))
      "destructure inside the loop body instead, per the documented scope limit"))

(deftest loop-requires-exactly-one-body-expression
  (is (some? (rejection-message "(defn main [] (loop [i 0] i i))"))))

(deftest loop-helper-is-only-injected-when-loop-is-actually-used
  (let [{:keys [functions]} (:hir (compiler/check-source "(defn main [] (+ 1 2))"))]
    (is (= 1 (count functions)))))

;; ───────────────────────── reproducibility ─────────────────────────

(deftest loop-compiled-bytes-are-identical-across-repeated-compiles
  ;; Guards against a real regression found and fixed while implementing
  ;; loop/recur: the synthesized helper name previously used `gensym` (a
  ;; JVM-process-global monotonic counter) -- safe for let-local temp names
  ;; (erased to WASM local indices) but NOT for this exported top-level
  ;; function name, which is baked literally into the WASM export section.
  ;; Live-confirmed with `gensym`: two compiles of identical source in one
  ;; process produced DIFFERENT bytes despite an identical oracle value.
  (let [source "(defn main [] (loop [i 0 acc 0] (if (= i 5) acc (recur (+ i 1) (+ acc i)))))"
        c1 (compiler/compile-source source :wasm32-kotoba-v1)
        c2 (compiler/compile-source source :wasm32-kotoba-v1)]
    (is (= (:oracle-value (:kir c1)) (:oracle-value (:kir c2))))
    (is (= (:kir c1) (:kir c2)) "the KIR itself, including synthesized helper names, must match")
    (is (java.util.Arrays/equals ^bytes (:bytes c1) ^bytes (:bytes c2))
        "the compiled WASM bytes must be byte-for-byte identical")))

(deftest multi-defn-loop-compiled-bytes-are-identical-across-repeated-compiles
  (let [source "(ns t) (defn f1 [] (loop [i 0] (if (= i 3) i (recur (+ i 1)))))
                (defn main [] (+ (f1) (loop [j 0] (if (= j 2) j (recur (+ j 1))))))"
        c1 (compiler/compile-source source :wasm32-kotoba-v1)
        c2 (compiler/compile-source source :wasm32-kotoba-v1)]
    (is (java.util.Arrays/equals ^bytes (:bytes c1) ^bytes (:bytes c2)))))

;; ───────────────────────── cross-backend consistency ─────────────────────────

(deftest loop-produces-one-shared-kir-across-all-backends
  ;; `loop` alone (no destructuring) has no gensym'd temp names in its
  ;; desugared shape -- *loop-counter* is deterministic -- so, like the
  ;; existing map/get/assoc cross-backend test, its :kir is expected to be
  ;; byte-for-byte identical across every backend's own `analyze` call.
  (let [source "(defn main [] (loop [i 0 acc 0] (if (= i 5) acc (recur (+ i 1) (+ acc i)))))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= 1 (count (set (map :kir results)))))
    (is (= 10 (:oracle-value (:kir (first results)))))))

(deftest typed-map-destructuring-agrees-with-reference-execution
  (is (= 3 (oracle "(defn main [] (let [{:keys [a b]} {:a 1 :b 2}] (+ a b)))"))))
