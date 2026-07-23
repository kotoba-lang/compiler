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

(defn- string-keyword-scalar-field? [field-type]
  (contains? #{:i64 :f32 :f64 :bool :string :keyword} field-type))

(defn- string-field-record-schema
  "Schema of `descriptor` when it is a sealed record whose fields are each a
  Canonical scalar (`i64`/`f32`/`f64`/`bool`) or a bounded `string`/`keyword`
  leaf -- flat only, no nesting, no variant payloads. This is
  `sealed-scalar-record` widened by exactly the two leaf types ADR 0051's own
  'Remaining gaps' named ('strings or keywords inside a case's record
  payload, so `state-v1`'s actual `entry`/`error` records remain closed'):
  `state-v1`'s `entry` record (`key: keyword, value: string, version: i64`)
  is this shape exactly. It deliberately does not add `:string`/`:keyword`
  to `sealed-scalar-record` itself -- projection, construction, update, and
  scalar-capability-call's request/result admission all key off
  `sealed-scalar-record` alone, and their WAT emitters
  (`scalar-record-projection-wat`/`scalar-record-write-wat`/
  `record-capability-wat`) only know `wasm-value-type`/`wasm-store`, neither
  of which has a `:string`/`:keyword` case; widening `sealed-scalar-record`
  itself would make those predicates silently admit a shape their own
  emitters cannot correctly generate. This function and its own dedicated
  identity path were the only consumers of the wider field set as of ADR
  0053; ADR 0054 added a second, explicit consumer --
  `record-or-scalar-variant-case?`/`variant-case-schema`, whose matching
  `variant-case-body` emitter was extended in the same change to actually
  handle a string/keyword leaf, so that second consumer does not repeat the
  silent-admission hazard this docstring warns against."
  [descriptor schemas]
  (let [schema (cond
                 (and (vector? descriptor) (= :ref (first descriptor)))
                 (get schemas (second descriptor))
                 (and (vector? descriptor) (= :record (first descriptor))) descriptor)]
    (when (and (vector? schema)
               (= :record (first schema))
               (seq (nth schema 2))
               (= schema (get schemas (second schema)))
               (every? (comp string-keyword-scalar-field? second) (nth schema 2)))
      schema)))

(defn- record-or-scalar-variant-case?
  "True when `payload-type` is a shape ADR 0054 admits as one variant case's
  payload: a Canonical scalar, a sealed all-scalar record (the ADR 0052
  shape), or a sealed flat string/keyword-bearing record (the ADR 0053
  shape). A case payload that is itself an ADR 0051 one-level-nested record,
  or another variant, fails both `sealed-scalar-record` and
  `string-field-record-schema` and so is rejected here too -- this bounds
  every case to exactly the same 'flat record, no nesting, no variant
  payload' depth ADR 0052/0053 each proved on their own, now admitted
  per case in any mix within one variant."
  [payload-type schemas]
  (or (contains? #{:i64 :f32 :f64 :bool} payload-type)
      (boolean (sealed-scalar-record payload-type schemas))
      (boolean (string-field-record-schema payload-type schemas))))

(defn- variant-case-schema
  "Schema of `descriptor` when it is a sealed variant whose every case's
  payload independently satisfies `record-or-scalar-variant-case?`. This
  directly widens ADR 0052's `sealed-scalar-variant-schema` in place (the
  only caller was `variant-identity-function?`, and the codegen side --
  `variant-case-leaves`/`variant-case-body`/`variant-wat` -- is extended in
  the same change to actually emit correct WAT for the newly admitted
  string/keyword-bearing record case shape, so there is no window where this
  predicate admits a case shape its own emitter cannot yet correctly
  generate). Cases may freely mix all three kinds (scalar, all-scalar
  record, string/keyword-bearing record) within one variant -- there is no
  per-variant restriction that every record case be the same kind."
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
                         (record-or-scalar-variant-case? payload-type schemas))
                       (nth schema 2)))
      schema)))

