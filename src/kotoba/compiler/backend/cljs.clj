(ns kotoba.compiler.backend.cljs
  "ADR-2607151500: lowers a KIR module to plain ClojureScript source TEXT --
  a new, genuinely separate execution target alongside the existing wasm32/
  x86_64/aarch64 backends. Unlike those, this backend does not assemble
  bytes; KIR's `:functions` bodies are already a small closed-form Lisp
  (let/if/cap-call/pair family/arithmetic/comparison/named calls, see
  ir.clj's `eval-expr`), so lowering is a 1:1 data->data rewrite from KIR's
  s-expression vocabulary to an equivalent, textually-emitted cljs
  s-expression vocabulary -- no byte-level encoding, no memory layout, no
  ABI concerns (a cljs runtime already has real heap-allocated persistent
  data structures, so `pair`/`pair-first`/`pair-second` become plain
  2-element vectors + `nth`, not a hand-rolled linear-memory heap).

  Two semantics KIR normatively defines that plain cljs forms do NOT
  reproduce for free, both handled explicitly here (silently accepting the
  cljs default would be a silent divergence from every other backend, not
  an equivalent target):
  - KIR's `if` treats exactly 0 as false, anything else (including
    negative numbers) as true -- cljs's `if` treats only nil/false as
    falsy, and 0 is truthy. Every emitted `if` wraps its test as
    `(if (zero? test) else then)`, matching ir.clj's own eval-expr exactly.
  - KIR's comparison ops (`= < > <= >=`) return the INTEGER 1 or 0 (so the
    result composes with `if`'s 0-is-false convention above), not a
    boolean -- every comparison is wrapped `(if (cljs-op ...) 1 0)`.

  One more real (documented, not silently smoothed over) gap against the
  wasm/native backends, an honest scope limit -- now narrowed from
  \"silently wrong\" to \"loudly fails\":
  - `ir.clj`'s `execute` docstring is explicit: \"Add/subtract/multiply
    wrap modulo 2^64\" -- true two's-complement i64 wraparound. Exact
    wraparound parity would need EVERY value (params, literals,
    intermediate results) represented as a JS BigInt end to end -- a much
    larger, invasive rewrite of this backend's whole numeric
    representation, not attempted here (no known safe-kotoba-subset
    program needs it). Instead, every `+`/`-`/`*` result is checked
    against JS's own safe-integer bound (`kotoba$check-safe-int`, 2^53-1)
    and THROWS `:arithmetic-overflow` rather than silently continuing with
    a value cljs's plain `number` (an IEEE-754 double) can no longer
    represent exactly -- the same fail-closed posture this backend already
    takes for fuel/division/capability, applied here too: an unreachable-
    today divergence stays unreachable by trapping loudly, not by
    producing a silently wrong result. The safe-integer bound is written
    as a portable numeric literal (not `js/Number.isSafeInteger`)
    specifically so this check evaluates identically whether the emitted
    source runs under real cljs or, as this backend's own committed test
    suite does, under plain JVM Clojure.
  `cap-call` (capability invocation): the emitted module exports
  `set-cap-dispatch!` (a fn [cap-id value] -> i64, installed via
  `kotoba$cap-dispatch`, a `defonce` atom) as its cljs-side equivalent of
  WASM's `kotoba:cap.call` host import -- the host calls
  `set-cap-dispatch!` before calling `main`, exactly the same \"host wires
  the boundary, host calls main\" shape as the wasm/native backends'
  memory-writing convention. No dispatcher installed means EVERY cap-call
  throws `:capability-denied` -- fail-closed, matching
  `kotoba-lang/kotoba`'s own `has-capability-fn` (\"no POLICY grants
  NOTHING\") rather than defaulting to permissive.

  Fuel: WASM's `charge!` is a MODULE-GLOBAL mutable counter that starts at
  256 and is never replenished for the life of one Instance (ir.clj: one
  charge per function ENTRY, not per expression -- confirmed by
  backend/wasm.clj's `global-section`, a `(global (mut i64) (i64.const
  256))` set once at instantiation). This backend reproduces the same
  global-depletion behavior with a namespace-level `defonce` atom, so a
  loaded cljs module is, like a WASM Instance, permanently exhausted after
  256 total function calls across its whole lifetime -- not 256 per
  top-level call."
  (:require [clojure.string :as str]))

(declare lower-expr)

(def ^:private comparison-ops '#{= < > <= >=})
(def ^:private wraparound-arith-ops '#{+ - *})

(defn- lower-expr [form]
  (cond
    (integer? form) form
    (symbol? form) form
    :else
    (let [[op & args] form]
      (case op
        let (let [[bindings body] args]
              (list 'let (vec (mapcat (fn [[name value]] [name (lower-expr value)])
                                       (partition 2 bindings)))
                    (lower-expr body)))

        if (let [[test then else] args]
             (list 'if (list 'zero? (lower-expr test)) (lower-expr else) (lower-expr then)))

        ;; `do`: Clojure's own `do` sequences the lowered subexpressions.
        do (list* 'do (map lower-expr args))

        pair (let [[l r] args] (list 'vector (lower-expr l) (lower-expr r)))
        pair-first (list 'nth (lower-expr (first args)) 0)
        pair-second (list 'nth (lower-expr (first args)) 1)

        quot (list 'kotoba$quot (lower-expr (first args)) (lower-expr (second args)))

        ;; cap-id is always a literal integer in [0,255] here -- validated
        ;; by frontend.clj's validate-expr, never itself an expression (see
        ;; ir.clj's eval-expr, which never runs `cap-id` through eval-expr
        ;; either) -- so it passes through unchanged, only `value` recurses.
        cap-call (let [[cap-id value] args]
                   (list 'kotoba$cap-call cap-id (lower-expr value)))

        (cond
          (contains? comparison-ops op)
          (list 'if (apply list op (map lower-expr args)) 1 0)

          (contains? wraparound-arith-ops op)
          (list 'kotoba$check-safe-int (apply list op (map lower-expr args)))

          :else (apply list op (map lower-expr args)))))))

(defn- lower-function [{:keys [name params body]}]
  (list 'defn name (vec params) (list 'do '(kotoba$charge!) (lower-expr body))))

(def ^:private prelude-forms
  '[(defonce kotoba$fuel (atom 256))
    (defn- kotoba$charge! []
      (let [remaining (swap! kotoba$fuel dec)]
        (when (neg? remaining)
          (throw (ex-info "fuel-exhausted" {:kotoba.cljs/trap :fuel-exhausted})))))
    ;; 2^53 - 1, JS's own safe-integer bound -- written as a portable
    ;; numeric literal (not js/Number.isSafeInteger) so this evaluates
    ;; identically under real cljs or plain JVM Clojure (see this ns's
    ;; own docstring).
    (def ^:private kotoba$max-safe-integer 9007199254740991)
    (defn- kotoba$check-safe-int [n]
      (if (<= (- kotoba$max-safe-integer) n kotoba$max-safe-integer)
        n
        (throw (ex-info "arithmetic-overflow"
                        {:kotoba.cljs/trap :arithmetic-overflow :kotoba.cljs/value n}))))
    (defn- kotoba$quot [a b]
      (when (zero? b)
        (throw (ex-info "division-by-zero" {:kotoba.cljs/trap :division-by-zero})))
      (quot a b))
    (defonce kotoba$cap-dispatch (atom nil))
    (defn set-cap-dispatch!
      "Installs F (a fn [cap-id value] -> i64) as this module's capability
      host, this backend's equivalent of WASM's kotoba:cap.call host
      import. Call before `main` if the module uses cap-call. No
      dispatcher installed means every cap-call is denied (fail-closed)."
      [f]
      (reset! kotoba$cap-dispatch f))
    (defn- kotoba$cap-call [cap-id value]
      (if-let [f @kotoba$cap-dispatch]
        (long (f cap-id value))
        (throw (ex-info "capability-denied"
                        {:kotoba.cljs/trap :capability-denied
                         :kotoba.cljs/capability cap-id}))))])

(def default-ns-name
  "KIR carries no `ns` name (frontend/analyze validates and discards the
  source `(ns ...)` form, see its own comment) -- every emitted module uses
  this fixed namespace unless the caller renames it in the returned source
  text themselves (an ns-name option is a natural follow-up, not needed for
  emit to be genuinely runnable via `nbb`/a cljs bundler today)."
  'kotoba.compiled.generated)

(defn emit
  "Lowers KIR to a plain ClojureScript source string: one `(ns ...)` form,
  the fuel/quot prelude, a `(declare ...)` of every KIR function name, then
  one `defn` per KIR function (in KIR's own order) -- callable directly by
  any cljs host (nbb, a browser bundle, shadow-cljs) that requires the
  emitted namespace and invokes `main` itself, the same \"host writes
  inputs, host calls main\" shape every other backend in this compiler
  already uses.

  The `declare` line is not decorative: unlike WASM/native, which resolve
  every call through a function-index table built BEFORE any body is
  assembled (so textual order is irrelevant), plain `defn` forms in a cljs
  (or JVM Clojure) file are compiled in file order with no forward
  hoisting -- a function whose body calls a KIR sibling defined LATER in
  the same source fails to resolve at compile/analysis time. This is real,
  not hypothetical: a `loop`/`recur`-using source's own synthesized helper
  is placed AFTER the defn that calls it in KIR's own function order (the
  originating defn is listed first, its loop-helpers appended after --
  see ADR-2607150000), so `main () -> __kotoba_loop_1` is exactly this
  shape. Confirmed live via real `nbb` execution before this fix: `Unable
  to resolve symbol: __kotoba_loop_1`, identically to plain JVM `eval`."
  [kir]
  (let [fn-names (mapv :name (:functions kir))
        fn-forms (mapv lower-function (:functions kir))
        forms (concat [(list 'ns default-ns-name)] prelude-forms
                       [(list* 'declare fn-names)] fn-forms)]
    (str/join "\n\n" (map pr-str forms))))
