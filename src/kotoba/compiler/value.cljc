(ns kotoba.compiler.value
  #?(:cljs (:require [kotoba.compiler.cljs-i64 :as i64])))

(def string-literal-byte-limit 4096)
(def string-value-byte-limit 65536)
(def keyword-value-byte-limit 512)
(def map-entry-limit 128)
(def vector-item-limit 128)
(def adt-depth-limit 8)
(def adt-node-limit 64)
(def variant-case-limit 32)

(defn utf8-byte-count!
  "Return the exact UTF-8 byte count without normalizing or replacing malformed
  UTF-16. Unpaired surrogates fail closed on both JVM and JavaScript hosts."
  [value]
  (when-not (string? value)
    (throw (ex-info "value is not a string" {:phase :value :value value})))
  (loop [index 0 total 0]
    (if (= index (count value))
      total
      (let [unit #?(:clj (int (.charAt ^String value index))
                    :cljs (.charCodeAt value index))]
        (cond
          (<= unit 0x7f) (recur (inc index) (inc total))
          (<= unit 0x7ff) (recur (inc index) (+ total 2))
          (<= 0xd800 unit 0xdbff)
          (if (< (inc index) (count value))
            (let [next-unit #?(:clj (int (.charAt ^String value (inc index)))
                               :cljs (.charCodeAt value (inc index)))]
              (if (<= 0xdc00 next-unit 0xdfff)
                (recur (+ index 2) (+ total 4))
                (throw (ex-info "string contains an unpaired high surrogate"
                                {:phase :value :index index}))))
            (throw (ex-info "string contains an unpaired high surrogate"
                            {:phase :value :index index})))
          (<= 0xdc00 unit 0xdfff)
          (throw (ex-info "string contains an unpaired low surrogate"
                          {:phase :value :index index}))
          :else (recur (inc index) (+ total 3)))))))

(defn bounded-string!
  [value limit]
  (let [bytes (utf8-byte-count! value)]
    (when (> bytes limit)
      (throw (ex-info "string exceeds UTF-8 byte limit"
                      {:phase :value :bytes bytes :limit limit})))
    value))

(defn bounded-keyword!
  [value limit]
  (when-not (keyword? value)
    (throw (ex-info "value is not a keyword" {:phase :value :value value})))
  (let [text (str value)
        bytes (utf8-byte-count! text)]
    (when (> bytes limit)
      (throw (ex-info "keyword exceeds UTF-8 byte limit"
                      {:phase :value :bytes bytes :limit limit})))
    value))

(defn bounded-map!
  "Validate the first bounded map profile: canonical keyword keys and i64
  values only. The representation is immutable host data, never a pointer or
  integer sentinel in the Kotoba value domain."
  [value]
  (when-not (map? value)
    (throw (ex-info "value is not a map" {:phase :value :value value})))
  (when (> (count value) map-entry-limit)
    (throw (ex-info "map exceeds entry limit"
                    {:phase :value :entries (count value) :limit map-entry-limit})))
  (doseq [[key item] value]
    (bounded-keyword! key keyword-value-byte-limit)
    (when-not #?(:clj (and (integer? item)
                            (<= Long/MIN_VALUE item Long/MAX_VALUE))
                 :cljs (and (i64/bigint-value? item)
                            (i64/in-i64-range? item)))
      (throw (ex-info "map value is not a signed i64"
                      {:phase :value :key key}))))
  value)

(defn bounded-option-i64!
  "Validate the first option profile. None is `[false]`; some i64 is
  `[true value]`. Nil, host null/undefined, and integer sentinels are never
  members of the runtime value domain. Return a canonical immutable value."
  [value]
  (when-not (and (vector? value)
                 (or (= [false] value)
                     (and (= 2 (count value)) (true? (first value)))))
    (throw (ex-info "value is not a tagged option-i64"
                    {:phase :value :value value})))
  (if (false? (first value))
    [false]
    (let [item (second value)]
      (when-not #?(:clj (and (integer? item)
                              (<= Long/MIN_VALUE item Long/MAX_VALUE))
                   :cljs (and (i64/bigint-value? item)
                              (i64/in-i64-range? item)))
        (throw (ex-info "option payload is not a signed i64" {:phase :value})))
      [true item])))

(defn bounded-result-i64!
  "Validate the first algebraic-result profile. `[true value]` is ok and
  `[false error]` is err; both variants carry exactly one signed-i64 payload."
  [value]
  (when-not (and (vector? value) (= 2 (count value)) (boolean? (first value)))
    (throw (ex-info "value is not a tagged result-i64"
                    {:phase :value :value value})))
  (let [item (second value)]
    (when-not #?(:clj (and (integer? item) (<= Long/MIN_VALUE item Long/MAX_VALUE))
                 :cljs (and (i64/bigint-value? item) (i64/in-i64-range? item)))
      (throw (ex-info "result payload is not a signed i64" {:phase :value})))
    [(first value) item]))

