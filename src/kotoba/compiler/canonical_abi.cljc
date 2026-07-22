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
    (and (vector? descriptor) (= :ref (first descriptor)))
    (record-layout descriptor schemas visited)
    (and (vector? descriptor) (= :record (first descriptor)))
    (let [identity (second descriptor)]
      (when-not (= descriptor (get schemas identity))
        (reject "inline record differs from sealed schema identity"
                {:descriptor descriptor :schema (get schemas identity)}))
      (assoc (record-layout [:ref identity] schemas visited) :descriptor descriptor))
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
  of `{:offset absolute-byte-offset :descriptor scalar-descriptor}` leaves, in
  the same left-to-right depth-first order as the layout's own `:flat`
  vector. A field whose own layout is itself a nested record layout (that is,
  it carries a `:fields` key) is recursed into so every nested scalar leaf
  gets its own absolute store/load offset; this is how one level of nested
  aggregate lowering reuses the same flat-scalar codegen as a plain record.
  Every consumer must still bound nesting depth before calling this (`layout`
  itself only rejects unbounded recursive schemas, not depth generally)."
  ([record-layout] (layout-leaves record-layout 0))
  ([record-layout base-offset]
   (vec
    (mapcat (fn [{:keys [offset layout]}]
              (let [absolute (+ base-offset offset)]
                (if (contains? layout :fields)
                  (layout-leaves layout absolute)
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