(defn- string-field-record-identity-function? [function schemas]
  (let [{:keys [params param-types result body]} function
        descriptor (first param-types)
        schema (string-field-record-schema descriptor schemas)]
    (and (= 1 (count params))
         (= 1 (count param-types))
         (= descriptor result)
         (= (first params) body)
         schema
         ;; Distinct from `scalar-record-identity-function?`: admitted only
         ;; when at least one field is a string or keyword leaf, so the two
         ;; predicates (and their dispatch cases) never overlap.
         (some (fn [[_ field-type]] (contains? #{:string :keyword} field-type))
               (nth schema 2))
         (canonical/layout descriptor schemas))))

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
        schema (variant-case-schema descriptor schemas)]
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

(defn- variant-capability-case?
  "True when `payload-type` is a shape admitted as one variant case's
  payload *when that variant is used as a `typed-cap-call` request/result*:
  a bare Canonical scalar (`i64`/`f32`/`f64`/`bool`, ADR 0055's original
  scope), a sealed all-scalar record (the ADR 0052 shape, ADR 0056), or --
  new in ADR 0057 -- a sealed flat string/keyword-bearing record (the ADR
  0053 shape, via `string-field-record-schema`). This closes the exact gap
  both ADR 0055 and ADR 0056 named as remaining and unattempted: string/
  keyword data crossing a capability-call boundary at all. It does *not*
  admit a bare `:string`/`:keyword` case payload directly (a case whose own
  payload type is `:string`/`:keyword` with no record wrapper) -- only a
  record field carries string/keyword data across this boundary in this
  slice, matching `state-v1`'s own actual shape (every one of its non-bool
  cases wraps a record, never a bare string). `record-capability-call` (a
  bare record, not wrapped in a variant, as the whole request/result) is
  untouched and still admits scalar fields only -- widening that path is a
  separate, still-unattempted slice this ADR does not attempt, deliberately,
  because `state-v1`'s own request/result are both variants, never a bare
  record."
  [payload-type schemas]
  (or (contains? #{:i64 :f32 :f64 :bool} payload-type)
      (boolean (sealed-scalar-record payload-type schemas))
      (boolean (string-field-record-schema payload-type schemas))))

(defn- variant-capability-schema
  "Schema of `descriptor` when it is a sealed variant whose every case's
  payload independently satisfies `variant-capability-case?`. Structurally
  the same admission shape as `variant-case-schema` (ADR 0052/0054), renamed
  from ADR 0055's own `scalar-variant-capability-schema` now that the case
  set is no longer scalar-only. Kept as a separate function (not a
  parameterization of `variant-case-schema`) so the identity-export path's
  own admitted case set never silently narrows if this one changes, and vice
  versa -- `variant-case-schema` still additionally admits a string/keyword-
  bearing record case, which this one does not."
  [descriptor schemas]
  (let [schema (cond
                 (and (vector? descriptor) (= :ref (first descriptor)))
                 (get schemas (second descriptor))
                 (and (vector? descriptor) (= :variant (first descriptor))) descriptor)]
    (when (and (vector? schema)
               (= :variant (first schema))
               (seq (nth schema 2))
               (= schema (get schemas (second schema)))
               (every? (fn [[_ payload-type]] (variant-capability-case? payload-type schemas))
                       (nth schema 2)))
      schema)))

(defn- variant-capability-call
  "Admission for a direct `typed-cap-call` whose request and result are one
  sealed variant (`variant-capability-schema`), the same request/
  result identity, matching `record-capability-call`'s own same-type
  discipline (ADR 0048) rather than ADR 0046's scalar slice, which allows
  request and result to differ -- widening a *structured* request/result to
  different identities would require the provider to perform a real semantic
  mapping between two distinct shapes, out of scope for a wiring-only
  identity provider slice."
  [function schemas]
  (let [{:keys [params param-types result body]} function
        request-type (first param-types)
        [_ id body-request-type body-result-type request] (when (seq? body) body)
        capability (some #(when (= id (:id %)) %) (:capabilities component-wit/contract))
        request-schema (variant-capability-schema request-type schemas)
        result-schema (variant-capability-schema result schemas)]
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

(defn- asymmetric-variant-capability-case?
  "True when `payload-type` is a shape admitted as one variant case's payload
  for the *different-identity* `typed-cap-call` crossing (ADR 0058): a bare
  Canonical scalar (`i64`/`f32`/`f64`/`bool`) or a sealed all-scalar record
  (the ADR 0052 shape) -- exactly ADR 0055's and ADR 0056's own case-kind
  union. Deliberately narrower than `variant-capability-case?` (the same-
  identity path, which additionally admits ADR 0057's string/keyword-
  bearing record case): a string/keyword leaf crossing a capability boundary
  at all already required real new allocator engineering for the SAME-
  identity path (ADR 0057's capacity-bounded bump allocator, needed because
  the Canonical ABI's own cross-instance string-copy glue calls a module's
  exported `cm32p2_realloc` an unpredictable extra number of times before
  that module's own body runs). Combining that risk with a genuinely
  different request/result identity -- which itself requires new
  engineering (the provider can no longer echo the request case verbatim
  into the result area, since the two shapes are unrelated; both sides'
  result-area sizing must be driven by the RESULT layout rather than a
  layout shared with the request) -- in one step would not be the smallest
  honest increment this chain's own discipline follows (ADR 0055 -> 0056 ->
  0057 each widened by exactly one new dimension, never two at once). Also,
  practically: because this case-kind set never carries a string/keyword
  leaf, the different-identity crossing never triggers the Canonical ABI's
  cross-instance string-copy glue at all, so neither side of this crossing
  needs any string-aware memory sizing -- a plain fixed one-page module and
  the simplest bump allocator suffice, exactly ADR 0055/0056's own pre-0057
  precedent."
  [payload-type schemas]
  (or (contains? #{:i64 :f32 :f64 :bool} payload-type)
      (boolean (sealed-scalar-record payload-type schemas))))

(defn- asymmetric-variant-capability-schema
  "Schema of `descriptor` when it is a sealed variant whose every case's
  payload independently satisfies `asymmetric-variant-capability-case?` --
  the different-identity twin of `variant-capability-schema`, kept as a
  separate function for the same reason `variant-capability-schema` itself
  is kept separate from `variant-case-schema`: so the same-identity path's
  own admitted case set never silently narrows if this one changes, and
  vice versa."
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
                         (asymmetric-variant-capability-case? payload-type schemas))
                       (nth schema 2)))
      schema)))

