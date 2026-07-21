(ns kotoba.compiler.canonical-abi
  "Checked Canonical ABI layout plans. This namespace describes transport;
  it does not admit a descriptor for component emission by itself."
  (:require [kotoba.compiler.value :as value]))

(def profile :component-model/standard32-v1)
(def string-encoding :utf8)

(defn- reject [message data]
  (throw (ex-info message (assoc data :phase :canonical-abi-layout))))

(defn layout
  "Return the closed standard32 memory and flat-value layout for one admitted
  descriptor. Aggregate descriptors are added only with matching lift/lower
  implementations."
  [descriptor]
  (case descriptor
    :i64 {:descriptor :i64 :size 8 :alignment 8 :flat [:i64]}
    :f32 {:descriptor :f32 :size 4 :alignment 4 :flat [:f32]}
    :f64 {:descriptor :f64 :size 8 :alignment 8 :flat [:f64]}
    :string {:descriptor :string
             :size 8
             :alignment 4
             :flat [:i32 :i32]
             :encoding string-encoding
             :max-bytes value/string-value-byte-limit
             :validation [:checked-pointer-range :valid-utf8]}
    (reject "descriptor has no qualified Canonical ABI layout"
            {:descriptor descriptor})))

(defn export-plan
  "Plan the standard32 core signature and result-area contract for one KIR
  export. Multi-flat results use the Canonical ABI indirect-result convention."
  [{:keys [name params param-types result]}]
  (when-not (= (count params) (count param-types))
    (reject "parameter names and types differ in arity"
            {:function name :params (count params) :types (count param-types)}))
  (let [parameter-layouts (mapv layout param-types)
        result-layout (layout result)
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
     :post-return-params (if indirect-result? [:i32] result-flat)}))
