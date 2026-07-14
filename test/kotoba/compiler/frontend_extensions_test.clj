(ns kotoba.compiler.frontend-extensions-test
  "Tests for ADR-2607150000's language extensions: and/or/when (ported from
  kotoba-lang/kotoba's already-proven runtime.clj desugar-and/desugar-or),
  and keyword/map literals + get/assoc (new, desugared entirely to the
  existing heap-pair/list primitives -- no backend/codegen change)."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.frontend :as frontend]))

(defn- oracle [source]
  (:oracle-value (:kir (compiler/compile-source source :wasm32-kotoba-v1))))

(defn- rejection-message [source]
  (try (compiler/check-source source) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; ───────────────────────── and/or/when ─────────────────────────

(deftest and-short-circuits-and-evaluates-each-arg-once
  (is (= 1 (oracle "(defn main [] (and))")))
  (is (= 5 (oracle "(defn main [] (and 5))")))
  (is (= 0 (oracle "(defn main [] (and 1 0 99))")))
  (is (= 7 (oracle "(defn main [] (and 1 2 7))")))
  (is (= 0 (oracle "(defn main [] (and 0 (quot 1 0)))")) ; would trap if 2nd arg evaluated
      "false first arg must short-circuit past a trapping second arg"))

(deftest or-short-circuits-and-evaluates-each-arg-once
  (is (= 0 (oracle "(defn main [] (or))")))
  (is (= 5 (oracle "(defn main [] (or 5))")))
  (is (= 3 (oracle "(defn main [] (or 3 0))")))
  (is (= 4 (oracle "(defn main [] (or 0 4))")))
  (is (= 9 (oracle "(defn main [] (or 9 (quot 1 0)))")) ; would trap if 2nd arg evaluated
      "true first arg must short-circuit past a trapping second arg"))

(deftest when-is-if-with-implicit-else-zero
  (is (= 42 (oracle "(defn main [] (when 1 42))")))
  (is (= 0 (oracle "(defn main [] (when 0 42))")))
  (is (= "when requires a test and exactly one result expression (this profile has no `do`, unlike kotoba-lang/kotoba's)"
         (rejection-message "(defn main [] (when 1 2 3))"))
      "no implicit do in this profile -- exactly one result expression"))

(deftest and-or-when-are-reserved-function-names
  (is (some? (rejection-message "(defn and [] 1) (defn main [] 0)")))
  (is (some? (rejection-message "(defn or [] 1) (defn main [] 0)")))
  (is (some? (rejection-message "(defn when [] 1) (defn main [] 0)"))))

;; ───────────────────────── keyword + map + get + assoc ─────────────────────────

(deftest keyword-literals-are-deterministic-and-distinct
  (is (= (oracle "(defn main [] (get {:a 1} :a))")
         (oracle "(defn main [] (get {:a 1} :a))"))
      "the same keyword must intern to the same constant across separate compiles")
  (is (= 1 (oracle "(defn main [] (if (= :a :a) 1 0))")))
  (is (= 0 (oracle "(defn main [] (if (= :a :b) 1 0))"))))

(deftest map-literal-get-round-trips
  (is (= 1 (oracle "(defn main [] (get {:a 1} :a))")))
  (is (= 2 (oracle "(defn main [] (get {:a 1 :b 2} :b))")))
  (is (= 0 (oracle "(defn main [] (get {:a 1} :missing))"))
      "2-arg get defaults to 0 on a miss")
  (is (= 99 (oracle "(defn main [] (get {:a 1} :missing 99))"))
      "3-arg get uses the explicit default on a miss")
  (is (= 0 (oracle "(defn main [] (get {} :a))")) "get on an empty map is a miss"))

(deftest map-literal-values-can-be-arbitrary-expressions
  (is (= 7 (oracle "(defn main [] (get {:a (+ 3 4)} :a))"))))

(deftest get-on-a-map-passed-through-a-function-parameter-walks-at-runtime
  (is (= 5 (oracle "(defn extract [m] (get m :a))
                     (defn main [] (extract {:a 5}))"))
      "get must work on a map value whose shape isn't known at the call site,
       not just on a literal map inlined at the get form itself"))

(deftest assoc-adds-and-shadows
  (is (= 7 (oracle "(defn main [] (get (assoc {:a 1} :c 7) :c))")))
  (is (= 5 (oracle "(defn main [] (get (assoc {:a 1} :a 5) :a))"))
      "assoc on an existing key shadows the old value (get returns the newest)")
  (is (= 1 (oracle "(defn main [] (get (assoc {} :a 1) :a))"))
      "assoc onto an empty map")
  (is (= 9 (oracle "(defn main [] (get (assoc {:a 1} :b 2 :c 9) :c))"))
      "variadic assoc with multiple key/value pairs in one call"))

(deftest map-entry-count-is-admission-bounded
  (with-redefs [frontend/max-list-items 1]
    (is (some? (rejection-message "(defn main [] (get {:a 1 :b 2} :a))")))))

(deftest map-get-helper-collision-is-caught-as-duplicate-function-name
  (is (some? (rejection-message
              "(defn __kotoba_map_get [m k default] 0)
               (defn main [] (get {:a 1} :a))"))))

(deftest get-and-assoc-are-reserved-function-names
  (is (some? (rejection-message "(defn get [] 1) (defn main [] 0)")))
  (is (some? (rejection-message "(defn assoc [] 1) (defn main [] 0)"))))

(deftest map-get-helper-not-injected-unless-get-is-used
  (let [{:keys [functions]} (:hir (compiler/check-source "(defn main [] (+ 1 2))"))]
    (is (not (contains? (set (map :name functions)) '__kotoba_map_get)))))

(deftest map-literal-entry-count-cannot-exceed-max-list-items
  ;; A literal map can never grow past max-list-items (128, shared with
  ;; `list`) -- so a get-miss walk over the LARGEST admissible map literal
  ;; costs at most 129 fuel units (128 steps + 1 for main's own call),
  ;; comfortably under this compiler's fixed 256-fuel budget. Verifies
  ;; that bound actually holds, rather than just asserting it in prose.
  (let [source (str "(defn main [] (get {"
                     (clojure.string/join " " (map #(str ":k" % " " %) (range 128)))
                     "} :missing))")]
    (is (= 0 (oracle source)) "a full-width admissible map miss completes within fuel")))

(deftest map-get-recursion-shares-the-existing-fuel-budget
  ;; get/assoc introduce no new resource limit -- they are subject to the
  ;; SAME fixed fuel budget (ir.clj/backend/wasm.clj's 256-instruction-call
  ;; global counter) every other recursive .kotoba program already is.
  ;; Demonstrated here via a helper that repeatedly assocs while recursing
  ;; past the budget -- the existing mechanism traps it, unmodified.
  (let [source "(defn build [m n] (if (= n 0) m (build (assoc m :dummy n) (- n 1))))
                (defn main [] (get (build {} 300) :missing))"]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fuel"
                          (:oracle-value (:kir (compiler/compile-source source :wasm32-kotoba-v1)))))))

;; ───────────────────────── cross-backend consistency ─────────────────────────

(deftest map-get-assoc-produce-one-shared-kir-across-all-backends
  (let [source "(defn main [] (get (assoc {:a 1} :b 2) :b))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= 1 (count (set (map :kir results)))))
    (is (= 2 (:oracle-value (:kir (first results)))))))