(defn- different-variant-capability-call
  "Admission for a direct `typed-cap-call` whose request and result are two
  INDEPENDENTLY admitted (`asymmetric-variant-capability-schema`) but
  DIFFERENT sealed variant identities (ADR 0058) -- widening
  `variant-capability-call`'s own same-identity discipline (ADR 0048/0055)
  along the one dimension every capability-call ADR through 0057 explicitly
  named as still unattempted. `state-v1`'s own request
  (`kotoba.state/request`) and result (`kotoba.state/result`) are exactly
  this shape: two different variant identities, never the same type twice.
  Structurally identical to `variant-capability-call` except (1) it checks
  `(not= request-type result)` instead of `(= request-type result)`, so the
  two admission functions are mutually exclusive by construction and never
  both admit the same function, and (2) each side is checked against the
  narrower `asymmetric-variant-capability-schema` (see that function's own
  docstring for why the case-kind set is narrower here)."
  [function schemas]
  (let [{:keys [params param-types result body]} function
        request-type (first param-types)
        [_ id body-request-type body-result-type request] (when (seq? body) body)
        capability (some #(when (= id (:id %)) %) (:capabilities component-wit/contract))
        request-schema (asymmetric-variant-capability-schema request-type schemas)
        result-schema (asymmetric-variant-capability-schema result schemas)]
    (when (and (= 1 (count params))
               (= 1 (count param-types))
               (seq? body)
               (= 'typed-cap-call (first body))
               (= request (first params))
               (= body-request-type request-type)
               (= body-result-type result)
               (not= request-type result)
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
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (variant-capability-call (first exports) (:schemas kir))) :variant-capability-call
      (and (= 1 (count (:functions kir)))
           (= 1 (count exports))
           (different-variant-capability-call (first exports) (:schemas kir)))
      :different-variant-capability-call
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
           (string-field-record-identity-function? (first exports) (:schemas kir))
           (empty? (:effects kir))) :string-field-record-identity
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
  "The ordered `{:relative-offset :descriptor :flat-index}` (plus
  `:max-bytes` on a string/keyword leaf) leaves of one variant case's own
  payload layout: a scalar payload is its own single leaf at relative
  offset 0 and flat-index 0; a sealed all-scalar record payload (ADR 0052)
  or a sealed flat string/keyword-bearing record payload (ADR 0053, ADR
  0054) contributes one leaf per top-level field, in the same order as the
  record's own `:fields`/`:flat`. `flat-index` is now a running sum of each
  preceding field's own flat *width* (1 for a scalar, 2 for a string/keyword
  pointer+length leaf) rather than a plain field index, because a
  string/keyword field's own `:flat` is two core positions
  (`[:i32 :i32]`) -- `flat-index` must still line up with position
  `flat-index` of *this case's own* `flatten_type` sequence, the same
  sequence `variant-flatten-payload` folds every case's flat types into
  position by position starting at index 0 for every case alike; a
  string/keyword leaf's second (`length`) core value sits at `flat-index`+1
  by construction (never re-derived from a separate counter -- see
  `variant-string-leaf-value-exprs`). This does not recurse into nested
  `:fields` (unlike `canonical-abi/layout-leaves`): a variant case payload
  is bounded to the ADR 0043/0053 flat-record shape in this slice, never
  the ADR 0051 one-level-nested shape, so no leaf here is itself a nested
  record."
  [layout]
  (if (contains? layout :fields)
    (loop [remaining (:fields layout) flat-index 0 acc []]
      (if-let [{:keys [offset layout]} (first remaining)]
        (let [descriptor (:descriptor layout)
              max-bytes (:max-bytes layout)
              width (if max-bytes 2 1)
              leaf (cond-> {:relative-offset offset :descriptor descriptor :flat-index flat-index}
                     max-bytes (assoc :max-bytes max-bytes))]
          (recur (next remaining) (+ flat-index width) (conj acc leaf)))
        acc))
    [{:relative-offset 0 :descriptor (:descriptor layout) :flat-index 0}]))

(defn- variant-flat-value-expr
  "The WAT expression that turns the joined-flat param at `flat-index`
  (core type `(nth joined-types flat-index)`) back into a value of core
  type `want`, via `variant-coerce-ops` -- the same un-join step
  `variant-payload-value-expr` already did for a scalar leaf's single
  position, factored out so a string/keyword leaf can call it twice (once
  for its pointer position, once for its length position, both always
  wanting `:i32`)."
  [joined-types flat-index want]
  (let [have (nth joined-types flat-index)]
    (apply str "local.get $p" flat-index
           (map #(str " " %) (variant-coerce-ops have want)))))

(defn- variant-payload-value-expr [joined-types leaf]
  (variant-flat-value-expr joined-types (:flat-index leaf) (core-type-of (:descriptor leaf))))

(defn- variant-string-leaf-value-exprs
  "The `[pointer-expr length-expr]` pair for one string/keyword leaf: its
  pointer sits at `flat-index`, its length at `flat-index`+1, both always
  joined as (or coerced back to) `:i32` -- the identical pointer+length
  linear-memory shape ADR 0040/0041/0053 already gave a bare string
  parameter and a string-field record leaf."
  [joined-types leaf]
  [(variant-flat-value-expr joined-types (:flat-index leaf) :i32)
   (variant-flat-value-expr joined-types (inc (:flat-index leaf)) :i32)])

(defn- variant-case-body
  "The validation and result-area stores for one active variant case,
  covering three leaf shapes now: a plain scalar leaf (unchanged from ADR
  0052 -- range-checked if `:bool`, then stored via `wasm-store`), and a
  string/keyword leaf (new in ADR 0054 -- length checked against its own
  `:max-bytes` and pointer range checked against the module's own `capacity`
  exactly like `string-field-record-wat`'s `validate-parameters`, then
  stored as the pointer+length pair). Both validations are scoped inside
  this case's own branch only, matching the existing bool-validation
  precedent: validating a shared joined position unconditionally, before
  knowing which case is active, would wrongly reject a legitimate payload
  belonging to a different case occupying the same position."
  [payload-offset joined-types capacity leaves]
  (let [validation
        (apply str
               (keep (fn [leaf]
                       (cond
                         (:max-bytes leaf)
                         (let [[pointer length] (variant-string-leaf-value-exprs joined-types leaf)]
                           (str
                            "      " length " i32.const " (:max-bytes leaf)
                            " i32.gt_u if unreachable end\n"
                            "      " pointer " " length " i32.add\n"
                            "      local.tee $end " pointer " i32.lt_u if unreachable end\n"
                            "      local.get $end i32.const " capacity " i32.gt_u if unreachable end\n"))
                         (= :bool (:descriptor leaf))
                         (str "      " (variant-payload-value-expr joined-types leaf)
                              " i32.const 1 i32.gt_u if unreachable end\n")
                         :else nil))
                     leaves))
        stores
        (apply str
               (map (fn [leaf]
                      (if (:max-bytes leaf)
                        (let [[pointer length] (variant-string-leaf-value-exprs joined-types leaf)
                              offset (+ payload-offset (:relative-offset leaf))]
                          (str "      local.get $ret " pointer " i32.store offset=" offset "\n"
                               "      local.get $ret " length " i32.store offset=" (+ offset 4) "\n"))
                        (str "      local.get $ret "
                             (variant-payload-value-expr joined-types leaf)
                             " " (wasm-store (:descriptor leaf))
                             " offset=" (+ payload-offset (:relative-offset leaf)) "\n")))
                    leaves))]
    (str validation stores)))

(defn- variant-case-chain
  "The nested `if`/`else` chain that stores the active case's payload into
  the result area. Every case but the last is guarded by an explicit
  `local.get $disc i32.const <index> i32.eq`; the final case needs no guard
  because the discriminant is already range-checked (`i32.ge_u` against the
  case count) before this chain runs, so falling through every prior `else`
  leaves exactly the last case."
  [cases payload-offset joined-types capacity]
  (letfn [(build [remaining index]
            (let [body (variant-case-body payload-offset joined-types capacity
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
  Canonical scalar, a sealed all-scalar record (ADR 0052), or a sealed flat
  string/keyword-bearing record (ADR 0053, admitted as a case payload for
  the first time here in ADR 0054). The core function receives the variant
  already flattened by the caller's own `canon lower` -- an `i32`
  discriminant plus one core value per joined payload position
  (`canonical/layout`'s `:flat` on a variant descriptor, computed by
  `variant-flatten-payload`, which already folds a string/keyword field's
  own two-position `[:i32 :i32]` flat sequence into the join exactly like
  any other field's flat sequence -- no change was needed there) --
  range-checks the discriminant, allocates the variant's in-memory union
  result area (discriminant byte plus the widest case's payload, from the
  same layout's `:size`/`:alignment`), stores the discriminant, and then,
  in exactly the branch selected by the discriminant, un-joins and stores
  that case's own leaves (`variant-case-chain`/`variant-coerce-ops`,
  `variant-case-body`). This is the same realloc/result-area shape as
  `scalar-record-wat`/`nested-record-wat`/`string-field-record-wat`; the
  new work is the join/coercion table a variant's shared flat positions
  require (ADR 0052, unchanged) plus threading a string/keyword leaf's
  pointer+length pair and its own bounds validation through that same
  per-case branch (ADR 0054). Memory sizing follows
  `string-field-record-wat`'s generous-not-tight precedent, but keyed off
  the *widest single case* (`max-string-leaves-per-case`), not a sum across
  every case -- only one case's own payload is ever validated or stored per
  call, so only one case's own string-like leaf count needs headroom, and a
  variant with no string-like leaf in any case keeps the exact original
  one-page/65536-byte memory this function produced before ADR 0054 (no
  page-count regression for the ADR 0052 shapes already proven)."
  [function schemas]
  (let [export (wit-name (:name function))
        variant-layout (canonical/layout (first (:param-types function)) schemas)
        joined-types (vec (rest (:flat variant-layout)))
        case-leaves (mapv (fn [case] (variant-case-leaves (:layout case))) (:cases variant-layout))
        max-string-leaves-per-case (reduce max 0 (map #(count (filter :max-bytes %)) case-leaves))
        needs-string-headroom? (pos? max-string-leaves-per-case)
        pages (if needs-string-headroom?
                (max 1 (quot (+ 8 (* (inc max-string-leaves-per-case) 65536)
                                (:size variant-layout) 65535)
                             65536))
                1)
        capacity (* pages 65536)
        params (apply str
                      (map-indexed
                       (fn [index core-type]
                         (str " (param $p" index " " (core-type-name core-type) ")"))
                       joined-types))
        case-chain (variant-case-chain (:cases variant-layout)
                                       (:payload-offset variant-layout)
                                       joined-types capacity)]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") " pages " " pages ")\n"
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
     "    local.get $end i32.const " capacity " i32.gt_u if unreachable end\n"
     "    local.get $end global.set $next\n"
     "    local.get $old-ptr i32.eqz if else\n"
     "      local.get $old-size local.get $new-size i32.lt_u\n"
     "      if (result i32) local.get $old-size else local.get $new-size end\n"
     "      local.set $copy-size\n"
     "      local.get $ptr local.get $old-ptr local.get $copy-size memory.copy\n"
     "    end local.get $ptr)\n"
     "  (func (export \"cm32p2||" export "\") (param $disc i32)" params " (result i32)\n"
     "    (local $ret i32)" (when needs-string-headroom? " (local $end i32)") "\n"
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

(defn- string-field-record-wat
  "Identity export for a sealed flat record admitting bounded `string`/
  `keyword` leaves alongside Canonical scalars (`string-field-record-schema`
  -- no nesting, no variant payloads). Every leaf is planned from
  `canonical/layout-leaves`, exactly as `nested-record-wat` already does;
  the only new work is that a leaf carrying `:max-bytes` (a string or
  keyword field) takes two core wasm parameters (`$fN-ptr`/`$fN-len`)
  instead of one and is stored as that same pointer+length pair at its
  field offset -- the identical pointer+length linear-memory shape ADR
  0040/0041 already gave a bare string parameter/result, reused here
  unchanged rather than re-derived: the received pointer already refers to
  guest memory the caller populated (via this module's own `$realloc`)
  before invoking this export, so passthrough needs no byte copy, only the
  same bounds check `string-expression-wat`'s own `validate-parameters`
  already performs (length within the field's own bound, pointer range
  within the module's linear memory) before the pointer is trusted enough
  to store into the result record."
  [function schemas]
  (let [export (wit-name (:name function))
        record-layout (canonical/layout (first (:param-types function)) schemas)
        leaves (canonical/layout-leaves record-layout)
        string-like-count (count (filter :max-bytes leaves))
        required-bytes (+ 8 (* (inc string-like-count) 65536) (:size record-layout))
        pages (max 1 (quot (+ required-bytes 65535) 65536))
        capacity (* pages 65536)
        params (apply str
                      (map-indexed
                       (fn [index {:keys [descriptor max-bytes]}]
                         (if max-bytes
                           (str " (param $f" index "-ptr i32) (param $f" index "-len i32)")
                           (str " (param $f" index " " (wasm-value-type descriptor) ")")))
                       leaves))
        bool-validation
        (apply str
               (keep-indexed
                (fn [index {:keys [descriptor max-bytes]}]
                  (when (and (not max-bytes) (= :bool descriptor))
                    (str "    local.get $f" index
                         " i32.const 1 i32.gt_u if unreachable end\n")))
                leaves))
        string-validation
        (apply str
               (keep-indexed
                (fn [index {:keys [max-bytes]}]
                  (when max-bytes
                    (str
                     "    local.get $f" index "-len i32.const " max-bytes
                     " i32.gt_u if unreachable end\n"
                     "    local.get $f" index "-ptr local.get $f" index "-len i32.add\n"
                     "    local.tee $end local.get $f" index "-ptr i32.lt_u if unreachable end\n"
                     "    local.get $end i32.const " capacity " i32.gt_u if unreachable end\n")))
                leaves))
        stores
        (apply str
               (map-indexed
                (fn [index {:keys [offset descriptor max-bytes]}]
                  (if max-bytes
                    (str "    local.get $ret local.get $f" index "-ptr i32.store offset=" offset "\n"
                         "    local.get $ret local.get $f" index "-len i32.store offset="
                         (+ offset 4) "\n")
                    (str "    local.get $ret local.get $f" index " "
                         (wasm-store descriptor) " offset=" offset "\n")))
                leaves))]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") " pages " " pages ")\n"
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
     "    local.get $end i32.const " capacity " i32.gt_u if unreachable end\n"
     "    local.get $end global.set $next\n"
     "    local.get $old-ptr i32.eqz if else\n"
     "      local.get $old-size local.get $new-size i32.lt_u\n"
     "      if (result i32) local.get $old-size else local.get $new-size end\n"
     "      local.set $copy-size\n"
     "      local.get $ptr local.get $old-ptr local.get $copy-size memory.copy\n"
     "    end local.get $ptr)\n"
     "  (func (export \"cm32p2||" export "\")" params " (result i32)\n"
     "    (local $ret i32) (local $end i32)\n"
     bool-validation
     string-validation
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

(defn- variant-capability-string-headroom
  "`[pages capacity needs-string-headroom?]` for a capability-crossing
  variant layout, the capability-call twin of `variant-wat`'s own generous-
  not-tight sizing formula (same `max-string-leaves-per-case` computation,
  keyed off the *widest single case*, since only one case's own payload is
  ever active per call). New in ADR 0057: every capability-crossing variant
  WAT module before this ADR (`variant-capability-wat`/
  `variant-capability-provider-wat`) fixed its memory at exactly one page
  and never needed headroom, because `variant-capability-case?` admitted no
  string/keyword-bearing leaf. Now that it does, both the application module
  (which must hold a copy of the result's string bytes once the provider's
  result crosses back) and the provider module (which must hold a copy of
  the request's string bytes once they cross in, *and* leave room for its
  own result struct) need the same string-aware sizing `string-field-record-
  wat`/`variant-wat` already established, reused here rather than
  re-derived."
  [variant-layout]
  (let [case-leaves (mapv (fn [case] (variant-case-leaves (:layout case))) (:cases variant-layout))
        max-string-leaves-per-case (reduce max 0 (map #(count (filter :max-bytes %)) case-leaves))
        needs-string-headroom? (pos? max-string-leaves-per-case)
        pages (if needs-string-headroom?
                (max 1 (quot (+ 8 (* (inc max-string-leaves-per-case) 65536)
                                (:size variant-layout) 65535)
                             65536))
                1)]
    [pages (* pages 65536) needs-string-headroom?]))

(defn- bounded-bump-realloc-wat
  "A real bounded bump allocator (`$next`-tracked, alignment-respecting,
  capacity-trapping, old-content-preserving on grow) as a standalone
  `cm32p2_realloc` export body -- exactly `variant-wat`'s own realloc,
  factored out so `variant-capability-wat` and
  `variant-capability-provider-wat` can each use it too (new in ADR 0057:
  neither previously needed more than one, single-purpose allocation per
  call, so a plain unbounded bump pointer, or in the provider's case a fixed
  constant address, was enough; a string/keyword-bearing case now means the
  Canonical ABI's own cross-instance string-copy machinery calls this
  export an *additional*, unpredictable number of times -- once per string-
  like leaf actually crossing, before this module's own body even runs --
  so both callers now need a real allocator that composes safely with those
  extra calls instead of colliding with them, and a capacity bound so an
  oversized string traps rather than silently corrupting memory past the
  module's declared page count)."
  [capacity]
  (str
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
   "    end local.get $ptr)\n"))

(defn- variant-capability-wat
  "Application-side standard32 core module for a direct `typed-cap-call`
  whose request/result is one sealed variant admitted by
  `variant-capability-schema`. The joined component-flat signature (`$disc`
  plus one core param per joined payload position, exactly `variant-wat`'s
  own signature -- `canonical/layout`'s `:flat` on the variant descriptor)
  is unchanged from the identity-export case; this module never itself
  un-joins or stores a case's payload -- it allocates the variant's
  Canonical result area (`realloc` sized to the variant layout's own
  `:size`/`:alignment`, exactly as `variant-wat` does for its own
  self-allocated result), forwards the discriminant and every joined
  payload value plus that result pointer to the imported provider function
  unchanged, and returns the same pointer. This is `record-capability-wat`'s
  exact division of labor (forward flat values plus a caller-allocated
  result pointer to an import that has no core result, matching the
  Canonical ABI's own indirect-result convention for a >1-flat-value
  result) generalized from a record's flat field list to a variant's
  joined `:flat` case-value list. Unlike `record-capability-wat` (which
  validates bool fields on the *application* side before crossing),
  disc-range-checking and per-case validation for a variant can only be
  done correctly by whichever side actually knows which case is active,
  which is exactly the side that performs the case-dispatch store -- here,
  the provider (`variant-capability-provider-wat`), which reuses
  `variant-wat`'s own case-chain (disc range check plus in-branch bool and
  string/keyword-bounds validation) unmodified. The application module
  therefore performs no validation of its own; it is a thin pass-through,
  exactly mirroring `scalar-capability-wat`'s own no-validation precedent
  for a single scalar leaf, now applied to every joined position of a
  variant, string/keyword pointer+length pairs included -- new in ADR 0057
  is only that this module's own memory/`$realloc` must now tolerate the
  *additional* realloc calls the Canonical ABI's own cross-instance string
  lowering makes when copying a result string's bytes back into this
  module's memory (`variant-capability-string-headroom`/
  `bounded-bump-realloc-wat`, the same string-aware sizing and bounded
  allocator `variant-wat` already uses for its own single-module string
  leaves); the WAT emitted for a case with no string-like leaf at all is
  otherwise unchanged in shape from ADR 0055/0056 (still one page, still
  the same bump-pointer body, now just capacity-checked).

  ADR 0058 generalizes this function to a REQUEST layout and a (possibly
  different) RESULT layout, computed independently from `(:request plan)`
  and `(:result plan)`, rather than one shared `variant-layout` computed
  from the request alone and silently reused for the result area too. This
  is a genuine bug fix, not a cosmetic rename: the result area this module
  allocates and hands to the provider as an out-pointer must be sized to
  hold a RESULT-shaped value (the provider writes a result into it), which
  for every ADR 0055/0056/0057 fixture happened to be correct only because
  their request and result were, by construction, the identical schema --
  sizing it from the request layout was silently relying on that
  coincidence. For the different-identity case (ADR 0058) request and
  result layouts genuinely differ, so this fix is required for correctness,
  not merely for symmetry; for the same-identity case it is a no-op
  (`request-layout` and `result-layout` are structurally `=`, confirmed by
  rebuilding and re-running the ADR 0055/0056/0057 fixtures unchanged
  through this generalized function). The joined param signature and
  string headroom are still sized from the REQUEST layout only, unchanged:
  this module forwards the request's own joined payload values to the
  import exactly as before, and (per `asymmetric-variant-capability-case?`'s
  own docstring) the different-identity path never admits a string/keyword
  leaf on either side, so request-only headroom sizing remains correct for
  it too -- a genuinely wider future slice that combines asymmetry with
  string/keyword crossing would need to revisit this, and is explicitly not
  attempted here."
  [function schemas plan]
  (let [export (wit-name (:name function))
        capability (:capability plan)
        request-layout (canonical/layout (:request plan) schemas)
        result-layout (canonical/layout (:result plan) schemas)
        joined-types (vec (rest (:flat request-layout)))
        [pages capacity _] (variant-capability-string-headroom request-layout)
        payload-params (apply str
                              (map (fn [core-type] (str " (param " (core-type-name core-type) ")"))
                                   joined-types))
        import-params (str " (param i32)" payload-params)
        params (apply str
                      (cons " (param $disc i32)"
                            (map-indexed
                             (fn [index core-type]
                               (str " (param $p" index " " (core-type-name core-type) ")"))
                             joined-types)))
        arguments (apply str
                         (cons "    local.get $disc\n"
                               (map-indexed (fn [index _] (str "    local.get $p" index "\n"))
                                            joined-types)))]
    (str
     "(module\n"
     "  (import \"cm32p2|kotoba:application/" (:interface capability) "@1\" \""
     (:function capability) "\" (func $provider" import-params " (param i32)))\n"
     "  (memory (export \"cm32p2_memory\") " pages " " pages ")\n"
     "  (global $next (mut i32) (i32.const 8))\n"
     (bounded-bump-realloc-wat capacity)
     "  (func (export \"cm32p2||" export "\")" params " (result i32)\n"
     "    (local $ret i32) (local $end i32)\n"
     "    i32.const 0 i32.const 0 i32.const " (:alignment result-layout)
     " i32.const " (:size result-layout) " call $realloc local.set $ret\n"
     arguments
     "    local.get $ret call $provider local.get $ret)\n"
     "  (func (export \"cm32p2||" export "_post\") (param i32)\n"
     "    i32.const 8 global.set $next)\n"
     "  (func (export \"cm32p2_initialize\") i32.const 8 global.set $next))\n")))

(defn variant-capability-provider-wat
  "Wiring-only provider core module for one sealed variant admitted by
  `variant-capability-schema` (ADR 0055/0056/0057) -- public so
  `kotoba.compiler.component-composition` can build a provider artifact for
  it (mirroring `kotoba.compiler.component-composition/record-provider-wat`,
  which duplicates `record-capability-wat`'s own store shape locally rather
  than calling into this namespace; this function is exposed directly
  instead because the case-chain it reuses, `variant-case-chain`, is
  materially more involved than a flat record's own field loop and
  duplicating it would risk the two copies silently drifting). `entry` is a
  `{:interface :function}` capability map (the same shape
  `component-wit/contract`'s own `:capabilities` entries and
  `component-composition`'s local `capability` lookup already use).
  Reuses `variant-wat`'s exact case-chain (disc range check, then in-branch
  bool and, since ADR 0057 widened admission, string/keyword-bounds
  validation and store for the active case) unchanged.

  New in ADR 0057: the result pointer is no longer the fixed minimal
  address ADR 0055/0056's identity-only providers used (`i32.const 8`, no
  dynamic allocation) -- it is now allocated through this module's own
  `cm32p2_realloc` (`bounded-bump-realloc-wat`), exactly the way every
  other struct-producing WAT emitter in this namespace already allocates
  its own result area. This is not a cosmetic change: once a case's payload
  can carry a string/keyword leaf, the Canonical ABI's own cross-instance
  string-copy machinery calls this module's *exported* `cm32p2_realloc`
  itself -- once per string-like leaf in the active request case -- to copy
  each leaf's bytes into this module's memory *before* this module's own
  function body runs at all. A fixed constant-address realloc (correct only
  when it is ever the sole allocation in a call, true for every case shape
  ADR 0055/0056 admitted) would silently collide: the incoming string
  bytes and this function's own result struct would land at the identical
  address, corrupting whichever was written second. Routing the result
  struct through the same bump allocator the string copies already use
  keeps every allocation in one call sequential and non-overlapping,
  regardless of call order between this module's own body and the
  generated glue -- a plain consequence of a bump allocator's own
  construction, not case-specific logic. A case with no string-like leaf at
  all is unaffected in behavior: with no extra realloc call preceding it,
  the bump allocator's first call still returns the same fixed address 8
  ADR 0055/0056 hard-coded, for the same alignment reason `variant-wat`'s
  own realloc already establishes (8 is a multiple of every alignment this
  codebase's Canonical ABI layouts produce, so the bump math's first result
  is always exactly 8)."
  [entry descriptor schemas]
  (let [export (str "cm32p2|kotoba:application/" (:interface entry) "@1|" (:function entry))
        variant-layout (canonical/layout descriptor schemas)
        joined-types (vec (rest (:flat variant-layout)))
        [pages capacity needs-string-headroom?] (variant-capability-string-headroom variant-layout)
        params (apply str
                      (cons " (param $disc i32)"
                            (map-indexed
                             (fn [index core-type]
                               (str " (param $p" index " " (core-type-name core-type) ")"))
                             joined-types)))
        case-chain (variant-case-chain (:cases variant-layout)
                                       (:payload-offset variant-layout)
                                       joined-types capacity)]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") " pages " " pages ")\n"
     "  (global $next (mut i32) (i32.const 8))\n"
     (bounded-bump-realloc-wat capacity)
     "  (func (export \"" export "\")" params " (result i32)\n"
     "    (local $ret i32)" (when needs-string-headroom? " (local $end i32)") "\n"
     "    i32.const 0 i32.const 0 i32.const " (:alignment variant-layout)
     " i32.const " (:size variant-layout) " call $realloc local.set $ret\n"
     "    local.get $disc i32.const " (count (:cases variant-layout)) " i32.ge_u if unreachable end\n"
     "    local.get $ret local.get $disc "
     (variant-disc-store (:discriminant-size variant-layout)) " offset=0\n"
     case-chain
     "    local.get $ret)\n"
     "  (func (export \"" export "_post\") (param i32)\n"
     "    i32.const 8 global.set $next)\n"
     "  (func (export \"cm32p2_initialize\") i32.const 8 global.set $next))\n")))

(defn- constant-leaf-wat
  "A deterministic literal WAT push instruction for one Canonical scalar leaf
  of an asymmetric provider's fixed result payload (ADR 0058), distinct per
  `(case-index, leaf-index)` pair so a real round trip can tell two
  different chosen result cases -- and two different leaves within the same
  case -- apart, rather than every leaf silently sharing one constant (which
  would make the evidence weaker at distinguishing 'stored the right value
  at the right offset' from 'stored one lucky constant everywhere'). This
  value is never derived from any request payload leaf -- only from the
  chosen result case's own static index and the leaf's own position within
  it -- matching `asymmetric-variant-capability-provider-wat`'s own
  'wiring-only, not semantic' framing."
  [descriptor case-index leaf-index]
  (let [n (+ (* case-index 101) (* leaf-index 7) 3)]
    (case descriptor
      :bool (str "i32.const " (mod n 2))
      :i64 (str "i64.const " n)
      :f32 (str "f32.const " n)
      :f64 (str "f64.const " n))))

(defn- asymmetric-result-case-store
  "The stores for one RESULT case's own fixed constant payload, reusing
  `variant-case-leaves` (unmodified) to find each leaf's own relative offset
  and descriptor -- exactly `variant-case-body`'s store half, with
  `constant-leaf-wat` standing in for a request-derived value and no
  validation at all (a compile-time constant is trivially in-bounds)."
  [result-payload-offset case-index layout]
  (let [leaves (variant-case-leaves layout)]
    (apply str
           (map-indexed
            (fn [leaf-index {:keys [relative-offset descriptor]}]
              (str "      local.get $ret " (constant-leaf-wat descriptor case-index leaf-index)
                   " " (wasm-store descriptor)
                   " offset=" (+ result-payload-offset relative-offset) "\n"))
            leaves))))

(defn- asymmetric-provider-dispatch-chain
  "The nested `if`/`else` chain, dispatched on the REQUEST's own
  discriminant, that writes a FIXED result value into `$ret` for an
  asymmetric-identity provider (ADR 0058). One deterministic output case is
  chosen per request case via `(mod request-case-index (count
  result-cases))`, so every request case maps to some valid result case
  even when the two case counts differ (e.g. `state-v1`'s own 3 request
  cases vs. 5 result cases), and every possible request discriminant is
  provably distinguishable in a round trip (`constant-leaf-wat` varies by
  the chosen output case's own index). This is wiring-only, not a semantic
  mapping: no request PAYLOAD leaf value is ever read here, only the
  request's own discriminant (already range-checked by the caller before
  this chain runs), to select purely which fixed result case gets written."
  [request-case-count result-cases result-disc-size result-payload-offset]
  (let [result-case-count (count result-cases)]
    (letfn [(build [index]
              (let [output-index (mod index result-case-count)
                    output-layout (:layout (nth result-cases output-index))
                    body (str "      local.get $ret i32.const " output-index " "
                              (variant-disc-store result-disc-size) " offset=0\n"
                              (asymmetric-result-case-store result-payload-offset output-index
                                                            output-layout))]
                (if (= index (dec request-case-count))
                  body
                  (str "    local.get $disc i32.const " index " i32.eq\n"
                       "    if\n" body
                       "    else\n" (build (inc index))
                       "    end\n"))))]
      (build 0))))

(defn asymmetric-variant-capability-provider-wat
  "Wiring-only provider core module for a `typed-cap-call` whose request and
  result are two genuinely DIFFERENT sealed variant identities (ADR 0058),
  each independently admitted by `asymmetric-variant-capability-schema`
  (scalar or sealed all-scalar record cases only -- string/keyword-bearing
  cases are out of scope for this crossing, see that function's own
  docstring for why). Unlike `variant-capability-provider-wat` (whose whole
  semantic IS echoing the active request case verbatim into a result area
  of the identical shape, only meaningful when request and result share one
  schema), this provider cannot echo a request case into a result case of a
  genuinely different, unrelated shape. It is a deliberately simple,
  explicitly non-semantic wiring fixture instead, matching the task's own
  framing exactly: it range-checks the request discriminant, never reads
  any request PAYLOAD leaf, and writes one of the result variant's own
  cases with a fixed compile-time-constant payload, chosen deterministically
  from the request's own discriminant alone
  (`asymmetric-provider-dispatch-chain`) -- this is NOT `state`'s real
  semantics (no request payload value ever informs the result's own value,
  only which case is chosen), exactly the same 'wiring only' framing every
  prior capability-call ADR's own identity provider already carried, now
  applied to a provider that (unlike an identity provider) cannot even in
  principle be semantically neutral, because request and result are
  different types. Because neither admitted case kind in this slice ever
  carries a string/keyword leaf, the Canonical ABI's own cross-instance
  string-copy glue never triggers for this crossing, so this module needs
  none of `variant-capability-provider-wat`'s ADR 0057 headroom-sizing
  machinery -- a fixed one page and the simplest bump realloc
  (`bounded-bump-realloc-wat` with a flat 65536-byte capacity) suffice,
  matching ADR 0055/0056's own pre-0057 precedent."
  [entry request-descriptor result-descriptor schemas]
  (let [export (str "cm32p2|kotoba:application/" (:interface entry) "@1|" (:function entry))
        request-layout (canonical/layout request-descriptor schemas)
        result-layout (canonical/layout result-descriptor schemas)
        joined-types (vec (rest (:flat request-layout)))
        params (apply str
                      (cons " (param $disc i32)"
                            (map-indexed
                             (fn [index core-type]
                               (str " (param $p" index " " (core-type-name core-type) ")"))
                             joined-types)))
        dispatch (asymmetric-provider-dispatch-chain
                  (count (:cases request-layout)) (:cases result-layout)
                  (:discriminant-size result-layout) (:payload-offset result-layout))]
    (str
     "(module\n"
     "  (memory (export \"cm32p2_memory\") 1 1)\n"
     "  (global $next (mut i32) (i32.const 8))\n"
     (bounded-bump-realloc-wat 65536)
     "  (func (export \"" export "\")" params " (result i32)\n"
     "    (local $ret i32)\n"
     "    local.get $disc i32.const " (count (:cases request-layout)) " i32.ge_u if unreachable end\n"
     "    i32.const 0 i32.const 0 i32.const " (:alignment result-layout)
     " i32.const " (:size result-layout) " call $realloc local.set $ret\n"
     dispatch
     "    local.get $ret)\n"
     "  (func (export \"" export "_post\") (param i32)\n"
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
    :string-field-record-identity
    (wasm-tools/parse-wat
     (string-field-record-wat (first (exported-functions kir)) (:schemas kir)))
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
                              (record-capability-call function (:schemas kir)))))
    :variant-capability-call
    (let [function (first (exported-functions kir))]
      (wasm-tools/parse-wat
       (variant-capability-wat function (:schemas kir)
                               (variant-capability-call function (:schemas kir)))))
    :different-variant-capability-call
    (let [function (first (exported-functions kir))]
      (wasm-tools/parse-wat
       (variant-capability-wat function (:schemas kir)
                               (different-variant-capability-call function (:schemas kir)))))))
