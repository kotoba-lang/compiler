(ns kotoba.compiler.canonical-abi
  "Checked Canonical ABI layout plans. This namespace describes transport;
  it does not admit a descriptor for component emission by itself."
  (:require [kotoba.compiler.value :as value]))

(def profile :component-model/standard32-v1)
(def string-encoding :utf8)

(defn- reject [message data]
  (throw (ex-info message (assoc data :phase :canonical-abi-layout))))

(defn- align-up [value alignment]
  (* alignment (quot (+ value (dec alignment)) alignment)))

(declare layout*)

(defn- discriminant-byte-size
  "In-memory byte width of a variant discriminant for `case-count` cases,
  mirroring the Component Model spec's `discriminant_type`: u8 up to 256
  cases, u16 up to 65536, u32 beyond (`variant-case-limit` in
  `kotoba.compiler.value` keeps every admitted variant well under the u8
  boundary today; the wider cases are implemented for spec fidelity, not
  because anything in this codebase currently produces them)."
  [case-count]
  (cond (<= case-count 256) 1
        (<= case-count 65536) 2
        :else 4))

(defn- join-core-type
  "The Component Model spec's `join`: the single core wasm value type two
  positions of two different variant cases' flattened payloads must share
  when they occupy the same flat position. Identical types need no
  coercion; an i32/f32 mismatch (a scalar/bool case sharing a position with
  an f32 case) still fits in i32; every other mismatch (anything touching
  i64 or f64 against a different type) widens to i64. This is intentionally
  exactly the three-line spec function, not a superset or a subset of it."
  [a b]
  (cond (= a b) a
        (= #{a b} #{:i32 :f32}) :i32
        :else :i64))

(defn- variant-flatten-payload
  "Fold every case's own flat core-type sequence into one joined sequence,
  left to right, matching `flatten_variant`'s loop: each case's own flat
  types are compared position-by-position against whatever the fold has
  already accumulated there (via `join-core-type`), and any position past
  the fold's current length is simply appended. This is a component-level
  flattening (the shape the guest receives as bare core wasm parameters),
  distinct from the in-memory union layout `variant-layout` computes below
  for the result area (see docstring there)."
  [case-layouts]
  (reduce
   (fn [flat {:keys [layout]}]
     (let [case-flat (:flat layout)]
       (loop [index 0 acc flat]
         (if (>= index (count case-flat))
           acc
           (let [core-type (nth case-flat index)]
             (recur (inc index)
                    (if (< index (count acc))
                      (update acc index join-core-type core-type)
                      (conj acc core-type))))))))
   []
   case-layouts))

(defn- variant-layout
  "Checked Canonical ABI layout for one sealed variant schema: an in-memory
  union (discriminant byte width from `discriminant-byte-size`, aligned
  payload area sized to the widest case, per `elem_size_variant`/
  `alignment_variant` in the Component Model spec) used to plan the
  indirect-result area, plus a component-level `:flat` core-value sequence
  ([i32 discriminant] ++ `variant-flatten-payload`) used to plan the wasm
  core function signature when this variant is a direct parameter or
  result. Unlike a record, a variant's memory layout and its flat core
  signature are genuinely different shapes (a union vs. a join), so both
  are computed and kept side by side on the same layout map rather than
  reusing one `:flat` for both purposes the way record layouts do."
  [descriptor schemas visited]
  (let [identity (second descriptor)
        schema (get schemas identity)]
    (when (contains? visited identity)
      (reject "recursive schema has no bounded Canonical ABI layout"
              {:descriptor descriptor :identity identity}))
    (when-not (and (vector? schema) (= :variant (first schema)) (= identity (second schema)))
      (reject "variant reference has no matching schema identity"
              {:descriptor descriptor :schema schema}))
    (let [cases (nth schema 2)
          case-layouts (mapv (fn [[tag payload-type]]
                               {:tag tag
                                :layout (layout* payload-type schemas (conj visited identity))})
                             cases)
          discriminant-size (discriminant-byte-size (count cases))
          payload-alignment (reduce max 1 (map (comp :alignment :layout) case-layouts))
          payload-offset (align-up discriminant-size payload-alignment)
          payload-size (reduce max 0 (map (comp :size :layout) case-layouts))
          alignment (max discriminant-size payload-alignment)]
      {:descriptor descriptor
       :identity identity
       :kind :variant
       :cases case-layouts
       :discriminant-size discriminant-size
       :payload-offset payload-offset
       :alignment alignment
       :size (align-up (+ payload-offset payload-size) alignment)
       :flat (into [:i32] (variant-flatten-payload case-layouts))})))

(defn- record-layout [descriptor schemas visited]
  (let [identity (second descriptor)
        schema (get schemas identity)]
    (when (contains? visited identity)
      (reject "recursive schema has no bounded Canonical ABI layout"
              {:descriptor descriptor :identity identity}))
    (when-not (and (vector? schema) (= :record (first schema)) (= identity (second schema)))
      (reject "record reference has no matching schema identity"
              {:descriptor descriptor :schema schema}))
    (let [fields (nth schema 2)
          planned
          (loop [remaining fields offset 0 alignment 1 result []]
            (if-let [[field field-type] (first remaining)]
              (let [field-layout (layout* field-type schemas (conj visited identity))
                    field-offset (align-up offset (:alignment field-layout))]
                (recur (next remaining)
                       (+ field-offset (:size field-layout))
                       (max alignment (:alignment field-layout))
                       (conj result {:name field :offset field-offset :layout field-layout})))
              {:fields result :alignment alignment :end offset}))]
      {:descriptor descriptor
       :identity identity
       :size (align-up (:end planned) (:alignment planned))
       :alignment (:alignment planned)
       :flat (vec (mapcat (comp :flat :layout) (:fields planned)))
       :fields (:fields planned)})))

(defn- layout* [descriptor schemas visited]
  (cond
    (= descriptor :i64) {:descriptor :i64 :size 8 :alignment 8 :flat [:i64]}
    (= descriptor :f32) {:descriptor :f32 :size 4 :alignment 4 :flat [:f32]}
    (= descriptor :f64) {:descriptor :f64 :size 8 :alignment 8 :flat [:f64]}
    (= descriptor :bool) {:descriptor :bool :size 1 :alignment 1 :flat [:i32]
                          :validation [:canonical-bool]}
    (= descriptor :string) {:descriptor :string
                            :size 8
                            :alignment 4
                            :flat [:i32 :i32]
                            :encoding string-encoding
                            :max-bytes value/string-value-byte-limit
                            :validation [:checked-pointer-range :valid-utf8]}
    (= descriptor :keyword) {:descriptor :keyword
                             :size 8
                             :alignment 4
                             :flat [:i32 :i32]
                             :encoding string-encoding
                             :max-bytes value/keyword-value-byte-limit
                             :validation [:checked-pointer-range :valid-utf8]}
    (= descriptor :symbol) {:descriptor :symbol
                            :size 8
                            :alignment 4
                            :flat [:i32 :i32]
                            :encoding string-encoding
                            :max-bytes value/symbol-value-byte-limit
                            :validation [:checked-pointer-range :valid-utf8
                                         :valid-symbol-source]}
    (and (vector? descriptor) (= :ref (first descriptor)))
    (let [schema (get schemas (second descriptor))]
      (if (and (vector? schema) (= :variant (first schema)))
        (variant-layout descriptor schemas visited)
        (record-layout descriptor schemas visited)))
    (and (vector? descriptor) (= :record (first descriptor)))
    (let [identity (second descriptor)]
      (when-not (= descriptor (get schemas identity))
        (reject "inline record differs from sealed schema identity"
                {:descriptor descriptor :schema (get schemas identity)}))
      (assoc (record-layout [:ref identity] schemas visited) :descriptor descriptor))
    (and (vector? descriptor) (= :variant (first descriptor)))
    (let [identity (second descriptor)]
      (when-not (= descriptor (get schemas identity))
        (reject "inline variant differs from sealed schema identity"
                {:descriptor descriptor :schema (get schemas identity)}))
      (assoc (variant-layout [:ref identity] schemas visited) :descriptor descriptor))
    :else
    (reject "descriptor has no qualified Canonical ABI layout"
            {:descriptor descriptor})))

(defn layout
  "Return the closed standard32 memory and flat-value layout for one admitted
  descriptor. Aggregate descriptors are added only with matching lift/lower
  implementations."
  ([descriptor] (layout descriptor {}))
  ([descriptor schemas] (layout* descriptor schemas #{})))

(defn layout-leaves
  "Flatten one record layout (as returned by `layout`) into an ordered list
  of leaves, in the same left-to-right depth-first order as the layout's own
  `:flat` vector. A field whose own layout is itself a nested record layout
  (that is, it carries a `:fields` key) is recursed into so every nested leaf
  gets its own absolute store/load offset; this is how one level of nested
  aggregate lowering reuses the same flat-scalar codegen as a plain record.
  A field whose own layout is a bounded `string`/`keyword` (carries a
  `:max-bytes` key, the same pointer+length linear-memory shape ADR 0040/0041
  already gave bare string parameters/results) is exposed as
  `{:offset :descriptor :max-bytes}` instead of the plain scalar leaf's
  `{:offset :descriptor}`, so callers can tell a two-core-value pointer+length
  leaf from a one-core-value scalar leaf without re-deriving it from the
  descriptor. Every consumer must still bound nesting depth before calling
  this (`layout` itself only rejects unbounded recursive schemas, not depth
  generally)."
  ([record-layout] (layout-leaves record-layout 0))
  ([record-layout base-offset]
   (vec
    (mapcat (fn [{:keys [offset layout]}]
              (let [absolute (+ base-offset offset)]
                (cond
                  (contains? layout :fields)
                  (layout-leaves layout absolute)

                  (contains? layout :max-bytes)
                  [{:offset absolute :descriptor (:descriptor layout)
                    :max-bytes (:max-bytes layout)}]

                  :else
                  [{:offset absolute :descriptor (:descriptor layout)}])))
            (:fields record-layout)))))

(defn export-plan
  "Plan the standard32 core signature and result-area contract for one KIR
  export. Multi-flat results use the Canonical ABI indirect-result convention."
  ([function] (export-plan function {}))
  ([{:keys [name params param-types result]} schemas]
  (when-not (= (count params) (count param-types))
    (reject "parameter names and types differ in arity"
            {:function name :params (count params) :types (count param-types)}))
  (let [parameter-layouts (mapv #(layout % schemas) param-types)
        result-layout (layout result schemas)
        result-flat (:flat result-layout)
        indirect-result? (> (count result-flat) 1)]
    {:profile profile
     :function name
     :parameters (mapv (fn [parameter abi-layout]
                         {:name parameter :layout abi-layout})
                       params parameter-layouts)
     :core-params (vec (mapcat :flat parameter-layouts))
     :result-layout result-layout
     :indirect-result? indirect-result?
     :core-results (if indirect-result? [:i32] result-flat)
     :post-return-params (if indirect-result? [:i32] result-flat)})))
