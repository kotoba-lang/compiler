(ns kotoba.compiler.backend-cljs-test
  "Tests for ADR-2607151500's new cljs backend (kotoba.compiler.backend.cljs):
  KIR -> plain ClojureScript source text, a genuinely separate execution
  target alongside wasm32/x86_64/aarch64.

  These tests eval the emitted source under PLAIN JVM CLOJURE, not a real
  cljs/nbb runtime -- deliberately, matching this compiler's own existing
  convention of not depending on an external runtime (Chicory for wasm) in
  its committed test suite (see frontend_extensions_test.clj's addendum
  notes: real Chicory execution was verified via an uncommitted scratch
  script, not a committed test). This is a legitimate real-execution check
  here specifically because every construct `backend.cljs/emit` ever emits
  (defn/let/if/vector/nth/atom/swap!/throw/ex-info, a namespaced map
  literal) is valid, semantically identical Clojure AND ClojureScript --
  there is nothing cljs-specific in the emitted text this backend produces.
  Real nbb execution of the exact same generated sources (including the
  fuel-exhaustion global-depletion property) was independently verified by
  hand before this commit; see ADR-2607151500."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]))

(defn- compile-cljs
  ([source] (compiler/compile-source source :cljs-kotoba-v1))
  ([source policy] (compiler/compile-source source :cljs-kotoba-v1 policy)))

(defn- eval-in-fresh-ns
  "Reads and evals every top-level form EXCEPT the emitted `(ns ...)` form
  (a real cljs host would `require` the emitted namespace by name; here we
  just eval everything into a fresh throwaway JVM namespace instead, to
  keep every test isolated from the others -- the emitted `defonce`
  fuel atom must not leak between tests)."
  [source-text]
  (let [ns-sym (gensym "kotoba-cljs-eval-test-ns-")
        forms (read-string (str "(" source-text ")"))
        target-ns (create-ns ns-sym)]
    (binding [*ns* target-ns]
      (clojure.core/refer-clojure)
      (doseq [form forms]
        (when-not (and (seq? form) (= 'ns (first form)))
          (eval form))))
    target-ns))

(defn- call [ns fn-sym & args]
  (apply (ns-resolve ns fn-sym) args))

;; ───────────────────────── emit shape + oracle parity ─────────────────────────

(deftest emit-produces-readable-multi-form-cljs-source
  (let [{:keys [source]} (compile-cljs "(defn main [] (+ 1 2))")
        forms (read-string (str "(" source ")"))]
    (is (= '(ns kotoba.compiled.generated) (first forms)))
    (is (some #(and (seq? %) (= 'defn (first %)) (= 'main (second %))) forms))))

(deftest arithmetic-matches-oracle
  (let [compiled (compile-cljs "(defn main [] (+ 1 2))")
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 3 (:oracle-value (:kir compiled))))
    (is (= 3 (call ns 'main)))))

(deftest pair-round-trips-through-plain-vectors
  (let [source "(defn main [] (let [m (pair 1 2)] (+ (pair-first m) (pair-second m))))"
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 3 (:oracle-value (:kir compiled))))
    (is (= 3 (call ns 'main)))))

(deftest if-treats-zero-as-false-not-clojures-own-truthy-zero
  ;; The crux semantic gap this backend must bridge: cljs/clj's OWN `if`
  ;; treats 0 as truthy (unlike KIR's 0-is-false convention) -- if the
  ;; wrapping were missing, this would return the WRONG branch.
  (let [source "(defn main [] (if (= 1 2) 42 0))" ; (= 1 2) -> KIR value 0 -> KIR-false -> else branch (0)
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 0 (:oracle-value (:kir compiled))))
    (is (= 0 (call ns 'main)))))

(deftest named-function-calls-work-across-multiple-defns
  (let [source "(defn helper [x] (* x x)) (defn main [] (helper 7))"
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 49 (:oracle-value (:kir compiled))))
    (is (= 49 (call ns 'main)))
    (is (= 100 (call ns 'helper 10)))))

(deftest destructuring-and-loop-recur-lower-through-unchanged
  ;; loop/recur and destructuring are entirely FRONTEND desugars (ADR-
  ;; 2607150000) -- by the time KIR sees this source it's already plain
  ;; named-function recursion, so this backend needs no special handling;
  ;; this test is the proof.
  (let [source "(defn main [] (loop [i 0 acc 0] (if (= i 5) acc (recur (+ i 1) (+ acc i)))))"
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 10 (:oracle-value (:kir compiled))))
    (is (= 10 (call ns 'main)))))

;; ───────────────────────── division-by-zero / fuel ─────────────────────────

(deftest quot-by-zero-throws-instead-of-silently-returning-infinity
  ;; Plain cljs `quot` on JS numbers does NOT throw on a zero divisor
  ;; (produces Infinity/NaN) -- kotoba$quot's explicit guard is what makes
  ;; this backend trap the same way ir.clj's oracle and the wasm/native
  ;; backends do, rather than silently diverging into IEEE-754 semantics.
  (let [source "(defn maybe-div [a b] (quot a b)) (defn main [] (+ 1 2))"
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 3 (call ns 'main)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"division-by-zero"
                          (call ns 'maybe-div 10 0)))))

(deftest fuel-is-a-global-never-replenished-budget-not-per-call
  ;; Mirrors WASM's own module-global fuel semantics exactly (ir.clj/
  ;; backend/wasm.clj: charge once per function ENTRY, a single Instance
  ;; gets 512 calls total for its whole lifetime, never replenished) --
  ;; NOT 512 fresh calls every time `main` is invoked.
  (let [source "(defn noop [] 0) (defn main [] (+ 1 2))"
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (dotimes [_ 511] (call ns 'noop))
    (is (= 3 (call ns 'main)) "the 512th call still succeeds")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fuel-exhausted"
                          (call ns 'noop))
        "the 513th call, ever, on this loaded module traps -- not reset per top-level call")))

;; ───────────────────────── arithmetic overflow ─────────────────────────

(deftest arithmetic-within-safe-integer-range-is-unaffected
  (let [compiled (compile-cljs "(defn main [] (+ 9007199254740990 1))") ; == 2^53-1, the boundary itself
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 9007199254740991 (:oracle-value (:kir compiled))))
    (is (= 9007199254740991 (call ns 'main)))))

(deftest arithmetic-past-safe-integer-range-throws-instead-of-silently-diverging
  ;; True i64 wraparound parity would need every value represented as a JS
  ;; BigInt end to end -- not attempted (see this backend's own docstring).
  ;; Instead, exceeding JS's safe-integer bound (2^53-1, past which a plain
  ;; cljs `number` can no longer represent every integer exactly) throws
  ;; :arithmetic-overflow rather than silently continuing with an
  ;; imprecise value -- the same fail-closed posture already used for
  ;; fuel/division/capability, narrowing the wraparound gap from "silently
  ;; wrong" to "loudly fails."
  (let [compiled (compile-cljs "(defn main [] (* 100000000 100000000))") ; 10^16 > 2^53-1
        ns (eval-in-fresh-ns (:source compiled))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arithmetic-overflow"
                          (call ns 'main)))))

(deftest arithmetic-overflow-check-applies-to-plus-minus-and-times
  (doseq [[source expect-throw?]
          [["(defn main [] (+ 100000000 100000000))" false]
           ["(defn main [] (* 100000000 100000000))" true]
           ["(defn main [] (- 0 100000000000000000))" true]]]
    (let [compiled (compile-cljs source)
          ns (eval-in-fresh-ns (:source compiled))]
      (if expect-throw?
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arithmetic-overflow" (call ns 'main))
            source)
        (is (= (:oracle-value (:kir compiled)) (call ns 'main)) source)))))

;; ───────────────────────── cap-call ─────────────────────────

(deftest cap-call-with-no-dispatcher-installed-is-denied-fail-closed
  ;; a capability POLICY that admits this cap-call at the frontend/admission
  ;; level -- so a throw here is genuinely this backend's OWN cljs-side
  ;; capability boundary (kotoba$cap-dispatch defaulting to nil), not the
  ;; earlier, stricter static admission gate short-circuiting first.
  (let [compiled (compile-cljs "(defn main [] (cap-call 1 2))" {:allow #{[:cap/call 1]}})
        ns (eval-in-fresh-ns (:source compiled))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"capability-denied"
                          (call ns 'main))
        "no dispatcher installed -- every cap-call denied, matching kotoba-lang/kotoba's own fail-closed has-capability-fn")))

(deftest cap-call-dispatches-to-the-installed-host-function
  (let [compiled (compile-cljs "(defn main [] (cap-call 1 42))" {:allow #{[:cap/call 1]}})
        ns (eval-in-fresh-ns (:source compiled))]
    (call ns 'set-cap-dispatch! (fn [cap-id value] (+ (* cap-id 100) value)))
    (is (= 142 (call ns 'main))
        "cap-id 1 and value 42 both reach the installed host function unchanged")))

;; ───────────────────────── cross-backend consistency ─────────────────────────

(deftest cljs-target-is-in-compatibility-targets-and-agrees-with-other-backends
  (let [source "(defn main [] (+ 1 2))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (contains? compiler/targets :cljs-kotoba-v1))
    (is (= 1 (count (set (map :kir results)))))
    (is (= 3 (:oracle-value (:kir (first results)))))))
