(ns kotoba.compiler.frontend
  (:require [clojure.set :as set]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as rt]))

(def forbidden-heads
  '#{eval load load-file require use import ns-resolve resolve alter-var-root
     future pmap agent send send-off new . .. set! defmacro throw try catch
     locking dosync atom ref volatile!})

(def arithmetic '#{+ - * quot})
(def comparisons '#{= < > <= >=})
(def heap-operations '{pair 2 pair-first 1 pair-second 1})
(def list-operations '#{list cons first second rest empty?})
(def predicate-operations '#{not zero? pos? neg?})
;; ADR-2607150000: and/or/when mirror kotoba-lang/kotoba's already-proven
;; desugar-and/desugar-or (runtime.clj) -- ported here rather than reinvented,
;; closing the divergence ADR-2607141600/2607150000 identified between the
;; two independently-evolved grammars. get/assoc are new: map literals and
;; keyword literals desugar entirely to the EXISTING heap-pair/list
;; primitives (see desugar-map/keyword->i64 below) -- no backend/codegen
;; change anywhere, since `pair`/`pair-first`/`pair-second` were already
;; host-imported capabilities before this change (backend/wasm.clj), not
;; WASM-linear-memory-managed by the guest itself.
(def logical-operations '#{and or when})
(def map-operations '#{get assoc})
(def reserved-function-names
  (set/union forbidden-heads arithmetic comparisons (set (keys heap-operations))
             list-operations predicate-operations logical-operations map-operations
             '#{let if cap-call ns defn}))
(def max-functions 1024)
(def max-expression-nodes 50000)
(def max-lowered-nodes 100000)
(def max-bindings 4096)
(def max-parameters 5)
(def max-symbol-chars 128)
(def max-list-items 128)

(defn- check-reader-depth! [source]
  (loop [index 0 depth 0 in-string? false escaped? false in-comment? false]
    (when (< index (count source))
      (let [ch (.charAt ^String source index)]
        (cond
          in-comment? (recur (inc index) depth false false (not= ch \newline))
          (and in-string? escaped?) (recur (inc index) depth true false false)
          (and in-string? (= ch \\)) (recur (inc index) depth true true false)
          (= ch \newline) (recur (inc index) depth in-string? false false)
          (= ch \;) (recur (inc index) depth in-string? false (not in-string?))
          (= ch \") (recur (inc index) depth (not in-string?) false false)
          in-string? (recur (inc index) depth true false false)
          (#{\( \[ \{} ch)
          (let [next-depth (inc depth)]
            (when (> next-depth 512)
              (throw (ex-info "reader nesting exceeds admission limit" {:phase :read})))
            (recur (inc index) next-depth false false false))
          (#{\) \] \}} ch) (recur (inc index) (max 0 (dec depth)) false false false)
          :else (recur (inc index) depth false false false))))))

(defn read-forms [source]
  (when-not (string? source)
    (throw (ex-info "source must be a string" {:phase :read})))
  (when (> (count source) (* 1024 1024))
    (throw (ex-info "source exceeds 1 MiB admission limit" {:phase :read})))
  (when (re-find #"#=" source)
    (throw (ex-info "reader evaluation is forbidden" {:phase :read})))
  (check-reader-depth! source)
  (let [r (rt/string-push-back-reader source)]
    (loop [out []]
      (when (> (count out) 10000)
        (throw (ex-info "too many top-level forms" {:phase :read})))
      (let [x (try
                (reader/read {:read-cond :allow :features #{:kotoba} :eof ::eof} r)
                (catch Exception error
                  (throw (ex-info "source reader rejected input"
                                  {:phase :read} error))))]
        (if (= x ::eof) out (recur (conj out x)))))))

(defn- reject! [message form]
  (throw (ex-info message {:phase :subset :form form})))

(declare desugar-expr)

(defn- desugar-list [args form]
  (when (> (count args) max-list-items)
    (reject! "list item count exceeds admission limit" form))
  (reduce (fn [tail item] (list 'pair (desugar-expr item) tail))
          0 (reverse args)))

(defn- desugar-and
  "`(and a b c ...)` -> nested `let`/`if`, ported from kotoba-lang/kotoba's
  runtime.clj `desugar-and` (verified live there, ADR-2607150000): binds
  each argument's value once (never re-evaluated) and branches on it.
  `(and)` is vacuously truthy (1); `(and a)` is just a."
  [args]
  (cond
    (empty? args) 1
    (empty? (rest args)) (desugar-expr (first args))
    :else (let [tmp (gensym "and-tmp__")]
            (list 'let [tmp (desugar-expr (first args))]
                  (list 'if tmp (desugar-and (rest args)) tmp)))))

(defn- desugar-or
  "Mirror of desugar-and for `(or a b c ...)`. `(or)` is vacuously falsy
  (0); `(or a)` is just a."
  [args]
  (cond
    (empty? args) 0
    (empty? (rest args)) (desugar-expr (first args))
    :else (let [tmp (gensym "or-tmp__")]
            (list 'let [tmp (desugar-expr (first args))]
                  (list 'if tmp tmp (desugar-or (rest args)))))))

(defn- fnv1a-i64
  "Deterministic 64-bit FNV-1a hash of S's UTF-8 bytes, used to intern
  keyword literals as distinct i64 constants (ADR-2607150000). Not
  Clojure's own `hash` -- FNV-1a is a fixed, dependency-free algorithm
  whose output is reproducible forever, matching this compiler's byte-
  for-byte reproducibility gates (coverage_evidence.clj/release.clj).
  Collision probability for the realistically small keyword vocabulary of
  one .kotoba module is astronomically low but not proven zero -- a known,
  documented limitation, not eliminated."
  [^String s]
  (let [bs (.getBytes s "UTF-8")
        offset-basis -3750763034362895579  ; 0xcbf29ce484222325 as signed i64
        prime 1099511628211]                ; 0x100000001b3
    (reduce (fn [h b] (unchecked-multiply (bit-xor h (bit-and (long b) 0xff)) prime))
            offset-basis bs)))

(defn- keyword->i64 [kw] (fnv1a-i64 (str kw)))

(defn- desugar-map
  "`{:k1 v1 :k2 v2 ...}` -> a cons-list of `(pair key value)` pairs, reusing
  the EXISTING heap-pair/list primitives (`pair`/`pair-first`/`pair-second`)
  entirely -- no backend/codegen change (ADR-2607150000). Entries are
  sorted by the SOURCE TEXT of their key (`pr-str`, not the interned i64,
  which isn't computable for non-literal keys) for deterministic codegen
  regardless of Clojure's own map-literal iteration order (unspecified for
  >8 entries) -- required by this compiler's reproducible-build gates."
  [form]
  (when (> (count form) max-list-items)
    (reject! "map entry count exceeds admission limit" form))
  (let [entries (sort-by (fn [[k _]] (pr-str k)) (seq form))
        pairs (map (fn [[k v]] (list 'pair (desugar-expr k) (desugar-expr v))) entries)]
    (reduce (fn [tail item] (list 'pair item tail)) 0 (reverse pairs))))

(def ^:private map-get-helper-name '__kotoba_map_get)

(def ^:private map-get-helper
  "Compiler-synthesized recursive linear scan over a desugar-map cons-list,
  injected into a module's function set only when `get` is actually used
  (analyze's uses-map-get? scan) -- ADR-2607150000. Written directly in
  already-primitive form (if/=/pair-first/pair-second/self-call), not run
  through desugar-expr. Each recursive step costs 1 unit of this
  compiler's existing fixed 256-instruction-call fuel budget (ir.clj/
  backend/wasm.clj/core.clj's `default-fuel`/global fuel counter) -- a
  map lookup on a long map, or a miss, can exhaust it; not a new limit,
  the existing one now also bounds map-walk depth."
  {:name map-get-helper-name
   :params '[m k default]
   :result :i64
   :effects #{}
   :body '(if (= m 0)
            default
            (if (= (pair-first (pair-first m)) k)
              (pair-second (pair-first m))
              (__kotoba_map_get (pair-second m) k default)))})

(defn- uses-map-get? [form]
  (cond
    (seq? form) (or (= map-get-helper-name (first form)) (some uses-map-get? (rest form)))
    (coll? form) (some uses-map-get? form)
    :else false))

(defn- desugar-expr [form]
  (cond
    (keyword? form) (keyword->i64 form)
    (map? form) (desugar-map form)
    (not (seq? form)) form
    :else
    (let [[op & args] form]
      (case op
        list (desugar-list args form)
        cons (do (when-not (= 2 (count args)) (reject! "cons requires two operands" form))
                 (list 'pair (desugar-expr (first args)) (desugar-expr (second args))))
        first (do (when-not (= 1 (count args)) (reject! "first requires one operand" form))
                  (list 'pair-first (desugar-expr (first args))))
        second (do (when-not (= 1 (count args)) (reject! "second requires one operand" form))
                   (list 'pair-first (list 'pair-second (desugar-expr (first args)))))
        rest (do (when-not (= 1 (count args)) (reject! "rest requires one operand" form))
                 (list 'pair-second (desugar-expr (first args))))
        empty? (do (when-not (= 1 (count args)) (reject! "empty? requires one operand" form))
                   (list '= (desugar-expr (first args)) 0))
        not (do (when-not (= 1 (count args)) (reject! "not requires one operand" form))
                (list '= (desugar-expr (first args)) 0))
        zero? (do (when-not (= 1 (count args)) (reject! "zero? requires one operand" form))
                  (list '= (desugar-expr (first args)) 0))
        pos? (do (when-not (= 1 (count args)) (reject! "pos? requires one operand" form))
                 (list '> (desugar-expr (first args)) 0))
        neg? (do (when-not (= 1 (count args)) (reject! "neg? requires one operand" form))
                 (list '< (desugar-expr (first args)) 0))
        and (desugar-and args)
        or (desugar-or args)
        when (do (when-not (= 2 (count args))
                   (reject! "when requires a test and exactly one result expression (this profile has no `do`, unlike kotoba-lang/kotoba's)" form))
                 (list 'if (desugar-expr (first args)) (desugar-expr (second args)) 0))
        get (do (when-not (<= 2 (count args) 3)
                  (reject! "get requires a map, a key, and an optional default" form))
                (let [[m k default] args]
                  (list map-get-helper-name (desugar-expr m) (desugar-expr k)
                        (if (some? default) (desugar-expr default) 0))))
        assoc (do (when-not (and (>= (count args) 3) (odd? (count args)))
                    (reject! "assoc requires a map followed by one or more key/value pairs" form))
                  (let [[m & kvs] args]
                    (reduce (fn [acc-map [k v]]
                              (list 'pair (list 'pair (desugar-expr k) (desugar-expr v)) acc-map))
                            (desugar-expr m)
                            (partition 2 kvs))))
        (apply list op (map desugar-expr args))))))

(defn- valid-name? [value]
  (and (simple-symbol? value) (<= (count (name value)) max-symbol-chars)))

(defn- charge-node! [budget form]
  (when (> (vswap! budget inc) max-expression-nodes)
    (reject! "program expression budget exhausted" form)))

(declare validate-expr)

(defn- validate-bindings [bindings locals functions depth budget]
  (when-not (and (vector? bindings) (even? (count bindings)))
    (reject! "let requires an even binding vector" bindings))
  (when-not (= (count (take-nth 2 bindings)) (count (distinct (take-nth 2 bindings))))
    (reject! "duplicate let binding" bindings))
  (when (> (quot (count bindings) 2) max-bindings)
    (reject! "let binding count exceeds admission limit" bindings))
  (loop [pairs (partition 2 bindings) env locals]
    (if-let [[name value] (first pairs)]
      (do
        (when-not (and (valid-name? name) (not (contains? forbidden-heads name)))
          (reject! "invalid local binding" name))
        (validate-expr value env functions (inc depth) budget)
        (recur (next pairs) (conj env name)))
      env)))

(defn validate-expr [form locals functions depth budget]
  (charge-node! budget form)
  (when (> depth 256)
    (reject! "expression nesting exceeds admission limit" form))
  (cond
    (integer? form) (if (<= Long/MIN_VALUE form Long/MAX_VALUE) form
                        (reject! "integer literal is outside i64" form))
    (symbol? form) (if (contains? locals form) form
                       (reject! "unbound or dynamic symbol is forbidden" form))
    (seq? form)
    (let [[op & args] form]
      (when-not (simple-symbol? op) (reject! "computed or namespaced calls are forbidden" form))
      (when (or (contains? forbidden-heads op) (re-find #"[.]" (name op)))
        (reject! "dynamic loading, interop, mutation, and metaprogramming are forbidden" form))
      (cond
        (= op 'let)
        (let [[bindings & body] args]
          (when-not (= 1 (count body)) (reject! "let requires one result expression" form))
          (validate-expr (first body)
                         (validate-bindings bindings locals functions depth budget)
                         functions (inc depth) budget))

        (= op 'if)
        (do (when-not (= 3 (count args)) (reject! "if requires test, then, else" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (= op 'cap-call)
        (let [[cap-id value :as call-args] args]
          (when-not (and (= 2 (count call-args)) (integer? cap-id) (<= 0 cap-id 255))
            (reject! "cap-call requires a literal capability id in [0,255] and one value" form))
          (validate-expr value locals functions (inc depth) budget))

        (contains? arithmetic op)
        (do (when (or (empty? args) (and (= op 'quot) (not= 2 (count args))))
              (reject! "invalid arithmetic arity" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? comparisons op)
        (do (when-not (= 2 (count args)) (reject! "comparison requires two operands" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? heap-operations op)
        (do (when-not (= (get heap-operations op) (count args))
              (reject! "heap operation arity mismatch" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? functions op)
        (let [expected (count (get functions op))]
          (when-not (= expected (count args))
            (reject! "function call arity mismatch" form))
          (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        :else (reject! "operation has no admitted lowering" form))
      form)
    :else (reject! "value type is outside the safe profile" form)))

(defn- direct-facts [form function-names]
  (let [effects (volatile! #{}) calls (volatile! #{})]
    (letfn [(walk [x]
              (cond
                (seq? x)
                (let [[op & args] x]
                  (cond
                    (= op 'cap-call)
                    (do (vswap! effects conj [:cap/call (first args)])
                        (walk (second args)))
                    (contains? function-names op)
                    (do (vswap! calls conj op) (doseq [arg args] (walk arg)))
                    :else (doseq [arg args] (walk arg))))
                (coll? x) (doseq [item x] (walk item))))]
      (walk form)
      {:effects @effects :calls @calls})))

(defn- infer-effects [functions]
  (let [names (set (map :name functions))
        direct (into {} (map (fn [{:keys [name body]}]
                               [name (direct-facts body names)])) functions)]
    (loop [inferred (into {} (map (fn [[name facts]] [name (:effects facts)]) direct))]
      (let [next-effects
            (into {} (map (fn [[name {direct-effects :effects calls :calls}]]
                            [name (reduce set/union direct-effects
                                          (map #(get inferred % #{}) calls))])
                          direct))]
        (if (= inferred next-effects) inferred (recur next-effects))))))

(defn- bounded-sum [values]
  (reduce (fn [total value]
            (min (inc max-lowered-nodes) (+ total value)))
          0 values))

(defn- lowered-cost [form env]
  (cond
    (integer? form) 1
    (symbol? form) (get env form 1)
    :else
    (let [[op & args] form]
      (if (= op 'let)
        (let [[bindings body] args
              env' (reduce (fn [current [name value]]
                             (assoc current name (lowered-cost value current)))
                           env (partition 2 bindings))]
          (lowered-cost body env'))
        (bounded-sum (cons 1 (map #(lowered-cost % env) args)))))))

(defn- check-lowering-budget! [functions]
  (let [cost (bounded-sum (map #(lowered-cost (:body %) {}) functions))]
    (when (> cost max-lowered-nodes)
      (reject! "lowered program budget exhausted" cost))))

(defn analyze [source]
  (let [forms (read-forms source)
        namespaces (filter #(and (seq? %) (= 'ns (first %))) forms)
        defs (filter #(and (seq? %) (= 'defn (first %))) forms)
        other (remove #(or (and (seq? %) (= 'ns (first %)))
                           (and (seq? %) (= 'defn (first %)))) forms)
        _ (when (> (count defs) max-functions)
            (reject! "function count exceeds admission limit" (count defs)))
        _ (when (or (> (count namespaces) 1)
                    (some #(not (and (= 2 (count %)) (symbol? (second %))
                                     (<= (count (str (second %))) max-symbol-chars)))
                          namespaces))
            (reject! "ns must contain exactly one namespace symbol" namespaces))
        parsed (mapv (fn [form]
                       (let [[_ name params & body] form]
                         (when-not (valid-name? name) (reject! "invalid function name" name))
                         (when (contains? reserved-function-names name)
                           (reject! "reserved function name" name))
                         (when-not (and (vector? params) (every? valid-name? params)
                                        (= (count params) (count (distinct params)))
                                        (<= (count params) max-parameters))
                           (reject! "function parameters must be unique bounded symbols with ABI-supported arity" params))
                         (when-not (= 1 (count body))
                           (reject! "function must contain one result expression" body))
                         {:name name :params params :result :i64 :effects #{}
                          :body (desugar-expr (first body))}))
                     defs)
        ;; ADR-2607150000: inject the synthesized `get` helper only when a
        ;; desugared body actually calls it -- keeps modules that never use
        ;; `get` byte-identical to before this change. A user `defn` that
        ;; collides with the helper's reserved name is caught for free by
        ;; the existing :duplicate-function-name check below (signatures'
        ;; map semantics silently drop one entry, count mismatch trips it).
        parsed (cond-> parsed
                 (some #(uses-map-get? (:body %)) parsed) (conj map-get-helper))
        signatures (into {} (map (juxt :name :params) parsed))]
    (when (seq other) (reject! "only ns and defn are allowed at top level" (first other)))
    (when (empty? parsed) (reject! "at least one defn is required" forms))
    (when-not (= (count parsed) (count signatures)) (reject! "duplicate function name" defs))
    (when-not (contains? signatures 'main) (reject! "main entrypoint is required" defs))
    (when-not (empty? (get signatures 'main)) (reject! "main must take zero arguments" 'main))
    (let [budget (volatile! 0)]
      (doseq [{:keys [params body]} parsed]
        (validate-expr body (set params) signatures 0 budget)))
    (check-lowering-budget! parsed)
    (let [function-effects (infer-effects parsed)
          functions (mapv #(assoc % :effects (get function-effects (:name %))) parsed)]
      {:format :kotoba.hir/v2 :entry 'main :result :i64
       ;; Every function is exported by current backends, so admission covers
       ;; the union rather than only effects reachable from main.
       :effects (reduce set/union #{} (vals function-effects))
       :functions functions})))