(defn bounded-vector-i64!
  "Validate and return the first bounded sequential collection profile."
  [value]
  (when-not (vector? value)
    (throw (ex-info "value is not a vector-i64" {:phase :value :value value})))
  (when (> (count value) vector-item-limit)
    (throw (ex-info "vector exceeds item limit"
                    {:phase :value :items (count value) :limit vector-item-limit})))
  (doseq [item value]
    (when-not #?(:clj (and (integer? item) (<= Long/MIN_VALUE item Long/MAX_VALUE))
                 :cljs (and (i64/bigint-value? item) (i64/in-i64-range? item)))
      (throw (ex-info "vector item is not a signed i64" {:phase :value}))))
  value)

(def ^:private leaf-value-types
  #{:i64 :string :keyword :map :bool :option-i64 :result-i64 :vector-i64})

(defn validate-value-type!
  ([type] (validate-value-type! type 0 (volatile! 0)))
  ([type depth nodes]
   (vswap! nodes inc)
   (when (> @nodes adt-node-limit)
     (throw (ex-info "value type exceeds node limit" {:phase :value :limit adt-node-limit})))
   (when (> depth adt-depth-limit)
     (throw (ex-info "value type exceeds depth limit" {:phase :value :limit adt-depth-limit})))
   (cond
     (contains? leaf-value-types type) type
     (and (vector? type) (= 3 (count type)) (= :result (first type)))
     (do (validate-value-type! (second type) (inc depth) nodes)
         (validate-value-type! (nth type 2) (inc depth) nodes)
         type)
     (and (vector? type) (= 3 (count type)) (= :variant (first type)))
     (let [[_ type-id cases] type]
       (when-not (and (keyword? type-id) (namespace type-id))
         (throw (ex-info "variant type id must be a qualified keyword" {:phase :value})))
       (when-not (and (vector? cases) (seq cases) (<= (count cases) variant-case-limit)
                      (every? #(and (vector? %) (= 2 (count %)) (keyword? (first %))) cases)
                      (= (count cases) (count (distinct (map first cases)))))
         (throw (ex-info "variant cases are invalid" {:phase :value})))
       (vswap! nodes + (+ 2 (* 2 (count cases))))
       (when (> @nodes adt-node-limit)
         (throw (ex-info "value type exceeds node limit" {:phase :value :limit adt-node-limit})))
       (doseq [[_ payload-type] cases]
         (validate-value-type! payload-type (inc depth) nodes))
       type)
     :else (throw (ex-info "value type is outside the safe profile"
                           {:phase :value :type type})))))

(defn bounded-typed-value!
  "Validate a value under a canonical possibly-parametric type descriptor.
  Recursive values share one fixed depth and node budget."
  ([type value]
   (validate-value-type! type)
   (bounded-typed-value! type value 0 (volatile! 0)))
  ([type value depth nodes]
   (vswap! nodes inc)
   (when (> @nodes adt-node-limit)
     (throw (ex-info "ADT value exceeds node limit" {:phase :value :limit adt-node-limit})))
   (when (> depth adt-depth-limit)
     (throw (ex-info "ADT value exceeds depth limit" {:phase :value :limit adt-depth-limit})))
   (case type
     :i64 (do (when-not #?(:clj (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE))
                              :cljs (and (i64/bigint-value? value) (i64/in-i64-range? value)))
                (throw (ex-info "value is not a signed i64" {:phase :value}))) value)
     :string (bounded-string! value string-value-byte-limit)
     :keyword (bounded-keyword! value keyword-value-byte-limit)
     :map (bounded-map! value)
     :bool (do (when-not (boolean? value)
                 (throw (ex-info "value is not a boolean" {:phase :value}))) value)
     :option-i64 (bounded-option-i64! value)
     :result-i64 (bounded-result-i64! value)
     :vector-i64 (bounded-vector-i64! value)
     (cond
       (= :result (first type))
       (do
         (when-not (and (vector? value) (= 2 (count value)) (boolean? (first value)))
           (throw (ex-info "value is not a parametric result" {:phase :value})))
         (let [payload-type (if (first value) (second type) (nth type 2))]
           [(first value) (bounded-typed-value! payload-type (second value) (inc depth) nodes)]))

       (= :variant (first type))
       (do
         (when-not (and (vector? value) (= 3 (count value)) (= type (first value))
                        (keyword? (second value)))
           (throw (ex-info "value is not the declared variant type" {:phase :value})))
         (let [tag (second value)
               payload-type (some (fn [[case-tag case-type]]
                                    (when (= case-tag tag) case-type))
                                  (nth type 2))]
           (when-not payload-type
             (throw (ex-info "variant case is not declared" {:phase :value :tag tag})))
           [type tag (bounded-typed-value! payload-type (nth value 2) (inc depth) nodes)]))

       :else (throw (ex-info "value type is outside the safe profile" {:phase :value}))))))
