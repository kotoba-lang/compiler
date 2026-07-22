(ns kotoba.compiler.component-core
  "Dedicated standard32 core emission for qualified Component Model slices."
  (:require [clojure.string :as str]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.canonical-abi :as canonical]
            [kotoba.compiler.component-wit :as component-wit]
            [kotoba.compiler.wasm-tools :as wasm-tools]))

(defn- reject [message data]
  (throw (ex-info message (assoc data :phase :component-core))))

(defn- exported-functions [kir]
  (let [names (set (:exports kir))]
    (filterv #(contains? names (:name %)) (:functions kir))))

(defn- scalar-function? [{:keys [param-types result]}]
  (and (every? #{:i64 :f32 :f64} param-types)
       (contains? #{:i64 :f32 :f64} result)))

(defn- string-leaves [form parameters]
  (cond
    (and (symbol? form) (contains? parameters form)) [{:kind :parameter :name form}]
    (string? form) [{:kind :literal :value form}]
    (and (seq? form) (= 'string-concat (first form)) (= 3 (count form)))
    (let [left (string-leaves (second form) parameters)
          right (string-leaves (nth form 2) parameters)]
      (when (and left right) (into left right)))
    :else nil))

(defn- string-expression-function? [{:keys [params param-types result body]}]
  (and (every? #{:string} param-types)
       (= (count params) (count param-types))
       (= :string result)
       (seq (string-leaves body (set params)))))

(defn- sealed-scalar-record [descriptor schemas]
  (let [schema (cond
                 (and (vector? descriptor) (= :ref (first descriptor)))
                 (get schemas (second descriptor))
                 (and (vector? descriptor) (= :record (first descriptor))) descriptor)]
    (when (and (vector? schema)
               (= :record (first schema))
               (seq (nth schema 2))
               (= schema (get schemas (second schema)))
               (every? (comp #{:i64 :f32 :f64 :bool} second) (nth schema 2)))
      schema)))

(defn- nested-scalar-record-schema
  "Schema of `descriptor` when it is a sealed record whose fields are each
  either a Canonical scalar or exactly one level of nested sealed all-scalar
  record (a field type for which `sealed-scalar-record` itself succeeds, so a
  nested field's own fields must all be scalar). This admits at most one
  level of nesting: a field that is itself a nested-of-nested record fails
  `sealed-scalar-record` (its fields are not all scalar) and so is rejected
  here too, before component encoding is attempted."
  [descriptor schemas]
  (let [schema (cond
                 (and (vector? descriptor) (= :ref (first descriptor)))
                 (get schemas (second descriptor))
                 (and (vector? descriptor) (= :record (first descriptor))) descriptor)]
    (when (and (vector? schema)
               (= :record (first schema))
               (seq (nth schema 2))
               (= schema (get schemas (second schema)))
               (every? (fn [[_ field-type]]
                         (or (contains? #{:i64 :f32 :f64 :bool} field-type)
                             (sealed-scalar-record field-type schemas)))
                       (nth schema 2)))
      schema)))

(defn- sealed-scalar-variant-schema
  "Schema of `descriptor` when it is a sealed variant whose every case's
  payload is a Canonical scalar or a sealed all-scalar record (the same
  shape `sealed-scalar-record` itself admits: top-level scalar fields only,
  not `nested-scalar-record-schema`'s one-level nesting). A case payload
  that is itself a nested-of-nested record, a record with a non-scalar
  field, or another variant fails `sealed-scalar-record` and so is rejected
  here too, before component encoding is attempted -- this bounds the
  variant slice to exactly the same 'record depth' ADR 0048 proved, applied
  per case instead of at the top level."
  [descriptor schemas]
  (let [schema (cond
                 (and (vector? descriptor) (= :ref (first descriptor)))
                 (get schemas (second descriptor))
                 (and (vector? descriptor) (= :variant (first descriptor))) descriptor)]
    (when (and (vector? schema)
               (= :variant (first schema))
               (seq (nth schema 2))
               (= schema (get schemas (second schema)))
               (every? (fn [[_ payload-type]]
                         (or (contains? #{:i64 :f32 :f64 :bool} payload-type)
                             (sealed-scalar-record payload-type schemas)))
                       (nth schema 2)))
      schema)))

(defn- scalar-record-identity-function? [function schemas]
  (let [{:keys [params param-types result body]} function
        descriptor (first param-types)
        schema (cond
                 (and (vector? descriptor) (= :ref (first descriptor)))
                 (get schemas (second descriptor))
                 (and (vector? descriptor) (= :record (first descriptor))) descriptor)]
    (and (= 1 (count params))
         (= 1 (count param-types))
         (= descriptor result)
         (= (first params) body)
         (and (vector? schema) (= :record (first schema)))
         (seq (nth schema 2))
         (every? (comp #{:i64 :f32 :f64 :bool} second) (nth schema 2))
         (canonical/layout descriptor schemas))))

(defn- nested-record-identity-function? [function schemas]
  (let [{:keys [params param-types result body]} function
        descriptor (first param-types)
        schema (nested-scalar-record-schema descriptor schemas)]
    (and (= 1 (count params))
         (= 1 (count param-types))
         (= descriptor result)
         (= (first params) body)
         schema
         ;; Distinct from `scalar-record-identity-function?`: admitted only
         ;; when at least one field is itself a nested sealed scalar record,
         ;; so the two predicates (and their dispatch cases) never overlap.
         (some (fn [[_ field-type]] (sealed-scalar-record field-type schemas))
               (nth schema 2))
         (canonical/layout descriptor schemas))))

(defn- variant-identity-function? [function schemas]
  (let [{:keys [params param-types result body]} function
        descriptor (first param-types)
        schema (sealed-scalar-variant-schema descriptor schemas)]
    (and (= 1 (count params))
         (= 1 (count param-types))
         (= descriptor result)
         (= (first params) body)
         schema
         (canonical/layout descriptor schemas))))

(defn- scalar-record-projection [function schemas]
  (let [{:keys [params param-types result body]} function
        descriptor (first param-types)
        schema (cond
                 (and (vector? descriptor) (= :ref (first descriptor)))
                 (get schemas (second descriptor))
                 (and (vector? descriptor) (= :record (first descriptor))) descriptor)
        [_ body-type value field] (when (seq? body) body)
        fields (when (and (vector? schema) (= :record (first schema))) (nth schema 2))
        field-index (first (keep-indexed (fn [index [name _]]
                                          (when (= name field) index))
                                        fields))
        field-type (when (some? field-index) (second (nth fields field-index)))]
    (when (and (= 1 (count params))
               (= 1 (count param-types))
               (seq? body)
               (= 'record-get (first body))
               (or (= body-type descriptor) (= body-type schema))
               (= value (first params))
               (some? field-index)
               (= field-type result)
               (contains? #{:i64 :f32 :f64 :bool} result)
               (every? (comp #{:i64 :f32 :f64 :bool} second) fields))
      {:descriptor descriptor :schema schema :field-index field-index})))

(defn- scalar-record-construction [function schemas]
  (let [{:keys [params param-types result body]} function
        schema (sealed-scalar-record result schemas)
        [_ body-type & values] (when (seq? body) body)
        field-types (mapv second (when schema (nth schema 2)))]
    (when (and schema
               (seq? body)
               (= 'record-new (first body))
               (= body-type schema)
               (= (vec values) (vec params))
               (= param-types field-types))
      {:descriptor result
       :input-types param-types
       :field-sources (vec (range (count field-types)))})))

(defn- scalar-record-update [function schemas]
  (let [{:keys [params param-types result body]} function
        record-descriptor (first param-types)
        replacement-type (second param-types)
        schema (sealed-scalar-record record-descriptor schemas)
        [_ body-type value field replacement] (when (seq? body) body)
        fields (when schema (nth schema 2))
        field-index (first (keep-indexed (fn [index [name _]]
                                          (when (= name field) index))
                                        fields))
        field-type (when (some? field-index) (second (nth fields field-index)))]
    (when (and schema
               (= 2 (count params))
               (= 2 (count param-types))
               (= result record-descriptor)
               (seq? body)
               (= 'record-assoc (first body))
               (or (= body-type record-descriptor) (= body-type schema))
               (= value (first params))
               (= replacement (second params))
               (some? field-index)
               (= replacement-type field-type))
      {:descriptor record-descriptor
       :input-types (conj (mapv second fields) replacement-type)
       :field-sources (assoc (vec (range (count fields)))
                             field-index (count fields))})))

(defn- scalar-capability-call [function]
  (let [{:keys [params param-types result body]} function
        [_ id request-type result-type request] (when (seq? body) body)
        capability (some #(when (= id (:id %)) %) (:capabilities component-wit/contract))]
    (when (and (= 1 (count params))
               (= 1 (count param-types))
               (seq? body)
               (= 'typed-cap-call (first body))
               (= request (first params))
               (= request-type (first param-types))
               (= result-type result)
               (contains? #{:i64 :f32 :f64} request-type)
               (contains? #{:i64 :f32 :f64} result-type)
               capability)
      capability)))

(defn- record-capability-call [function schemas]
  (let [{:keys [params param-types result body]} function
        request-type (first param-types)
        [_ id body-request-type body-result-type request] (when (seq? body) body)
        capability (some #(when (= id (:id %)) %) (:capabilities component-wit/contract))
        request-schema (sealed-scalar-record request-type schemas)
        result-schema (sealed-scalar-record result schemas)]
    (when (and (= 1 (count params))
               (= 1 (count param-types))
               (seq? body)
               (= 'typed-cap-call (first body))
               (= request (first params))
               (= body-request-type request-type)
               (= body-result-type result)
               (= request-type result)
               request-schema result-schema capability)
      {:capability capability :request request-type :result result})))

(defn assert-supported! [kir]
  (let [exports (exported-functions kir)]
    (cond
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (scalar-capability-call (first exports))) :scalar-capability-call
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (record-capability-call (first exports) (:schemas kir))) :record-capability-call
      (every? scalar-function? exports) :scalar
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (string-expression-function? (first exports))
           (empty? (:effects kir))) :string-expression
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (scalar-record-identity-function? (first exports) (:schemas kir))
           (empty? (:effects kir))) :scalar-record-identity
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (nested-record-identity-function? (first exports) (:schemas kir))
           (empty? (:effects kir))) :nested-record-identity
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (variant-identity-function? (first exports) (:schemas kir))
           (empty? (:effects kir))) :variant-identity
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (scalar-record-projection (first exports) (:schemas kir))
           (empty? (:effects kir))) :scalar-record-projection
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (scalar-record-construction (first exports) (:schemas kir))
           (empty? (:effects kir))) :scalar-record-construction
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (scalar-record-update (first exports) (:schemas kir))
           (empty? (:effects kir))) :scalar-record-update
      :else
      (reject "component function body has no qualified Canonical lowering"
              {:exports (mapv #(select-keys % [:name :param-types :result :body]) exports)}))))

(defn- wit-name [symbol]
  (let [value (name symbol)]
    (when-not (re-matches #"[a-z][a-z0-9-]*" value)
      (reject "string component export has no direct WIT name" {:name symbol}))
    value))

(defn- align-up [value alignment]
  (* alignment (quot (+ value (dec alignment)) alignment)))

(defn- prepare-leaves [function]
  (let [parameter-indices (zipmap (:params function) (range))
        leaves (string-leaves (:body function) (set (:params function)))]
    (loop [remaining leaves offset 8 prepared []]
      (if-let [leaf (first remaining)]
        (if (= :parameter (:kind leaf))
          (recur (next remaining) offset
                 (conj prepared (assoc leaf :index (get parameter-indices (:name leaf)))))
          (let [bytes (vec (.getBytes ^String (:value leaf) "UTF-8"))]
            (recur (next remaining) (+ offset (count bytes))
                   (conj prepared (assoc leaf :pointer offset :bytes bytes
                                         :length (count bytes))))))
        {:leaves prepared :arena-base (align-up offset 8)}))))

(defn- wat-data [bytes]
  (apply str (map #(format "\\%02x" (bit-and (int %) 0xff)) bytes)))

(defn- leaf-pointer [leaf]
  (if (= :parameter (:kind leaf))
    (str "local.get $p" (:index leaf) "-ptr")
    (str "i32.const " (:pointer leaf))))

(defn- leaf-length [leaf]
  (if (= :parameter (:kind leaf))
    (str "local.get $p" (:index leaf) "-len")
    (str "i32.const " (:length leaf))))

(defn- string-expression-wat [function]
  (let [export (wit-name (:name function))
        {:keys [leaves arena-base]} (prepare-leaves function)
        parameter-count (count (:params function))
        required-bytes (+ arena-base (* (inc parameter-count) 65536) 8)
        pages (max 1 (quot (+ required-bytes 65535) 65536))
        capacity (* pages 65536)
        params (apply str
                      (mapcat (fn [index]
                                [(str " (param $p" index "-ptr i32)")
                                 (str " (param $p" index "-len i32)")])
                              (range parameter-count)))
        validate-parameters
        (apply str
               (map (fn [index]
                      (str
                       "    local.get $p" index "-len i32.const 65536 i32.gt_u if unreachable end\n"
                       "    local.get $p" index "-ptr local.get $p" index "-len i32.add\n"
                       "    local.tee $end local.get $p" index "-ptr i32.lt_u if unreachable end\n"
                       "    local.get $end i32.const " capacity " i32.gt_u if unreachable end\n"))
                    (range parameter-count)))
        sum-lengths
        (apply str
               (map (fn [leaf]
                      (str "    local.get $total " (leaf-length leaf)
                           " i64.extend_i32_u i64.add local.tee $total\n"
                           "    i64.const 65536 i64.gt_u if unreachable end\n"))
                    leaves))
        copy-leaves
        (apply str
               (map (fn [leaf]
                      (let [length (leaf-length leaf)]
                        (str "    local.get $out local.get $cursor i32.add "
                             (leaf-pointer leaf) " " length " memory.copy\n"
                             "    local.get $cursor " length
                             " i32.add local.set $cursor\n")))
                    leaves))
        data-segments
        (apply str
               (keep (fn [leaf]
                       (when (= :literal (:kind leaf))
                         (str "  (data (i32.const " (:pointer leaf) ") \""
                              (wat-data (:bytes leaf)) "\")\n")))
                     leaves))]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") " pages " " pages ")\n"
     "  (global $next (mut i32) (i32.const " arena-base "))\n"
     "  (func $realloc (export \"cm32p2_realloc\")\n"
     "    (param $old-ptr i32) (param $old-size i32)\n"
     "    (param $align i32) (param $new-size i32) (result i32)\n"
     "    (local $ptr i32) (local $end i32) (local $copy-size i32)\n"
     "    local.get $new-size i32.eqz if i32.const 0 return end\n"
     "    local.get $align i32.eqz if unreachable end\n"
     "    local.get $align i32.const 8 i32.gt_u if unreachable end\n"
     "    local.get $align local.get $align i32.const 1 i32.sub i32.and if unreachable end\n"
     "    global.get $next local.get $align i32.const 1 i32.sub i32.add\n"
     "    i32.const 0 local.get $align i32.sub i32.and local.tee $ptr\n"
     "    local.get $new-size i32.add local.tee $end local.get $ptr i32.lt_u\n"
     "    if unreachable end\n"
     "    local.get $end i32.const " capacity " i32.gt_u if unreachable end\n"
     "    local.get $end global.set $next\n"
     "    local.get $old-ptr i32.eqz if else\n"
     "      local.get $old-size local.get $new-size i32.lt_u\n"
     "      if (result i32) local.get $old-size else local.get $new-size end\n"
     "      local.set $copy-size\n"
     "      local.get $ptr local.get $old-ptr local.get $copy-size memory.copy\n"
     "    end local.get $ptr)\n"
     "  (func (export \"cm32p2||" export "\")" params " (result i32)\n"
     "    (local $end i32) (local $out i32) (local $ret i32)\n"
     "    (local $cursor i32) (local $total i64)\n"
     validate-parameters
     "    i64.const 0 local.set $total\n"
     sum-lengths
     "    i32.const 0 i32.const 0 i32.const 1 local.get $total i32.wrap_i64\n"
     "    call $realloc local.set $out\n"
     "    i32.const 0 local.set $cursor\n"
     copy-leaves
     "    i32.const 0 i32.const 0 i32.const 4 i32.const 8 call $realloc local.tee $ret\n"
     "    local.get $out i32.store\n"
     "    local.get $ret local.get $total i32.wrap_i64 i32.store offset=4 local.get $ret)\n"
     "  (func (export \"cm32p2||" export "_post\") (param i32)\n"
     "    i32.const " arena-base " global.set $next)\n"
     "  (func (export \"cm32p2_initialize\") i32.const " arena-base " global.set $next)\n"
     data-segments
     ")\n")))

(defn- wasm-value-type [descriptor]
  ({:i64 "i64" :f32 "f32" :f64 "f64" :bool "i32"} descriptor))

(defn- wasm-store [descriptor]
  ({:i64 "i64.store" :f32 "f32.store" :f64 "f64.store" :bool "i32.store8"}
   descriptor))

(defn- core-type-of
  "The joined component-flat core wasm type (`:i64`/`:f32`/`:f64`/`:i32`)
  that a leaf's own Canonical scalar `descriptor` flattens to -- the same
  mapping `canonical-abi/layout*` bakes into every leaf's own `:flat`, kept
  here too because variant codegen needs it both to build case payload
  values (`wasm-value-type`/`wasm-store`, keyed by `descriptor`) and to
  compare against the joined param's core type (keyed by core type)."
  [descriptor]
  ({:i64 :i64 :f32 :f32 :f64 :f64 :bool :i32} descriptor))

(defn- core-type-name [core-type]
  ({:i64 "i64" :f32 "f32" :f64 "f64" :i32 "i32"} core-type))

(defn- variant-disc-store [byte-size]
  ({1 "i32.store8" 2 "i32.store16" 4 "i32.store"} byte-size))

(defn- variant-coerce-ops
  "The WAT instruction(s) that turn a received joined-flat param value
  (core type `have`) back into the value a specific case's own leaf (core
  type `want`) needs to store, mirroring the Component Model spec's
  `lift_flat_variant` `CoerceValueIter` table exactly: identical types need
  nothing; `i32`-holding-a-bool next to an `f32` case at the same position
  reinterprets; anything joined up to `i64` either wraps down to `i32`,
  reinterprets down to `f64`, or does both to reach `f32`. No other
  `(have, want)` pair is reachable -- `join-core-type` only ever produces
  `i32` (self-join or the i32/f32 pair) or `i64` (every other mismatch), so
  this table is exhaustive over what this codebase's own join can produce,
  not a hand-picked subset of the spec's."
  [have want]
  (cond
    (= have want) []
    (and (= have :i32) (= want :f32)) ["f32.reinterpret_i32"]
    (and (= have :i64) (= want :i32)) ["i32.wrap_i64"]
    (and (= have :i64) (= want :f32)) ["i32.wrap_i64" "f32.reinterpret_i32"]
    (and (= have :i64) (= want :f64)) ["f64.reinterpret_i64"]
    :else (reject "variant flat join has no defined coercion" {:have have :want want})))

(defn- variant-case-leaves
  "The ordered `{:relative-offset :descriptor :flat-index}` leaves of one
  variant case's own payload layout: a scalar payload is its own single
  leaf at relative offset 0 and flat-index 0; a sealed all-scalar record
  payload (the only aggregate case shape this slice admits -- see
  `sealed-scalar-variant-schema`) contributes one leaf per top-level field,
  in the same order as the record's own `:fields`/`:flat`, so `flat-index`
  always lines up with position `flat-index` of *this case's own*
  `flatten_type` sequence -- the same sequence `variant-flatten-payload`
  folds every case's flat types into, position by position, starting at
  index 0 for every case alike. This does not recurse into nested `:fields`
  (unlike `canonical-abi/layout-leaves`): a variant case payload is bounded
  to the ADR 0043 flat-record shape in this slice, never the ADR 0051
  one-level-nested shape, so no leaf here is itself a nested record."
  [layout]
  (if (contains? layout :fields)
    (vec (map-indexed (fn [index {:keys [offset layout]}]
                        {:relative-offset offset :descriptor (:descriptor layout) :flat-index index})
                      (:fields layout)))
    [{:relative-offset 0 :descriptor (:descriptor layout) :flat-index 0}]))

(defn- variant-payload-value-expr [joined-types leaf]
  (let [{:keys [descriptor flat-index]} leaf
        have (nth joined-types flat-index)
        want (core-type-of descriptor)]
    (apply str "local.get $p" flat-index
           (map #(str " " %) (variant-coerce-ops have want)))))

(defn- variant-case-body [payload-offset joined-types leaves]
  (let [validation
        (apply str
               (keep (fn [leaf]
                       (when (= :bool (:descriptor leaf))
                         (str "      " (variant-payload-value-expr joined-types leaf)
                              " i32.const 1 i32.gt_u if unreachable end\n")))
                     leaves))
        stores
        (apply str
               (map (fn [leaf]
                      (str "      local.get $ret "
                           (variant-payload-value-expr joined-types leaf)
                           " " (wasm-store (:descriptor leaf))
                           " offset=" (+ payload-offset (:relative-offset leaf)) "\n"))
                    leaves))]
    (str validation stores)))

(defn- variant-case-chain
  "The nested `if`/`else` chain that stores the active case's payload into
  the result area. Every case but the last is guarded by an explicit
  `local.get $disc i32.const <index> i32.eq`; the final case needs no guard
  because the discriminant is already range-checked (`i32.ge_u` against the
  case count) before this chain runs, so falling through every prior `else`
  leaves exactly the last case."
  [cases payload-offset joined-types]
  (letfn [(build [remaining index]
            (let [body (variant-case-body payload-offset joined-types
                                          (variant-case-leaves (:layout (first remaining))))]
              (if (= 1 (count remaining))
                body
                (str "    local.get $disc i32.const " index " i32.eq\n"
                     "    if\n"
                     body
                     "    else\n"
                     (build (rest remaining) (inc index))
                     "    end\n"))))]
    (build cases 0)))

(defn- variant-wat
  "Identity export for a sealed variant whose every case's payload is a
  Canonical scalar or a sealed all-scalar record. The core function
  receives the variant already flattened by the caller's own `canon
  lower` -- an `i32` discriminant plus one core value per joined payload
  position (`canonical/layout`'s `:flat` on a variant descriptor, computed
  by `variant-flatten-payload`) -- range-checks the discriminant, allocates
  the variant's in-memory union result area (discriminant byte plus the
  widest case's payload, from the same layout's `:size`/`:alignment`),
  stores the discriminant, and then, in exactly the branch selected by the
  discriminant, un-joins and stores that case's own leaves
  (`variant-case-chain`/`variant-coerce-ops`). This is the same
  realloc/result-area shape as `scalar-record-wat`/`nested-record-wat`;
  the only new work is the join/coercion table a variant's shared flat
  positions require and a record's concatenated ones never did."
  [function schemas]
  (let [export (wit-name (:name function))
        variant-layout (canonical/layout (first (:param-types function)) schemas)
        joined-types (vec (rest (:flat variant-layout)))
        params (apply str
                      (map-indexed
                       (fn [index core-type]
                         (str " (param $p" index " " (core-type-name core-type) ")"))
                       joined-types))
        case-chain (variant-case-chain (:cases variant-layout)
                                       (:payload-offset variant-layout)
                                       joined-types)]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") 1 1)\n"
     "  (global $next (mut i32) (i32.const 8))\n"
     "  (func $realloc (export \"cm32p2_realloc\")\n"
     "    (param $old-ptr i32) (param $old-size i32)\n"
     "    (param $align i32) (param $new-size i32) (result i32)\n"
     "    (local $ptr i32) (local $end i32) (local $copy-size i32)\n"
     "    local.get $new-size i32.eqz if i32.const 0 return end\n"
     "    local.get $align i32.eqz if unreachable end\n"
     "    local.get $align i32.const 8 i32.gt_u if unreachable end\n"
     "    local.get $align local.get $align i32.const 1 i32.sub i32.and if unreachable end\n"
     "    global.get $next local.get $align i32.const 1 i32.sub i32.add\n"
     "    i32.const 0 local.get $align i32.sub i32.and local.tee $ptr\n"
     "    local.get $new-size i32.add local.tee $end local.get $ptr i32.lt_u\n"
     "    if unreachable end\n"
     "    local.get $end i32.const 65536 i32.gt_u if unreachable end\n"
     "    local.get $end global.set $next\n"
     "    local.get $old-ptr i32.eqz if else\n"
     "      local.get $old-size local.get $new-size i32.lt_u\n"
     "      if (result i32) local.get $old-size else local.get $new-size end\n"
     "      local.set $copy-size\n"
     "      local.get $ptr local.get $old-ptr local.get $copy-size memory.copy\n"
     "    end local.get $ptr)\n"
     "  (func (export \"cm32p2||" export "\") (param $disc i32)" params " (result i32)\n"
     "    (local $ret i32)\n"
     "    local.get $disc i32.const " (count (:cases variant-layout)) " i32.ge_u if unreachable end\n"
     "    i32.const 0 i32.const 0 i32.const " (:alignment variant-layout)
     " i32.const " (:size variant-layout) " call $realloc local.set $ret\n"
     "    local.get $ret local.get $disc "
     (variant-disc-store (:discriminant-size variant-layout)) " offset=0\n"
     case-chain
     "    local.get $ret)\n"
     "  (func (export \"cm32p2||" export "_post\") (param i32)\n"
     "    i32.const 8 global.set $next)\n"
     "  (func (export \"cm32p2_initialize\") i32.const 8 global.set $next))\n")))

(defn- scalar-record-wat [function schemas]
  (let [export (wit-name (:name function))
        record-layout (canonical/layout (first (:param-types function)) schemas)
        fields (:fields record-layout)
        params (apply str
                      (map-indexed
                       (fn [index {:keys [layout]}]
                         (str " (param $f" index " "
                              (wasm-value-type (:descriptor layout)) ")"))
                       fields))
        bool-validation
        (apply str
               (keep-indexed
                (fn [index {:keys [layout]}]
                  (when (= :bool (:descriptor layout))
                    (str "    local.get $f" index
                         " i32.const 1 i32.gt_u if unreachable end\n")))
                fields))
        stores
        (apply str
               (map-indexed
                (fn [index {:keys [offset layout]}]
                  (str "    local.get $ret local.get $f" index " "
                       (wasm-store (:descriptor layout)) " offset=" offset "\n"))
                fields))]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") 1 1)\n"
     "  (global $next (mut i32) (i32.const 8))\n"
     "  (func $realloc (export \"cm32p2_realloc\")\n"
     "    (param $old-ptr i32) (param $old-size i32)\n"
     "    (param $align i32) (param $new-size i32) (result i32)\n"
     "    (local $ptr i32) (local $end i32) (local $copy-size i32)\n"
     "    local.get $new-size i32.eqz if i32.const 0 return end\n"
     "    local.get $align i32.eqz if unreachable end\n"
     "    local.get $align i32.const 8 i32.gt_u if unreachable end\n"
     "    local.get $align local.get $align i32.const 1 i32.sub i32.and if unreachable end\n"
     "    global.get $next local.get $align i32.const 1 i32.sub i32.add\n"
     "    i32.const 0 local.get $align i32.sub i32.and local.tee $ptr\n"
     "    local.get $new-size i32.add local.tee $end local.get $ptr i32.lt_u\n"
     "    if unreachable end\n"
     "    local.get $end i32.const 65536 i32.gt_u if unreachable end\n"
     "    local.get $end global.set $next\n"
     "    local.get $old-ptr i32.eqz if else\n"
     "      local.get $old-size local.get $new-size i32.lt_u\n"
     "      if (result i32) local.get $old-size else local.get $new-size end\n"
     "      local.set $copy-size\n"
     "      local.get $ptr local.get $old-ptr local.get $copy-size memory.copy\n"
     "    end local.get $ptr)\n"
     "  (func (export \"cm32p2||" export "\")" params " (result i32)\n"
     "    (local $ret i32)\n"
     bool-validation
     "    i32.const 0 i32.const 0 i32.const " (:alignment record-layout)
     " i32.const " (:size record-layout) " call $realloc local.set $ret\n"
     stores
     "    local.get $ret)\n"
     "  (func (export \"cm32p2||" export "_post\") (param i32)\n"
     "    i32.const 8 global.set $next)\n"
     "  (func (export \"cm32p2_initialize\") i32.const 8 global.set $next))\n")))

(defn- nested-record-wat
  "Identity export for a sealed record with exactly one level of nested
  record fields. Every core parameter and result-area store is planned from
  `canonical/layout-leaves`, which walks nested field layouts to absolute
  offsets in the same depth-first order as the Canonical ABI's own `:flat`
  vector; this is the only difference from `scalar-record-wat` (which is the
  degenerate zero-nesting case of the same flattening)."
  [function schemas]
  (let [export (wit-name (:name function))
        record-layout (canonical/layout (first (:param-types function)) schemas)
        leaves (canonical/layout-leaves record-layout)
        params (apply str
                      (map-indexed
                       (fn [index {:keys [descriptor]}]
                         (str " (param $f" index " " (wasm-value-type descriptor) ")"))
                       leaves))
        bool-validation
        (apply str
               (keep-indexed
                (fn [index {:keys [descriptor]}]
                  (when (= :bool descriptor)
                    (str "    local.get $f" index
                         " i32.const 1 i32.gt_u if unreachable end\n")))
                leaves))
        stores
        (apply str
               (map-indexed
                (fn [index {:keys [offset descriptor]}]
                  (str "    local.get $ret local.get $f" index " "
                       (wasm-store descriptor) " offset=" offset "\n"))
                leaves))]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") 1 1)\n"
     "  (global $next (mut i32) (i32.const 8))\n"
     "  (func $realloc (export \"cm32p2_realloc\")\n"
     "    (param $old-ptr i32) (param $old-size i32)\n"
     "    (param $align i32) (param $new-size i32) (result i32)\n"
     "    (local $ptr i32) (local $end i32) (local $copy-size i32)\n"
     "    local.get $new-size i32.eqz if i32.const 0 return end\n"
     "    local.get $align i32.eqz if unreachable end\n"
     "    local.get $align i32.const 8 i32.gt_u if unreachable end\n"
     "    local.get $align local.get $align i32.const 1 i32.sub i32.and if unreachable end\n"
     "    global.get $next local.get $align i32.const 1 i32.sub i32.add\n"
     "    i32.const 0 local.get $align i32.sub i32.and local.tee $ptr\n"
     "    local.get $new-size i32.add local.tee $end local.get $ptr i32.lt_u\n"
     "    if unreachable end\n"
     "    local.get $end i32.const 65536 i32.gt_u if unreachable end\n"
     "    local.get $end global.set $next\n"
     "    local.get $old-ptr i32.eqz if else\n"
     "      local.get $old-size local.get $new-size i32.lt_u\n"
     "      if (result i32) local.get $old-size else local.get $new-size end\n"
     "      local.set $copy-size\n"
     "      local.get $ptr local.get $old-ptr local.get $copy-size memory.copy\n"
     "    end local.get $ptr)\n"
     "  (func (export \"cm32p2||" export "\")" params " (result i32)\n"
     "    (local $ret i32)\n"
     bool-validation
     "    i32.const 0 i32.const 0 i32.const " (:alignment record-layout)
     " i32.const " (:size record-layout) " call $realloc local.set $ret\n"
     stores
     "    local.get $ret)\n"
     "  (func (export \"cm32p2||" export "_post\") (param i32)\n"
     "    i32.const 8 global.set $next)\n"
     "  (func (export \"cm32p2_initialize\") i32.const 8 global.set $next))\n")))

(defn- scalar-record-projection-wat [function schemas]
  (let [export (wit-name (:name function))
        {:keys [descriptor field-index]} (scalar-record-projection function schemas)
        record-layout (canonical/layout descriptor schemas)
        fields (:fields record-layout)
        result (:result function)
        params (apply str
                      (map-indexed
                       (fn [index {:keys [layout]}]
                         (str " (param $f" index " "
                              (wasm-value-type (:descriptor layout)) ")"))
                       fields))
        bool-validation
        (apply str
               (keep-indexed
                (fn [index {:keys [layout]}]
                  (when (= :bool (:descriptor layout))
                    (str "    local.get $f" index
                         " i32.const 1 i32.gt_u if unreachable end\n")))
                fields))]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") 1 1)\n"
     "  (global $next (mut i32) (i32.const 8))\n"
     "  (func $realloc (export \"cm32p2_realloc\")\n"
     "    (param $old-ptr i32) (param $old-size i32)\n"
     "    (param $align i32) (param $new-size i32) (result i32)\n"
     "    (local $ptr i32) (local $end i32) (local $copy-size i32)\n"
     "    local.get $new-size i32.eqz if i32.const 0 return end\n"
     "    local.get $align i32.eqz if unreachable end\n"
     "    local.get $align i32.const 8 i32.gt_u if unreachable end\n"
     "    local.get $align local.get $align i32.const 1 i32.sub i32.and if unreachable end\n"
     "    global.get $next local.get $align i32.const 1 i32.sub i32.add\n"
     "    i32.const 0 local.get $align i32.sub i32.and local.tee $ptr\n"
     "    local.get $new-size i32.add local.tee $end local.get $ptr i32.lt_u\n"
     "    if unreachable end\n"
     "    local.get $end i32.const 65536 i32.gt_u if unreachable end\n"
     "    local.get $end global.set $next\n"
     "    local.get $old-ptr i32.eqz if else\n"
     "      local.get $old-size local.get $new-size i32.lt_u\n"
     "      if (result i32) local.get $old-size else local.get $new-size end\n"
     "      local.set $copy-size\n"
     "      local.get $ptr local.get $old-ptr local.get $copy-size memory.copy\n"
     "    end local.get $ptr)\n"
     "  (func (export \"cm32p2||" export "\")" params
     " (result " (wasm-value-type result) ")\n"
     bool-validation
     "    local.get $f" field-index ")\n"
     "  (func (export \"cm32p2||" export "_post\") (param "
     (wasm-value-type result) "))\n"
     "  (func (export \"cm32p2_initialize\") i32.const 8 global.set $next))\n")))

(defn- scalar-record-write-wat [function schemas plan]
  (let [export (wit-name (:name function))
        record-layout (canonical/layout (:descriptor plan) schemas)
        input-types (:input-types plan)
        params (apply str
                      (map-indexed (fn [index type]
                                     (str " (param $v" index " "
                                          (wasm-value-type type) ")"))
                                   input-types))
        bool-validation
        (apply str
               (keep-indexed (fn [index type]
                               (when (= :bool type)
                                 (str "    local.get $v" index
                                      " i32.const 1 i32.gt_u if unreachable end\n")))
                             input-types))
        stores
        (apply str
               (map (fn [{:keys [offset layout]} source]
                      (str "    local.get $ret local.get $v" source " "
                           (wasm-store (:descriptor layout)) " offset=" offset "\n"))
                    (:fields record-layout) (:field-sources plan)))]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") 1 1)\n"
     "  (global $next (mut i32) (i32.const 8))\n"
     "  (func $realloc (export \"cm32p2_realloc\")\n"
     "    (param $old-ptr i32) (param $old-size i32)\n"
     "    (param $align i32) (param $new-size i32) (result i32)\n"
     "    (local $ptr i32) (local $end i32) (local $copy-size i32)\n"
     "    local.get $new-size i32.eqz if i32.const 0 return end\n"
     "    local.get $align i32.eqz if unreachable end\n"
     "    local.get $align i32.const 8 i32.gt_u if unreachable end\n"
     "    local.get $align local.get $align i32.const 1 i32.sub i32.and if unreachable end\n"
     "    global.get $next local.get $align i32.const 1 i32.sub i32.add\n"
     "    i32.const 0 local.get $align i32.sub i32.and local.tee $ptr\n"
     "    local.get $new-size i32.add local.tee $end local.get $ptr i32.lt_u\n"
     "    if unreachable end\n"
     "    local.get $end i32.const 65536 i32.gt_u if unreachable end\n"
     "    local.get $end global.set $next\n"
     "    local.get $old-ptr i32.eqz if else\n"
     "      local.get $old-size local.get $new-size i32.lt_u\n"
     "      if (result i32) local.get $old-size else local.get $new-size end\n"
     "      local.set $copy-size\n"
     "      local.get $ptr local.get $old-ptr local.get $copy-size memory.copy\n"
     "    end local.get $ptr)\n"
     "  (func (export \"cm32p2||" export "\")" params " (result i32)\n"
     "    (local $ret i32)\n"
     bool-validation
     "    i32.const 0 i32.const 0 i32.const " (:alignment record-layout)
     " i32.const " (:size record-layout) " call $realloc local.set $ret\n"
     stores
     "    local.get $ret)\n"
     "  (func (export \"cm32p2||" export "_post\") (param i32)\n"
     "    i32.const 8 global.set $next)\n"
     "  (func (export \"cm32p2_initialize\") i32.const 8 global.set $next))\n")))

(defn- scalar-capability-wat [function capability]
  (let [export (wit-name (:name function))
        request-type (first (:param-types function))
        result-type (:result function)
        interface (name (:interface capability))
        operation (:function capability)]
    (str
     "(module\n"
     "  (import \"cm32p2|kotoba:application/" interface "@1\" \"" operation
     "\" (func $provider (param " (wasm-value-type request-type)") (result "
     (wasm-value-type result-type) ")))\n"
     "  (memory (export \"cm32p2_memory\") 1 1)\n"
     "  (func (export \"cm32p2||" export "\") (param $request "
     (wasm-value-type request-type) ") (result " (wasm-value-type result-type) ")\n"
     "    local.get $request call $provider)\n"
     "  (func (export \"cm32p2||" export "_post\") (param "
     (wasm-value-type result-type) "))\n"
     "  (func (export \"cm32p2_realloc\") (param i32 i32 i32 i32) (result i32)\n"
     "    i32.const 0)\n"
     "  (func (export \"cm32p2_initialize\")))\n")))

(defn- record-capability-wat [function schemas plan]
  (let [export (wit-name (:name function))
        capability (:capability plan)
        request-layout (canonical/layout (:request plan) schemas)
        result-layout (canonical/layout (:result plan) schemas)
        fields (:fields request-layout)
        params (apply str
                      (map-indexed
                       (fn [index {:keys [layout]}]
                         (str " (param $f" index " "
                              (wasm-value-type (:descriptor layout)) ")"))
                       fields))
        import-params (apply str
                             (map (fn [{:keys [layout]}]
                                    (str " (param " (wasm-value-type (:descriptor layout)) ")"))
                                  fields))
        bool-validation
        (apply str
               (keep-indexed
                (fn [index {:keys [layout]}]
                  (when (= :bool (:descriptor layout))
                    (str "    local.get $f" index
                         " i32.const 1 i32.gt_u if unreachable end\n")))
                fields))
        arguments (apply str (map #(str "    local.get $f" % "\n") (range (count fields))))]
    (str
     "(module\n"
     "  (import \"cm32p2|kotoba:application/" (:interface capability) "@1\" \""
     (:function capability) "\" (func $provider" import-params " (param i32)))\n"
     "  (memory (export \"cm32p2_memory\") 1 1)\n"
     "  (global $next (mut i32) (i32.const 8))\n"
     "  (func $realloc (export \"cm32p2_realloc\")\n"
     "    (param i32 i32) (param $align i32) (param $size i32) (result i32)\n"
     "    (local $ptr i32)\n"
     "    global.get $next local.get $align i32.const 1 i32.sub i32.add\n"
     "    i32.const 0 local.get $align i32.sub i32.and local.tee $ptr\n"
     "    local.get $size i32.add global.set $next local.get $ptr)\n"
     "  (func (export \"cm32p2||" export "\")" params " (result i32)\n"
     "    (local $ret i32)\n"
     bool-validation
     "    i32.const 0 i32.const 0 i32.const " (:alignment result-layout)
     " i32.const " (:size result-layout) " call $realloc local.set $ret\n"
     arguments
     "    local.get $ret call $provider local.get $ret)\n"
     "  (func (export \"cm32p2||" export "_post\") (param i32)\n"
     "    i32.const 8 global.set $next)\n"
     "  (func (export \"cm32p2_initialize\") i32.const 8 global.set $next))\n")))

(defn emit [kir target]
  (case (assert-supported! kir)
    :scalar (wasm/emit-component-core kir target)
    :string-expression (wasm-tools/parse-wat
                        (string-expression-wat (first (exported-functions kir))))
    :scalar-record-identity
    (wasm-tools/parse-wat
     (scalar-record-wat (first (exported-functions kir)) (:schemas kir)))
    :nested-record-identity
    (wasm-tools/parse-wat
     (nested-record-wat (first (exported-functions kir)) (:schemas kir)))
    :variant-identity
    (wasm-tools/parse-wat
     (variant-wat (first (exported-functions kir)) (:schemas kir)))
    :scalar-record-projection
    (wasm-tools/parse-wat
     (scalar-record-projection-wat (first (exported-functions kir)) (:schemas kir)))
    :scalar-record-construction
    (let [function (first (exported-functions kir))]
      (wasm-tools/parse-wat
       (scalar-record-write-wat function (:schemas kir)
                                (scalar-record-construction function (:schemas kir)))))
    :scalar-record-update
    (let [function (first (exported-functions kir))]
      (wasm-tools/parse-wat
       (scalar-record-write-wat function (:schemas kir)
                                (scalar-record-update function (:schemas kir)))))
    :scalar-capability-call
    (let [function (first (exported-functions kir))]
      (wasm-tools/parse-wat
       (scalar-capability-wat function (scalar-capability-call function))))
    :record-capability-call
    (let [function (first (exported-functions kir))]
      (wasm-tools/parse-wat
       (record-capability-wat function (:schemas kir)
                              (record-capability-call function (:schemas kir)))))))
