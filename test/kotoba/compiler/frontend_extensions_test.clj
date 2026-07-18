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

(deftest first-class-closures-cross-value-boundaries
  (testing "a closure is an ordinary pair value with statically dispatched invocation"
    (is (= 5 (oracle "(defn main [] (let [f (fn [x] (+ x 1))] (invoke f 4)))")))
    (is (= 7 (oracle "(defn main [] (let [n 3 f (fn [x] (+ x n))] (invoke f 4)))")))
    (is (= 8 (oracle "(defn make [n] (fn [x] (+ x n)))
                      (defn main [] (invoke (make 5) 3))")))
    (is (= 3 (oracle "(defn main [] (let [f (fn [x] (+ x 1)) v [f]]
                                      (invoke (nth v 0) 2)))")))
    (is (= 9 (oracle "(defn call [f x] (invoke f x))
                      (defn main [] (call (fn [x] (+ x 4)) 5))")))
    (is (= 17 (oracle "(defn main []
                        (let [a (fn [x] (+ x 1)) b (fn [x] (+ x 10))]
                          (+ (invoke a 2) (invoke b 4))))")))))

(deftest closure-invoke-is-bounded-and-explicit
  (is (some? (rejection-message
              "(defn main [] (invoke (fn [a b c d e] a) 1 2 3 4 5))")))
  (is (some? (rejection-message
              "(defn main [] (invoke (fn [x] x) 1 2 3 4 5))"))))

(deftest first-class-closures-work-as-higher-order-callback-values
  (is (= 4 (oracle "(defn main []
                      (let [n 1 f (fn [x] (+ x n))]
                        (first (map f [3]))))")))
  (is (= 4 (oracle "(defn keep? [limit]
                      (fn [x] (> x limit)))
                    (defn main []
                      (first (filter (keep? 2) [1 4 2])))")))
  (is (= 16 (oracle "(defn main []
                       (let [scale 2 f (fn [acc x] (+ acc (* scale x)))]
                         (reduce f 4 [1 2 3])))")))
  (is (= 7 (oracle "(defn main []
                      (let [add (fn [a b] (+ a b))]
                        (first (map add [1 2] [6 8]))))"))))

(deftest closure-callback-state-remains-abi-bounded
  (is (some? (rejection-message
              "(defn main []
                 (let [f (fn [a b c d] a)]
                   (map f [1] [2] [3] [4] [5])))"))))

(deftest multi-arity-closures-can-be-stored-returned-and-reduced
  (is (= 9 (oracle "(defn make [base]
                      (fn ([] base) ([acc x] (+ acc x))))
                    (defn main []
                      (let [f (make 9)] (invoke f)))")))
  (is (= 6 (oracle "(defn make []
                      (fn ([] 40) ([acc x] (+ acc x))))
                    (defn main [] (reduce (make) [1 2 3]))")))
  (is (= 40 (oracle "(defn main []
                       (let [f (fn ([] 40) ([acc x] (+ acc x)))]
                         (reduce f [])))")))
  (is (= 6 (oracle "(defn main []
                       (let [bias 4 f (fn ([] bias) ([acc x] (+ acc x bias)))]
                         (reduce f [1 1])))"))))

(deftest bounded-apply-dispatches-first-class-closures
  (is (= 40 (oracle "(defn main [] (apply (fn [] 40) []))")))
  (is (= 7 (oracle "(defn main [] (apply (fn [x] (+ x 2)) [5]))")))
  (is (= 9 (oracle "(defn main [] (apply (fn [a b] (+ a b)) 4 [5]))")))
  (is (= 10 (oracle "(defn main []
                       (apply (fn [a b c d] (+ (+ a b) (+ c d))) 1 2 [3 4]))")))
  (is (= 0 (oracle "(defn main [] (apply (fn [a] a) [1 2 3 4 5]))"))
      "runtime argument lists beyond four fail closed"))

(deftest top-level-functions-have-explicit-first-class-references
  (is (= 9 (oracle "(defn add [a b] (+ a b))
                     (defn main [] (apply (fn-ref add) [4 5]))")))
  (is (= 3 (oracle "(defn plus ([] 7) ([x] (+ x 1)) ([a b] (+ a b)))
                     (defn main []
                       (let [f (fn-ref plus)] (invoke f 1 2)))")))
  (is (= 6 (oracle "(defn twice [x] (* x 2))
                     (defn main [] (first (map (fn-ref twice) [3])))")))
  (is (some? (rejection-message
              "(defn five [a b c d e] a)
               (defn main [] (fn-ref five))")))
  (is (some? (rejection-message "(defn main [] (fn-ref missing))"))))

(deftest variadic-closure-clauses-specialize-through-arity-four
  (is (= 4 (oracle "(defn main []
                      (let [f (fn [x & more] (+ x (nth more 1 0)))]
                        (invoke f 1 2 3)))")))
  (is (= 4 (oracle "(defn main []
                      (let [f (fn [& xs] (count xs))]
                        (invoke f 1 2 3 4)))")))
  (is (= 9 (oracle "(defn main []
                      (let [f (fn ([x] (+ x 7)) ([x & more] (+ x (count more))))]
                        (invoke f 2)))"))
      "a fixed clause overrides its overlapping variadic specialization")
  (is (some? (rejection-message
              "(defn main [] (fn [a b c d e & more] a))"))))

(deftest apply-requires-a-bounded-explicit-argument-tail
  (is (some? (rejection-message "(defn main [] (apply (fn [] 0)))")))
  (is (some? (rejection-message
              "(defn main [] (apply (fn [a b c d] a) 1 2 3 4 5 [6]))"))))

(deftest lazy-sequences-delay-head-and-tail-and-support-infinite-generators
  (is (= 7 (oracle "(defn main []
                      (lazy-first (lazy-cons 7 (quot 1 0))))"))
      "forcing the head must not evaluate the tail")
  (is (= 9 (oracle "(defn main []
                      (lazy-first (lazy-rest
                                   (lazy-cons (quot 1 0)
                                              (lazy-cons 9 0)))))"))
      "forcing the tail must not evaluate the previous head")
  (is (= 4 (oracle "(defn naturals [n]
                      (lazy-cons n (naturals (+ n 1))))
                    (defn main [] (nth (take 5 (naturals 0)) 4))")))
  (is (= 5 (oracle "(defn naturals [n]
                      (lazy-cons n (naturals (+ n 1))))
                    (defn main [] (lazy-first (drop 5 (naturals 0))))")))
  (is (= 1 (oracle "(defn main [] (lazy-empty? 0))")))
  (is (= 0 (oracle "(defn main [] (lazy-empty? (lazy-cons 1 0)))"))))

(deftest lazy-take-and-drop-are-empty-and-count-safe
  (is (= 0 (oracle "(defn main [] (take 0 (lazy-cons (quot 1 0) 0)))")))
  (is (= 0 (oracle "(defn main [] (drop 5 0))")))
  (is (= 0 (oracle "(defn main [] (take -1 (lazy-cons (quot 1 0) 0)))"))))

(deftest non-memoized-lazy-thunks-reject-effects
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"lazy sequence thunks must be effect-free"
       (compiler/compile-source
        "(defn main [] (lazy-cons (cap-call 1 2) 0))"
        :wasm32-kotoba-v1
        {:allow #{[:cap/call 1]}}))))

(deftest lazy-map-preserves-laziness-across-callback-kinds
  (is (= 8 (oracle "(defn naturals [n] (lazy-cons n (naturals (+ n 1))))
                     (defn twice [x] (* x 2))
                     (defn main [] (nth (take 5 (lazy-map twice (naturals 0))) 4))")))
  (is (= 7 (oracle "(defn main []
                      (lazy-first
                       (lazy-map (fn [x] (+ x 2))
                                 (lazy-cons 5 (quot 1 0)))))"))
      "observing the mapped head does not force the source tail")
  (is (= 9 (oracle "(defn make [n] (fn [x] (+ x n)))
                     (defn main []
                       (lazy-first (lazy-map (make 4) (lazy-cons 5 0))))"))))

(deftest lazy-map-supports-one-to-four-sequences-and-shortest-termination
  (is (= 14 (oracle "(defn naturals [n] (lazy-cons n (naturals (+ n 1))))
                      (defn add [a b] (+ a b))
                      (defn main []
                        (nth (take 3 (lazy-map add (naturals 1) (naturals 9))) 2))")))
  (is (= 1 (oracle "(defn main []
                      (lazy-empty?
                       (lazy-rest
                        (lazy-map (fn [a b] (+ a b))
                                  (lazy-cons 1 0)
                                  (lazy-cons 2 (lazy-cons 3 0))))))"))
      "multi-source lazy-map stops when the shortest source ends")
  (is (= 10 (oracle "(defn main []
                       (lazy-first
                        (lazy-map (fn [a b c d] (+ (+ a b) (+ c d)))
                                  (lazy-cons 1 0) (lazy-cons 2 0)
                                  (lazy-cons 3 0) (lazy-cons 4 0))))")))
  (is (some? (rejection-message
              "(defn main []
                 (lazy-map (fn [a b c d] a)
                           0 0 0 0 0))"))))

(deftest lazy-filter-defers-search-and-represents-deferred-empty
  (is (= 6 (oracle "(defn naturals [n] (lazy-cons n (naturals (+ n 1))))
                     (defn main []
                       (nth (take 3 (lazy-filter (fn [x] (> x 3))
                                                (naturals 0))) 2))")))
  (is (= 1 (oracle "(defn main []
                      (lazy-empty?
                       (lazy-filter (fn [x] 0)
                                    (lazy-cons 1 (lazy-cons 2 0)))))"))
      "an all-rejected finite source resolves to true empty")
  (is (= 2 (oracle "(defn main []
                      (lazy-first
                       (lazy-filter (fn [x] (> x 1))
                                    (lazy-cons 2 (quot 1 0)))))"))
      "finding the first match does not force the source tail")
  (is (= 5 (oracle "(defn predicate [limit] (fn [x] (> x limit)))
                     (defn main []
                       (lazy-first
                        (lazy-filter (predicate 4)
                                     (lazy-cons 3 (lazy-cons 5 0)))))"))))

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
  ;; ADR-2607180900 L2: multi-body when is admitted via `do` desugar.
  (is (= 3 (oracle "(defn main [] (when 1 2 3))"))
      "multi-body when returns the last body expression")
  (is (= 0 (oracle "(defn main [] (when 0 2 3))"))
      "multi-body when still else-zero when test is falsy"))

(deftest do-sequences-and-returns-last
  (is (= 5 (oracle "(defn main [] (do 1 2 5))")))
  (is (= 0 (oracle "(defn main [] (do))")))
  (is (= 9 (oracle "(defn main [] (do 9))"))))

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

(deftest assoc-removes-the-old-entry-not-just-shadows-it
  ;; A real bug found and fixed in the same ADR-2607150000 line of work
  ;; that first landed assoc: the original desugar only ever prepended a
  ;; new pair, so get's first-match-wins scan made the RESULT correct but
  ;; the map's underlying pair-chain grew without bound under repeated
  ;; re-assoc of the same key (documented as a known, unfixed tradeoff at
  ;; the time). __kotoba_map_without now removes the old entry before the
  ;; new one is prepended -- verified here by literally counting chain
  ;; length, not just checking the shadowed value (which the old, buggy
  ;; code already got right).
  (let [count-entries "(defn count-entries [m] (if (= m 0) 0 (+ 1 (count-entries (pair-second m)))))"]
    (is (= 2 (oracle (str count-entries
                          "(defn main [] (count-entries (assoc (assoc (assoc {:a 1 :b 2} :a 3) :a 4) :a 5)))")))
        "3 re-assocs of the same key on a 2-distinct-key map -- entry count stays 2, not 5")
    (is (= 3 (oracle (str count-entries
                          "(defn main [] (count-entries (assoc {} :a 1 :b 2 :c 3)))")))
        "3 distinct keys assoc'd in one call -- entry count is exactly 3")
    (is (= 2 (oracle "(defn main [] (get (assoc (assoc {:a 1 :b 2} :a 3) :a 4) :b))"))
        "re-assoc-ing :a repeatedly must not disturb :b's own value")))

(deftest map-entry-count-is-admission-bounded
  (with-redefs [frontend/max-list-items 1]
    (is (some? (rejection-message "(defn main [] (get {:a 1 :b 2} :a))")))))

(deftest map-get-helper-collision-is-caught-as-duplicate-function-name
  (is (some? (rejection-message
              "(defn __kotoba_map_get [m k default] 0)
               (defn main [] (get {:a 1} :a))"))))

(deftest map-without-helper-collision-is-caught-as-duplicate-function-name
  (is (some? (rejection-message
              "(defn __kotoba_map_without [m k] 0)
               (defn main [] (get (assoc {:a 1} :a 2) :a))"))))

(deftest get-and-assoc-are-reserved-function-names
  (is (some? (rejection-message "(defn get [] 1) (defn main [] 0)")))
  (is (some? (rejection-message "(defn assoc [] 1) (defn main [] 0)"))))

(deftest map-get-helper-not-injected-unless-get-is-used
  (let [{:keys [functions]} (:hir (compiler/check-source "(defn main [] (+ 1 2))"))]
    (is (not (contains? (set (map :name functions)) '__kotoba_map_get)))))

(deftest map-without-helper-not-injected-unless-assoc-is-used
  (let [{:keys [functions]} (:hir (compiler/check-source "(defn main [] (get {:a 1} :a))"))]
    (is (contains? (set (map :name functions)) '__kotoba_map_get)
        "get alone still injects its own helper")
    (is (not (contains? (set (map :name functions)) '__kotoba_map_without))
        "but not the assoc-only helper, since this source never calls assoc")))

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

(deftest map-get-produces-one-shared-kir-across-all-backends
  (let [source "(defn main [] (get {:a 1 :b 2} :b))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= 1 (count (set (map :kir results)))))
    (is (= 2 (:oracle-value (:kir (first results)))))))

(deftest map-assoc-produces-consistent-oracle-values-across-all-backends
  ;; assoc's desugar gensym's LET-LOCAL temp names (assoc-k__NNN/
  ;; assoc-v__NNN, added along with the entry-removal fix in
  ;; __kotoba_map_without) -- like destructuring's own gensym'd temps
  ;; (ADR-2607150000 addendum 4), this is a JVM-process-global counter NOT
  ;; reset per `analyze` call, so raw :kir data is NOT expected to be
  ;; identical across the separate `analyze` calls each backend makes here
  ;; -- harmless for actual compiled behavior (LET-LOCAL names are erased
  ;; to WASM local indices), but this test checks the invariant that
  ;; actually matters: the computed VALUE agrees across every backend, not
  ;; raw :kir data identity (which get-only sources, with no gensym
  ;; involved, DO still satisfy -- see the test above).
  (let [source "(defn main [] (get (assoc {:a 1} :b 2) :b))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= 1 (count (set (map #(:oracle-value (:kir %)) results)))))
    (is (= 2 (:oracle-value (:kir (first results)))))))

;; ───────────────────────── set + contains? + conj + disj ─────────────────────────

(deftest set-literal-membership-round-trips
  (is (= 1 (oracle "(defn main [] (contains? #{:a :b} :a))")))
  (is (= 0 (oracle "(defn main [] (contains? #{:a :b} :missing))")))
  (is (= 0 (oracle "(defn main [] (contains? #{} :a))"))))

(deftest set-conj-and-disj-have-value-semantics
  (is (= 1 (oracle "(defn main [] (contains? (conj #{:a} :b) :b))")))
  (is (= 0 (oracle "(defn main [] (contains? (disj #{:a :b} :a) :a))")))
  (is (= 1 (oracle "(defn main [] (contains? (disj #{:a :b}) :b))"))
      "zero-value disj is the identity"))

(deftest set-conj-eliminates-runtime-equal-duplicates
  (let [count-items "(defn count-items [s] (if (= s 0) 0 (+ 1 (count-items (pair-second s)))))"]
    (is (= 1 (oracle (str count-items
                          "(defn main [] (count-items (conj #{(+ 1 1)} 2 2)))")))
        "literal expressions and conj values that evaluate equally occupy one slot")))

(deftest set-values-pass-through-function-parameters
  (is (= 1 (oracle "(defn member [s x] (contains? s x))
                     (defn main [] (member #{4 5} 5))"))))

(deftest set-item-count-is-admission-bounded
  (with-redefs [frontend/max-set-items 1]
    (is (some? (rejection-message "(defn main [] (contains? #{1 2} 1))")))))

(deftest set-operations-are-reserved-function-names
  (doseq [name '[contains? conj disj]]
    (is (some? (rejection-message (str "(defn " name " [] 1) (defn main [] 0)"))))))

(deftest set-helpers-are-injected-only-when-used
  (let [{:keys [functions]} (:hir (compiler/check-source "(defn main [] (+ 1 2))"))
        names (set (map :name functions))]
    (is (not (contains? names '__kotoba_set_contains)))
    (is (not (contains? names '__kotoba_set_without)))))

(deftest set-operations-produce-consistent-values-across-backends
  (let [source "(defn main [] (contains? (disj (conj #{:a} :b) :a) :b))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= #{1} (set (map #(:oracle-value (:kir %)) results))))))

;; ───────────────────────── bounded higher-order collections ─────────────────────────

(deftest map-applies-a-named-unary-function
  (is (= 3 (oracle "(defn inc1 [x] (+ x 1))
                     (defn main [] (pair-first (pair-second (map inc1 [1 2 3]))))"))))

(deftest map-supports-multiple-collections-and-stops-at-the-shortest
  (is (= 22 (oracle "(defn add [a b] (+ a b))
                      (defn main [] (pair-first (pair-second (map add [1 2 3] [10 20]))))")))
  (is (= 1 (oracle "(defn add [a b] (+ a b))
                     (defn main [] (empty? (map add [1 2] [])))")))
  (is (= 15 (oracle "(defn sum5 [a b c d e] (+ a b c d e))
                      (defn main [] (pair-first (map sum5 [1] [2] [3] [4] [5])))"))))

(deftest filter-preserves-values-whose-named-predicate-is-truthy
  (is (= 2 (oracle "(defn positive [x] (pos? x))
                     (defn main [] (pair-first (filter positive [0 2 0 3])))"))))

(deftest reduce-folds-with-explicit-init-and-named-binary-function
  (is (= 16 (oracle "(defn add [acc x] (+ acc x))
                      (defn main [] (reduce add 10 [1 2 3]))"))))

(deftest higher-order-collection-callbacks-are-statically-resolved
  (is (some? (rejection-message "(defn main [] (map 1 [1 2]))")))
  (is (some? (rejection-message "(defn binary [a b] (+ a b))
                                  (defn main [] (map binary [1 2]))"))
      "callback arity is checked through the ordinary function-call validator"))

(deftest higher-order-helper-names-are-reproducible
  (let [source "(defn inc1 [x] (+ x 1)) (defn main [] (map inc1 [1 2]))"
        c1 (compiler/compile-source source :wasm32-kotoba-v1)
        c2 (compiler/compile-source source :wasm32-kotoba-v1)]
    (is (= (:kir c1) (:kir c2)))
    (is (java.util.Arrays/equals ^bytes (:bytes c1) ^bytes (:bytes c2)))))

(deftest higher-order-collections-agree-across-backends
  (let [source "(defn add [a b] (+ a b)) (defn main [] (reduce add 0 [1 2 3 4]))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= #{10} (set (map #(:oracle-value (:kir %)) results))))))

(deftest higher-order-collections-accept-inline-fn-closures
  (is (= 7 (oracle "(defn main []
                     (let [offset 5]
                       (pair-first (map (fn [x] (+ x offset)) [2 3]))))")))
  (is (= 3 (oracle "(defn main []
                     (let [floor 2]
                       (pair-first (filter (fn [x] (> x floor)) [1 3 4]))))")))
  (is (= 15 (oracle "(defn main []
                      (let [bonus 1]
                        (reduce (fn [acc x] (+ acc (+ x bonus))) 10 [1 2])))"))))

(deftest inline-fn-callback-validates-arity-and-capture-budget
  (is (some? (rejection-message "(defn main [] (map (fn [a b] (+ a b)) [1]))")))
  (is (some? (rejection-message
              "(defn main []
                 (let [a 1 b 2 c 3 d 4]
                   (reduce (fn [acc x] (+ acc (+ x (+ a (+ b (+ c d)))))) 0 [1])))"))
      "reduce needs two state params, leaving at most three closure captures under the ABI limit"))

(deftest inline-fn-effects-remain-visible-to-admission
  (let [hir (frontend/analyze
             "(defn main [] (map (fn [x] (cap-call 7 x)) [1]))")]
    (is (contains? (:effects hir) [:cap/call 7]))))

(deftest inline-fn-closure-output-is-reproducible
  (let [source "(defn main [] (let [n 3] (map (fn [x] (+ x n)) [1 2])))"
        c1 (compiler/compile-source source :wasm32-kotoba-v1)
        c2 (compiler/compile-source source :wasm32-kotoba-v1)]
    (is (= (:kir c1) (:kir c2)))
    (is (java.util.Arrays/equals ^bytes (:bytes c1) ^bytes (:bytes c2)))))

(deftest no-init-reduce-supports-clojure-empty-and-nonempty-semantics
  (is (= 6 (oracle "(defn main []
                     (reduce (fn ([] 0) ([acc x] (+ acc x))) [1 2 3]))")))
  (is (= 42 (oracle "(defn main []
                      (reduce (fn ([] 42) ([acc x] (+ acc x))) []))")))
  (is (= 6 (oracle "(defn main []
                     (let [bonus 3]
                       (reduce (fn ([] bonus) ([acc x] (+ acc (+ x bonus)))) [1 2])))")))
  (is (= 3 (oracle "(defn main []
                     (let [bonus 3]
                       (reduce (fn ([] bonus) ([acc x] (+ acc x))) [])))"))))

(deftest no-init-reduce-rejects-ambiguous-single-arity-callbacks
  (is (some? (rejection-message
              "(defn add [a b] (+ a b)) (defn main [] (reduce add [1 2]))")))
  (is (some? (rejection-message
              "(defn main [] (reduce (fn [a b] (+ a b)) [1 2]))")))
  (is (some? (rejection-message
              "(defn main [] (reduce (fn ([] 0) ([a b] (+ a b)) ([x] x)) [1 2]))"))))

;; ───────────────────────── persistent collection operations ─────────────────────────

(deftest count-and-nth-operate-on-pair-chain-collections
  (is (= 3 (oracle "(defn main [] (count [10 20 30]))")))
  (is (= 0 (oracle "(defn main [] (count []))")))
  (is (= 20 (oracle "(defn main [] (nth [10 20 30] 1))")))
  (is (= 99 (oracle "(defn main [] (nth [10] 4 99))")))
  (is (= 99 (oracle "(defn main [] (nth [10] -1 99))"))))

(deftest peek-and-pop-are-persistent-and-empty-safe
  (is (= 10 (oracle "(defn main [] (peek [10 20]))")))
  (is (= 20 (oracle "(defn main [] (peek (pop [10 20])))")))
  (is (= 0 (oracle "(defn main [] (peek []))")))
  (is (= 0 (oracle "(defn main [] (pop []))"))))

(deftest map-keys-vals-and-dissoc-return-new-collections
  (is (= 2 (oracle "(defn main [] (count (keys {:a 1 :b 2})))")))
  (is (= 3 (oracle "(defn main [] (reduce (fn ([] 0) ([a b] (+ a b))) (vals {:a 1 :b 2})))")))
  (is (= 0 (oracle "(defn main [] (get (dissoc {:a 1 :b 2} :a) :a))")))
  (is (= 2 (oracle "(defn main [] (get (dissoc {:a 1 :b 2} :a) :b))")))
  (is (= 0 (oracle "(defn main [] (count (dissoc {:a 1 :b 2} :a :b)))"))))

(deftest collection-operations-pass-through-function-boundaries
  (is (= 7 (oracle "(defn select [xs] (nth xs 2 0))
                     (defn main [] (select [5 6 7]))"))))

(deftest collection-operation-results-agree-across-backends
  (let [source "(defn main [] (+ (count [1 2 3]) (nth (vals {:a 4 :b 5}) 1 0)))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= 1 (count (set (map #(:oracle-value (:kir %)) results)))))))

;; ───────────────────────── records as tagged persistent maps ─────────────────────────

(deftest defrecord-generates-positional-and-map-constructors
  (is (= 7 (oracle "(defrecord Point [x y])
                     (defn main [] (get (->Point 7 8) :x))")))
  (is (= 8 (oracle "(defrecord Point [x y])
                     (defn main [] (get (map->Point {:x 7 :y 8}) :y))"))))

(deftest records-carry-a-deterministic-type-tag
  (is (= 1 (oracle "(defrecord Point [x y])
                     (defn main []
                       (if (= (get (->Point 1 2) :kotoba.record/type) :Point) 1 0))"))))

(deftest record-updates-remain-persistent-map-values
  (is (= 10 (oracle "(defrecord Point [x y])
                     (defn main []
                       (let [before (->Point 1 2)
                             after (assoc before :x 9)]
                         (+ (get before :x) (get after :x))))"))))

(deftest malformed-or-colliding-record-definitions-fail-closed
  (is (some? (rejection-message "(defrecord Point [x x]) (defn main [] 0)")))
  (is (some? (rejection-message "(defrecord Point [a b c d e f]) (defn main [] 0)")))
  (is (some? (rejection-message
              "(defrecord Point [x]) (defn ->Point [x] x) (defn main [] 0)"))))

(deftest record-constructors-agree-across-backends
  (let [source "(defrecord Point [x y]) (defn main [] (get (->Point 4 5) :y))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= #{5} (set (map #(:oracle-value (:kir %)) results))))))

;; ───────────────────────── named multi-arity functions ─────────────────────────

(deftest named-functions-dispatch-statically-by-arity
  (is (= 10 (oracle "(defn f ([] 10) ([x] x) ([x y] (+ x y)))
                      (defn main [] (f))")))
  (is (= 7 (oracle "(defn f ([] 10) ([x] x) ([x y] (+ x y)))
                     (defn main [] (f 7))")))
  (is (= 9 (oracle "(defn f ([] 10) ([x] x) ([x y] (+ x y)))
                     (defn main [] (f 4 5))"))))

(deftest named-multi-arity-functions-support-recursion
  (is (= 6 (oracle "(defn sum
                      ([xs] (sum 0 xs))
                      ([acc xs] (if (= xs 0) acc (sum (+ acc (pair-first xs)) (pair-second xs)))))
                     (defn main [] (sum [1 2 3]))"))))

(deftest named-multi-arity-functions-work-as-higher-order-callbacks
  (is (= 3 (oracle "(defn plus ([] 0) ([x] (+ x 1)) ([a b] (+ a b)))
                     (defn main [] (pair-first (map plus [2])))")))
  (is (= 6 (oracle "(defn plus ([] 0) ([x] (+ x 1)) ([a b] (+ a b)))
                     (defn main [] (reduce plus [1 2 3]))"))))

(deftest malformed-multi-arity-functions-fail-closed
  (is (some? (rejection-message "(defn f ([x] x) ([y] y)) (defn main [] 0)")))
  (is (some? (rejection-message "(defn f ([a b c d e f] a)) (defn main [] 0)")))
  (is (some? (rejection-message "(defn f ([x] x)) (defn main [] (f))"))))

(deftest zero-arity-main-may-use-multi-arity-source-shape
  (is (= 5 (oracle "(defn main ([] 5) ([x] x))"))))

(deftest multi-arity-expansion-is-reproducible-across-backends
  (let [source "(defn f ([] 1) ([x] (+ x 1))) (defn main [] (+ (f) (f 3)))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= #{5} (set (map #(:oracle-value (:kir %)) results))))
    (let [c1 (compiler/compile-source source :wasm32-kotoba-v1)
          c2 (compiler/compile-source source :wasm32-kotoba-v1)]
      (is (= (:kir c1) (:kir c2)))
      (is (java.util.Arrays/equals ^bytes (:bytes c1) ^bytes (:bytes c2))))))

;; ───────────────────────── protocols + record dispatch ─────────────────────────

(deftest protocols-dispatch-on-record-type-tags
  (is (= 10 (oracle "(defprotocol Area (area [this]))
                      (defrecord Rect [w h] Area
                        (area [this] (* (get this :w) (get this :h))))
                      (defrecord Square [side] Area
                        (area [this] (* (get this :side) (get this :side))))
                      (defn main [] (+ (area (->Rect 2 3)) (area (->Square 2))))"))))

(deftest protocol-methods-support-additional-arguments
  (is (= 15 (oracle "(defprotocol Scale (scale [this factor]))
                      (defrecord Amount [value] Scale
                        (scale [this factor] (* (get this :value) factor)))
                      (defn main [] (scale (->Amount 5) 3))"))))

(deftest protocol-dispatch-on-unknown-tag-fails-closed-to-zero
  (is (= 0 (oracle "(defprotocol Area (area [this]))
                     (defrecord Rect [w h] Area
                       (area [this] (* (get this :w) (get this :h))))
                     (defn main [] (area {:w 2 :h 3}))"))))

(deftest malformed-protocol-implementations-fail-closed
  (is (some? (rejection-message
              "(defprotocol P (f [this]))
               (defrecord R [x] Missing (f [this] 1)) (defn main [] 0)")))
  (is (some? (rejection-message
              "(defprotocol P (f [this x]))
               (defrecord R [x] P (f [this] 1)) (defn main [] 0)")))
  (is (some? (rejection-message
              "(defprotocol P (f [this]))
               (defrecord R [x] P (g [this] 1)) (defn main [] 0)"))))

(deftest protocol-dispatch-agrees-across-backends
  (let [source "(defprotocol Value (value [this]))
                (defrecord Box [x] Value (value [this] (get this :x)))
                (defn main [] (value (->Box 9)))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= #{9} (set (map #(:oracle-value (:kir %)) results))))))

(deftest extend-type-adds-protocol-implementation-outside-record
  (is (= 7 (oracle "(defprotocol Value (value [this]))
                     (defrecord Box [x])
                     (extend-type Box Value (value [this] (get this :x)))
                     (defn main [] (value (->Box 7)))"))))

(deftest extend-protocol-adds-multiple-type-sections
  (is (= 11 (oracle "(defprotocol Value (value [this]))
                      (defrecord A [x]) (defrecord B [x])
                      (extend-protocol Value
                        A (value [this] (get this :x))
                        B (value [this] (+ 1 (get this :x))))
                      (defn main [] (+ (value (->A 5)) (value (->B 5))))"))))

(deftest duplicate-protocol-extension-is-rejected
  (is (some? (rejection-message
              "(defprotocol Value (value [this]))
               (defrecord Box [x] Value (value [this] (get this :x)))
               (extend-type Box Value (value [this] 0))
               (defn main [] 0)"))))

(deftest extend-protocol-default-section-is-static-fallback
  (is (= 99 (oracle "(defprotocol Value (value [this]))
                      (defrecord Box [x])
                      (extend-protocol Value
                        Box (value [this] (get this :x))
                        default (value [this] 99))
                      (defn main [] (value {:not-a-record 1}))")))
  (is (= 7 (oracle "(defprotocol Value (value [this]))
                     (defrecord Box [x])
                     (extend-protocol Value
                       Box (value [this] (get this :x))
                       default (value [this] 99))
                     (defn main [] (value (->Box 7)))"))))

(deftest definterface-is-safe-static-method-contract
  (is (= 8 (oracle "(definterface Readable (read-value [this]))
                     (defrecord Box [x] Readable
                       (read-value [this] (get this :x)))
                     (defn main [] (read-value (->Box 8)))"))))

(deftest variadic-functions-specialize-rest-arity-to-pair-chain
  (is (= 0 (oracle "(defn rest-count [& xs] (count xs))
                     (defn main [] (rest-count))")))
  (is (= 3 (oracle "(defn rest-count [& xs] (count xs))
                     (defn main [] (rest-count 1 2 3))")))
  (is (= 9 (oracle "(defn choose [x & more] (+ x (nth more 1 0)))
                     (defn main [] (choose 4 2 5))"))))

(deftest fixed-arity-clause-wins-over-variadic-specialization
  (is (= 20 (oracle "(defn f ([x] 10) ([x & more] 20))
                      (defn main [] (f 1 2))")))
  (is (= 10 (oracle "(defn f ([x] 10) ([x & more] 20))
                      (defn main [] (f 1))"))))

(deftest variadic-functions-work-as-known-arity-callbacks
  (is (= 5 (oracle "(defn add-all [x & more] (+ x (nth more 0 0)))
                     (defn main [] (pair-first (map add-all [2] [3])))"))))

(deftest variadic-call-beyond-abi-limit-fails-closed
  (is (some? (rejection-message
              "(defn f [& xs] (count xs))
               (defn main [] (f 1 2 3 4 5 6))"))))

(deftest match-desugars-portably
  (is (= 3 (oracle "(defn main [] (match [1 2] [a b] (+ a b) :else 0))")))
  (is (= 9 (oracle "(defn main [] (match {:x 9} {:x n} n :else 0))")))
  (is (= 6 (oracle "(defn main [] (match [[1 2] 3] [[a b] c] (+ a b c) :else 0))")))
  (is (= 7 (oracle "(defn main [] (match [1] [a b] 0 :else 7))"))))

(deftest registered-pure-desugar-is-bounded-and-hygienic
  (is (= 10 (oracle "(defdesugar clamp [x lo hi]
                       (if (< x lo) lo (if (> x hi) hi x)))
                     (defn main [] (clamp 12 0 10))")))
  (is (= 4 (oracle "(defdesugar twice [x] (+ x x))
                     (defn one [] 1)
                     (defn main [] (twice (+ (one) 1)))")))
  (is (some? (rejection-message
              "(defdesugar unsafe [x] (cap-call x)) (defn main [] (unsafe 1))")))
  (is (some? (rejection-message
              "(defdesugar capture [x] (+ x ambient)) (defn main [] (capture 1))"))))

(deftest portable-string-values-have-a-fail-closed-byte-bound
  (is (= 3 (oracle "(defn main [] (string-length \"猫\"))")))
  (is (some? (rejection-message
              (str "(defn main [] \"" (apply str (repeat 128 "a")) "\")")))))
