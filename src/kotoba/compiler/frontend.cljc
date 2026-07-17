(ns kotoba.compiler.frontend
  ;; `clojure.set` is required on both runtimes, so `:require` is never
  ;; empty -- an empty `(:require)` (what results if EVERY item inside it
  ;; were individually `#?()`-conditional and none matched) fails ns-form
  ;; spec validation (confirmed live; see `kotoba.compiler.ir`'s ns form
  ;; for the fuller explanation). `#?@` (splicing) rather than `#?` here
  ;; because each branch below is more than one require-spec.
  (:require [clojure.set :as set]
            #?@(:clj [[clojure.tools.reader :as reader]
                      [clojure.tools.reader.reader-types :as rt]]
                :cljs [[kotoba.compiler.kotoba-reader :as kr]
                       [kotoba.compiler.cljs-i64 :as i64]])))

(def forbidden-heads
  '#{eval load load-file require use import ns-resolve resolve alter-var-root
     future pmap agent send send-off new . .. set! defmacro throw try catch
     locking dosync atom ref volatile!})

(def arithmetic '#{+ - * quot bit-xor bit-and})
(def comparisons '#{= < > <= >=})
(def heap-operations '{pair 2 pair-first 1 pair-second 1})
(def kernel-memory-operations
  '{kernel-load-u8 3 kernel-load-u8-4k 3 kernel-load-u8-16k 3
    kernel-store-u8 4 kernel-store-u8-4k 4})
(def kernel-privileged-operations
  '{kernel-boot-info 0 kernel-read-cr2 0 kernel-read-cr3 0 kernel-write-cr3 1 kernel-invlpg 1
    kernel-cli 0 kernel-sti 0 kernel-hlt 0 kernel-pause 0
    kernel-out-u8 2 kernel-out-u32 2})
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
;; ADR-2607180900 L2: `do` is surface sugar that desugars to nested `let`
;; (discard non-final expressions by binding gensym temps). Admitted in
;; reserved-function-names so authors cannot define a conflicting `do`.
(def sequencing-operations '#{do})
(def reserved-function-names
  (set/union forbidden-heads arithmetic comparisons (set (keys heap-operations))
             (set (keys kernel-memory-operations))
             (set (keys kernel-privileged-operations))
             list-operations predicate-operations logical-operations map-operations
             sequencing-operations
             '#{let if cap-call ns defn}))
(def max-functions 1024)
(def max-expression-nodes 50000)
(def max-lowered-nodes 100000)
(def max-bindings 4096)
(def max-parameters 5)
(def max-symbol-chars 128)
(def max-list-items 128)

(defn- kotoba-integer?
  "True for a value that is (or stands for) a `.kotoba` integer literal --
  plain `integer?` on `:clj`. On `:cljs` a literal may be EITHER a JS
  `bigint` (read from `.kotoba` source via `kotoba.compiler.kotoba-reader`)
  OR a plain cljs number (synthesized directly by this namespace's own
  desugaring, e.g. `desugar-and`'s vacuous `1`, `when`'s trailing `0`, `get`'s
  default `0` -- ordinary Clojure literals in THIS file's source, never
  routed through the reader) -- both are valid until
  `kotoba.compiler.ir/eval-expr` coerces every literal to `bigint` via
  `cljs-i64/->bigint` at the single point it enters the runtime value
  stream. Plain cljs `integer?`/`int?` do not recognize `bigint`
  (confirmed live), so this checks both forms explicitly."
  [form]
  #?(:clj (integer? form) :cljs (or (i64/bigint-value? form) (integer? form))))

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
  #?(:clj
     (let [r (rt/string-push-back-reader source)]
       (loop [out []]
         (when (> (count out) 10000)
           (throw (ex-info "too many top-level forms" {:phase :read})))
         (let [x (try
                   (reader/read {:read-cond :allow :features #{:kotoba} :eof ::eof} r)
                   (catch Exception error
                     (throw (ex-info "source reader rejected input"
                                     {:phase :read} error))))]
           (if (= x ::eof) out (recur (conj out x))))))
     ;; kotoba-reader (kr) is a purpose-built substitute for the JVM-only
     ;; clojure.tools.reader -- see its own ns docstring for why
     ;; cljs.tools.reader (the nominal ClojureScript sibling) isn't used
     ;; instead. It parses the whole source in one pass rather than one form
     ;; at a time, so the >10000 admission check runs post-hoc here instead
     ;; of per-iteration -- equivalent outcome, same 1 MiB source cap already
     ;; bounds how large that one pass can be.
     :cljs
     (let [out (try
                 (kr/read-forms source)
                 (catch :default error
                   (throw (ex-info "source reader rejected input"
                                   {:phase :read} error))))]
       (when (> (count out) 10000)
         (throw (ex-info "too many top-level forms" {:phase :read})))
       out)))

(defn- reject! [message form]
  (throw (ex-info message {:phase :subset :form form})))

(declare desugar-expr form-free-symbols replace-recur)

;; ADR-2607150000: bound (via `binding`) around each top-level defn's
;; desugaring pass in `analyze`, to an atom `loop`'s desugar-expr case
;; conjoins synthesized helper-function definitions onto -- the same
;; "collect synthesized functions on the side, inject them into `parsed`
;; afterward" pattern `map-get-helper`/`uses-map-get?` already established,
;; generalized to support MULTIPLE, uniquely-named helpers per defn (one
;; per `loop` occurrence) rather than one fixed shared name.
(def ^:dynamic *pending-loop-helpers* nil)

;; ADR-2607150000: loop-helper names are NOT `gensym` -- unlike and/or's
;; gensym'd `let`-local temp names (erased into numeric WASM local indices,
;; never appearing in the compiled bytes -- confirmed by repeated-compile
;; byte comparison), a loop-helper becomes a real EXPORTED top-level
;; function ("every function is exported by current backends," see
;; analyze's :effects comment below), so its NAME is literally baked into
;; the WASM export section. `gensym` uses a JVM-process-wide monotonic
;; counter, so two compiles of the IDENTICAL source in the same process
;; would get DIFFERENT loop-helper names and therefore DIFFERENT bytes --
;; confirmed empirically (compiled the same loop-using source twice in one
;; process: bytes differed). This compiler's own reproducible-build gates
;; (coverage_evidence.clj/release.clj) require byte-for-byte determinism,
;; so loop-helpers instead get a sequential name from *loop-counter*,
;; bound ONCE per `analyze` call (not per-defn) so the same source always
;; encounters `loop` forms in the same left-to-right desugaring order and
;; always assigns the same names.
(def ^:dynamic *loop-counter* nil)

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

(defn- desugar-do
  "ADR-2607180900 L2: `(do a b c)` -> nested `let` that evaluates each
  non-final expression for effects (none in pure KIR today) and returns
  the last. No new backend op — pure desugar to `let`/`if` primitives.
  `(do)` is 0; `(do a)` is a."
  [args]
  (cond
    (empty? args) 0
    (empty? (rest args)) (desugar-expr (first args))
    :else (let [tmp (gensym "do-tmp__")]
            (list 'let [tmp (desugar-expr (first args))]
                  (desugar-do (rest args))))))

(defn- fnv1a-i64
  "Deterministic 64-bit FNV-1a hash of S's UTF-8 bytes, used to intern
  keyword literals as distinct i64 constants (ADR-2607150000). Not
  Clojure's own `hash` -- FNV-1a is a fixed, dependency-free algorithm
  whose output is reproducible forever, matching this compiler's byte-
  for-byte reproducibility gates (coverage_evidence.clj/release.clj).
  Collision probability for the realistically small keyword vocabulary of
  one .kotoba module is astronomically low but not proven zero -- a known,
  documented limitation, not eliminated."
  [s]
  #?(:clj
     (let [bs (.getBytes ^String s "UTF-8")
           offset-basis -3750763034362895579  ; 0xcbf29ce484222325 as signed i64
           prime 1099511628211]                ; 0x100000001b3
       (reduce (fn [h b] (unchecked-multiply (bit-xor h (bit-and (long b) 0xff)) prime))
               offset-basis bs))
     :cljs
     ;; Same FNV-1a algorithm, over JS bigint instead of JVM long, so a
     ;; keyword interned on this path hashes to the IDENTICAL i64 constant
     ;; the JVM path would emit for the same keyword (this compiler's own
     ;; reproducible-build gates require byte-for-byte identical output).
     (let [bs (js/TextEncoder.)
           bytes (.encode bs s)
           offset-basis (js/BigInt "-3750763034362895579")
           prime (js/BigInt "1099511628211")]
       (reduce (fn [h b]
                 (i64/wrap-i64 (* (i64/wrap-i64 (bit-xor h (js/BigInt b))) prime)))
               offset-basis (js/Array.from bytes)))))

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
  ;; #?(:cljs ...): a plain `pr-str` on a `bigint` key prints as
  ;; "#object[BigInt 5]" (confirmed live), not "5" like the JVM path's Long
  ;; -- diverging sort order between an integer key and e.g. a keyword key
  ;; in the same map literal. `.toString()` on bigint gives the identical
  ;; plain-digit form Long's own `pr-str` produces, so entries sort
  ;; byte-identically to the JVM path regardless of key type mix.
  (let [entries (sort-by (fn [[k _]]
                            #?(:clj (pr-str k)
                               :cljs (if (i64/bigint-value? k) (.toString k) (pr-str k))))
                          (seq form))
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

(def ^:private map-without-helper-name '__kotoba_map_without)

(def ^:private map-without-helper
  "Compiler-synthesized recursive filter over a desugar-map cons-list,
  removing every entry whose key equals `k` while preserving the relative
  order of the rest -- `assoc`'s desugar (below) calls this on the OLD map
  before prepending the new pair, so re-`assoc`-ing an existing key no
  longer leaves the old entry as dead weight (a real bug found and fixed
  in the same ADR-2607150000 line of work that first landed assoc: the
  original desugar only ever prepended, `get`'s first-match-wins scan made
  the RESULT correct but the map grew without bound under repeated
  re-assoc of the same key). Injected only when `assoc` is actually used
  (`uses-map-without?`), same pattern as `map-get-helper`. Recurses through
  the WHOLE tail even after a match (not just the first hit) so any
  pre-existing duplicate from before this fix, or a hand-built map literal
  with a repeated key, is fully cleaned up, not just shadowed once more."
  {:name map-without-helper-name
   :params '[m k]
   :result :i64
   :effects #{}
   :body '(if (= m 0)
            0
            (if (= (pair-first (pair-first m)) k)
              (__kotoba_map_without (pair-second m) k)
              (pair (pair-first m) (__kotoba_map_without (pair-second m) k))))})

(defn- uses-map-without? [form]
  (cond
    (seq? form) (or (= map-without-helper-name (first form)) (some uses-map-without? (rest form)))
    (coll? form) (some uses-map-without? form)
    :else false))

(defn- nth-pair-second
  "N nested `pair-second`s around EXPR (0 => expr itself) -- the pair-chain
  position N steps past the head, used by both vector destructuring
  (below) and vector-as-data indexing."
  [expr n]
  (nth (iterate (fn [e] (list 'pair-second e)) expr) n))

(defn- destructure-binding
  "Expands ONE `[pattern value-expr]` `let`/`defn`-param binding into a flat
  seq of `[symbol expr]` pairs (ADR-2607150000). PATTERN is a plain symbol
  (kept as-is, 1 pair), a positional vector `[a b & rest]` (pair-chain
  destructuring via pair-first/pair-second, 1 pair per named/rest symbol),
  or a map `{:keys [a b]}` (association-list destructuring via the `get`
  special form, 1 pair per key). VALUE-EXPR must already be desugared --
  callers desugar it once; every pattern binds a single gensym'd temp to it
  first so it's never re-evaluated. One level only: a pattern NESTED inside
  a vector/map pattern is not itself recursively destructured -- a real,
  documented scope limit, not silently ignored (rejected below if written)."
  [pattern value-expr]
  (cond
    (symbol? pattern) [[pattern value-expr]]

    (vector? pattern)
    (let [tmp (gensym "destr-vec__")
          [positional rest-part] (split-with (complement #{'&}) pattern)]
      (when-not (every? symbol? positional)
        (reject! "vector destructuring supports only flat (one-level) symbol patterns" pattern))
      (when (and (seq rest-part) (or (not= 2 (count rest-part)) (not (symbol? (second rest-part)))))
        (reject! "`&` in vector destructuring must be followed by exactly one rest-binding symbol" pattern))
      (into [[tmp value-expr]]
            (concat
             (map-indexed (fn [i name] [name (list 'pair-first (nth-pair-second tmp i))]) positional)
             (when-let [rest-name (second rest-part)]
               [[rest-name (nth-pair-second tmp (count positional))]]))))

    (map? pattern)
    (let [keys-vec (:keys pattern)]
      (when-not (and (= 1 (count pattern)) keys-vec (vector? keys-vec) (every? symbol? keys-vec))
        (reject! "map destructuring supports only {:keys [...]} (no :or/:as/:strs)" pattern))
      (let [tmp (gensym "destr-map__")]
        (into [[tmp value-expr]]
              ;; Builds the same ALREADY-DESUGARED shape as the `get` case
              ;; in desugar-expr's case dispatch below
              ;; (`(map-get-helper-name m k default)`), not the sugared
              ;; `(get m k)` source form -- this generated call is never
              ;; routed back through desugar-expr, so it must already be in
              ;; its final form: both a bare `(get ...)` op (unresolvable,
              ;; "operation has no admitted lowering") and a raw keyword key
              ;; (unrepresentable at runtime) were caught live before this
              ;; fix.
              (map (fn [k] [k (list map-get-helper-name tmp (keyword->i64 (keyword k)) 0)]) keys-vec))))

    :else (reject! "unsupported destructuring pattern" pattern)))

(defn- form-free-symbols
  "Symbols FORM references as VALUES (never call-heads) that aren't in
  BOUND -- a purely syntactic free-variable scan for `loop`'s closure
  conversion (see desugar-expr's `loop` case). Operates on an ALREADY
  DESUGARED form, so every `let` it sees has plain-symbol bindings only
  (destructuring has already been expanded away by this point) -- no need
  to reason about vector/map patterns here."
  [form bound]
  (cond
    (symbol? form) (if (contains? bound form) #{} #{form})
    (seq? form)
    (let [[op & args] form]
      (if (= op 'let)
        (let [[bindings & body] args]
          (loop [pairs (partition 2 bindings) bound bound acc #{}]
            (if-let [[name value] (first pairs)]
              (recur (next pairs) (conj bound name) (set/union acc (form-free-symbols value bound)))
              (apply set/union acc (map #(form-free-symbols % bound) body)))))
        (apply set/union #{} (map #(form-free-symbols % bound) args))))
    (coll? form) (apply set/union #{} (map #(form-free-symbols % bound) form))
    :else #{}))

(defn- replace-recur
  "Walks the already-desugared loop BODY, replacing every `(recur a b ...)`
  with a call to HELPER-NAME carrying the new loop-binding values (A/B/...)
  followed by CAPTURED's outer-variable values UNCHANGED (they never vary
  across iterations, only the loop's own bindings do). A `recur` belonging
  to a NESTED loop is never seen here -- desugar-expr's `loop` case already
  resolved it (into an ordinary call to ITS OWN gensym'd helper) as part of
  desugaring this loop's body, before replace-recur ever runs."
  [form helper-name loop-names captured]
  (cond
    (seq? form)
    (let [[op & args] form]
      (if (= op 'recur)
        (do (when-not (= (count args) (count loop-names))
              (reject! "recur argument count must match loop bindings" form))
            (list* helper-name (concat args captured)))
        (cons op (map #(replace-recur % helper-name loop-names captured) args))))
    :else form))

(defn- desugar-expr [form]
  (cond
    (keyword? form) (keyword->i64 form)
    (map? form) (desugar-map form)
    ;; ADR-2607150000: vector-as-data reuses desugar-list's pair-chain
    ;; encoding verbatim -- a vector and a `(list ...)` call become
    ;; identical runtime representations, matching this language's
    ;; existing "no runtime type tags" design. Safe to dispatch generically
    ;; here because `let`'s OWN bindings vector never reaches this branch:
    ;; the `let` case below fully owns processing it directly (via
    ;; destructure-binding) and never routes it back through desugar-expr
    ;; as a bare value. `defn` params are consumed entirely inside
    ;; `analyze`, before any desugar-expr call, for the same reason.
    (vector? form) (desugar-list (seq form) form)
    (not (seq? form)) form
    :else
    (let [[op & args] form]
      (case op
        list (desugar-list args form)

        ;; ADR-2607150000: `let` gets its own case (previously handled only
        ;; by the generic default case below) for two reasons: (1) bug fix
        ;; -- the default case's `(map desugar-expr args)` called
        ;; desugar-expr on the WHOLE bindings vector as one opaque arg;
        ;; since vectors aren't `seq?`, it passed through UNCHANGED,
        ;; silently skipping map/keyword/nested-vector desugaring inside
        ;; binding VALUES (`(let [m {:a 1}] (get m :a))` failed with
        ;; "value type is outside the safe profile" before this fix,
        ;; confirmed live). (2) destructuring: each binding's PATTERN may
        ;; now be a vector `[a b & rest]` or a map `{:keys [a b]}`, not
        ;; just a plain symbol (destructure-binding above expands either
        ;; into flat symbol bindings). Malformed bindings (not an even
        ;; vector) pass through unchanged so validate-expr's own existing
        ;; "let requires an even binding vector" check still fires with
        ;; its original, clearer error.
        let (let [[bindings & body] args]
              (list* 'let
                     (if (and (vector? bindings) (even? (count bindings)))
                       ;; destructure-binding returns a seq of [name value]
                       ;; pairs per pattern; mapcat over patterns yields a
                       ;; seq OF PAIRS (not yet flat), so a second `mapcat
                       ;; identity` is needed to splice each pair's two
                       ;; elements into the flat alternating binding vector
                       ;; `let` itself expects.
                       (vec (mapcat identity
                                    (mapcat (fn [[pattern value]]
                                              (destructure-binding pattern (desugar-expr value)))
                                            (partition 2 bindings))))
                       bindings)
                     ;; `mapv`, not bare `map`: `list*`'s tail argument is
                     ;; never forced by `list*` itself, so a lazy `map`
                     ;; result here would defer these desugar-expr calls
                     ;; past the end of whatever *loop-counter*/
                     ;; *pending-loop-helpers* `binding` this `let` case is
                     ;; nested inside -- confirmed live as an NPE ("Cannot
                     ;; invoke Volatile.deref() ... is null") when a `loop`
                     ;; inside a `let` body was first forced later, by
                     ;; `uses-map-get?`'s post-analyze tree walk, long
                     ;; after `analyze`'s `binding` had already exited.
                     (mapv desugar-expr body)))

        ;; ADR-2607150000: `loop`/`recur` desugars to a compiler-synthesized
        ;; recursive helper function (like `get`'s __kotoba_map_get, but
        ;; freshly gensym'd per loop occurrence rather than one shared fixed
        ;; name) -- no backend/codegen change, since ordinary recursive
        ;; `defn` calls already work and are already fuel-metered. Any free
        ;; variable the loop body references from its ENCLOSING scope is
        ;; captured as an EXTRA helper parameter (form-free-symbols does a
        ;; purely syntactic scan -- no environment lookup needed: an
        ;; over/under-capture mistake still fails SAFELY later, as a hard
        ;; :unbound-symbol or arity-mismatch compile error from validate-expr,
        ;; never silently wrong runtime behavior). Threading the captured
        ;; values through recur unchanged is handled by replace-recur.
        loop
        (let [[bindings & body] args]
          (when-not (and (vector? bindings) (even? (count bindings))
                         (every? symbol? (take-nth 2 bindings)))
            (reject! "loop requires an even vector of plain-symbol bindings (destructure inside the body instead)" form))
          (when-not (= 1 (count body))
            (reject! "loop requires exactly one body expression (this profile has no `do`)" form))
          (let [loop-names (vec (take-nth 2 bindings))
                loop-inits (mapv desugar-expr (take-nth 2 (rest bindings)))
                desugared-body (desugar-expr (first body))
                captured (vec (sort-by str (form-free-symbols desugared-body (set loop-names))))
                helper-name (symbol (str "__kotoba_loop_" (vswap! *loop-counter* inc)))
                helper-params (into loop-names captured)]
            (when (> (count helper-params) max-parameters)
              (reject! "loop bindings plus captured outer variables exceed this compiler's ABI-supported arity" form))
            (when *pending-loop-helpers*
              (swap! *pending-loop-helpers* conj
                     {:name helper-name :params helper-params :result :i64 :effects #{}
                      :body (replace-recur desugared-body helper-name loop-names captured)}))
            (list* helper-name (concat loop-inits captured))))
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
        do (desugar-do args)
        ;; Multi-body when is now admitted (ADR-2607180900): desugars via `do`.
        when (do (when (empty? args)
                   (reject! "when requires a test expression" form))
                 (let [[test & body] args]
                   (list 'if (desugar-expr test)
                         (desugar-do body)
                         0)))
        get (do (when-not (<= 2 (count args) 3)
                  (reject! "get requires a map, a key, and an optional default" form))
                (let [[m k default] args]
                  (list map-get-helper-name (desugar-expr m) (desugar-expr k)
                        (if (some? default) (desugar-expr default) 0))))
        assoc (do (when-not (and (>= (count args) 3) (odd? (count args)))
                    (reject! "assoc requires a map followed by one or more key/value pairs" form))
                  (let [[m & kvs] args]
                    (reduce (fn [acc-map [k v]]
                              ;; ADR-2607150000: remove any existing entry
                              ;; for this key via __kotoba_map_without
                              ;; BEFORE prepending the new pair, so re-
                              ;; assoc-ing an existing key doesn't grow the
                              ;; map unboundedly. k/v are let-bound once
                              ;; (gensym'd LET-LOCAL temp names -- safe,
                              ;; erased to WASM local indices, same
                              ;; reasoning as and/or's temps) so the key
                              ;; expression is evaluated exactly once
                              ;; despite being referenced twice (removal
                              ;; scan + the new pair itself).
                              (let [k-sym (gensym "assoc-k__")
                                    v-sym (gensym "assoc-v__")]
                                (list 'let [k-sym (desugar-expr k) v-sym (desugar-expr v)]
                                      (list 'pair (list 'pair k-sym v-sym)
                                            (list map-without-helper-name acc-map k-sym)))))
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
    (kotoba-integer? form)
    #?(:clj (if (<= Long/MIN_VALUE form Long/MAX_VALUE) form
                (reject! "integer literal is outside i64" form))
       :cljs (if (i64/in-i64-range? form) form
                 (reject! "integer literal is outside i64" form)))
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
          (when-not (and (= 2 (count call-args)) (kotoba-integer? cap-id) (<= 0 cap-id 255))
            (reject! "cap-call requires a literal capability id in [0,255] and one value" form))
          (validate-expr value locals functions (inc depth) budget))

        (contains? arithmetic op)
            (do (when (or (empty? args) (and (contains? '#{quot bit-xor bit-and} op) (not= 2 (count args))))
              (reject! "invalid arithmetic arity" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? comparisons op)
        (do (when-not (= 2 (count args)) (reject! "comparison requires two operands" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? heap-operations op)
        (do (when-not (= (get heap-operations op) (count args))
              (reject! "heap operation arity mismatch" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? kernel-memory-operations op)
        (do (when-not (= (get kernel-memory-operations op) (count args))
              (reject! "kernel memory operation arity mismatch" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? kernel-privileged-operations op)
        (do (when-not (= (get kernel-privileged-operations op) (count args))
              (reject! "kernel privileged operation arity mismatch" form))
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
    (kotoba-integer? form) 1
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

(defn- param-name+wrap
  "For one `defn` PARAM: a plain symbol is kept as-is (identity wrap). A
  vector/map destructuring pattern (ADR-2607150000) gets a fresh gensym'd
  parameter name, plus a body-wrapping fn that binds the pattern from that
  gensym via a `let` -- reusing desugar-expr's own `let`-destructuring
  (above) rather than duplicating it: `(defn f [{:keys [a]}] body)`
  becomes params `[tmp]`, body `(let [{:keys [a]} tmp] body)`, which then
  goes through desugar-expr exactly like any other `let`. Returns
  `[param-symbol wrap-fn]`."
  [param]
  (if (symbol? param)
    [param identity]
    (let [tmp (gensym "param-destr__")]
      [tmp (fn [body] (list 'let [param tmp] body))])))

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
        ;; ADR-2607150000: mapcat, not mapv -- a defn using `loop` may
        ;; expand into itself PLUS one or more synthesized loop-helper
        ;; functions (collected via *pending-loop-helpers*, bound fresh
        ;; per defn so helpers from one function's loops never leak into
        ;; another's). defn PARAMS may now be destructuring patterns
        ;; (param-name+wrap above), not just plain symbols. *loop-counter*
        ;; is bound ONCE for the whole source (not per-defn) so loop-helper
        ;; names stay unique across every defn, not just within one.
        parsed (binding [*loop-counter* (volatile! 0)]
               ;; `vec` (forcing) must stay INSIDE `binding`'s dynamic
               ;; extent: `mapcat` is lazy, so `(vec (binding [...]
               ;; (mapcat ...)))` would rebind *loop-counter* only around
               ;; building the (unrealized) lazy seq, then unbind it before
               ;; `vec` actually forces each element -- confirmed live as an
               ;; NPE (`*loop-counter*` back to its nil default) on any
               ;; source using `loop`.
               (vec
                     (mapcat
                     (fn [form]
                       (let [[_ name raw-params & body] form]
                         (when-not (valid-name? name) (reject! "invalid function name" name))
                         (when (contains? reserved-function-names name)
                           (reject! "reserved function name" name))
                         (when-not (vector? raw-params)
                           (reject! "function parameters must be a vector" raw-params))
                         (when (> (count raw-params) max-parameters)
                           (reject! "function parameters exceed ABI-supported arity" raw-params))
                         (when-not (= 1 (count body))
                           (reject! "function must contain one result expression" body))
                         (let [name+wraps (mapv param-name+wrap raw-params)
                               params (mapv first name+wraps)
                               wrap-body (apply comp (map second name+wraps))]
                           (when-not (and (every? valid-name? params) (= (count params) (count (distinct params))))
                             (reject! "function parameters must be unique bounded symbols with ABI-supported arity" raw-params))
                           (let [loop-helpers (atom [])
                                 desugared (binding [*pending-loop-helpers* loop-helpers]
                                             (desugar-expr (wrap-body (first body))))]
                             (into [{:name name :params params :result :i64 :effects #{}
                                     :body desugared}]
                                   @loop-helpers)))))
                     defs)))
        ;; ADR-2607150000: inject the synthesized `get`/`assoc` helpers only
        ;; when a desugared body actually calls them -- keeps modules that
        ;; never use `get`/`assoc` byte-identical to before this change. A
        ;; user `defn` that collides with a helper's reserved name is
        ;; caught for free by the existing :duplicate-function-name check
        ;; below (signatures' map semantics silently drop one entry, count
        ;; mismatch trips it).
        parsed (cond-> parsed
                 (some #(uses-map-get? (:body %)) parsed) (conj map-get-helper)
                 (some #(uses-map-without? (:body %)) parsed) (conj map-without-helper))
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
