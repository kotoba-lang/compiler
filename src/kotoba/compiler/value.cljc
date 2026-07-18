(ns kotoba.compiler.value
  #?(:cljs (:require [kotoba.compiler.cljs-i64 :as i64])))

(def string-literal-byte-limit 4096)
(def string-value-byte-limit 65536)
(def keyword-value-byte-limit 512)
(def map-entry-limit 128)
(def vector-item-limit 128)

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
