(ns kotoba.compiler.backend.wasm-typed
  #?(:cljs (:require [kotoba.compiler.cljs-i64 :as i64])))

(def abi-version 5)
(def custom-section-name "kotoba.typed")

(def ^:private primitive-tags
  {:i64 0 :string 1 :keyword 2 :bool 3 :vector-i64 11 :f64 12 :f32 13
   :vector-f64 14})

(def ^:private boolean-result-ops
  '#{f64-eq f64-lt f64-le f64-gt f64-ge f64-unordered
     f32-eq f32-lt f32-le f32-gt f32-ge f32-unordered
     string=? bool-not option-some? result-ok? option-some?-of result-ok?-of
     typed-set-contains typed-map-contains})

(defn descriptor? [value]
  (or (contains? primitive-tags value)
      (and (vector? value)
           (contains? #{:option :result :variant :vector :set :map :record}
                      (first value)))))

(defn- uleb [n]
  (loop [n n out []]
    (let [byte (bit-and n 0x7f)
          remaining (unsigned-bit-shift-right n 7)]
      (if (zero? remaining)
        (conj out byte)
        (recur remaining (conj out (bit-or byte 0x80)))))))

(defn- utf8 [text]
  #?(:clj (mapv #(bit-and (int %) 0xff) (.getBytes ^String text "UTF-8"))
     :cljs (vec (js/Array.from (.encode (js/TextEncoder.) text)))))

(defn- text-bytes [text]
  (let [bytes (utf8 text)]
    (into (uleb (count bytes)) bytes)))

(defn- keyword-text [value]
  (str value))

(declare encode-descriptor)

(defn- encode-named-members [members]
  (into (uleb (count members))
        (mapcat (fn [[member-name member-type]]
                  (concat (text-bytes (keyword-text member-name))
                          (encode-descriptor member-type)))
                members)))

(defn encode-descriptor [descriptor]
  (if-let [tag (get primitive-tags descriptor)]
    [tag]
    (case (first descriptor)
      :option (into [4] (encode-descriptor (second descriptor)))
      :result (into [5] (concat (encode-descriptor (second descriptor))
                                (encode-descriptor (nth descriptor 2))))
      :variant (into [6] (concat (text-bytes (keyword-text (second descriptor)))
                                 (encode-named-members (nth descriptor 2))))
      :vector (into [7] (concat (uleb (count (second descriptor)))
                                (mapcat encode-descriptor (second descriptor))))
      :set (into [8] (encode-descriptor (second descriptor)))
      :map (into [10] (concat (encode-descriptor (second descriptor))
                              (encode-descriptor (nth descriptor 2))))
      :record (into [9] (concat (text-bytes (keyword-text (second descriptor)))
                                (encode-named-members (nth descriptor 2))))
      (throw (ex-info "unsupported Wasm typed descriptor"
                      {:phase :wasm-typed-metadata :descriptor descriptor})))))

(defn- walk [value found]
  (cond
    (descriptor? value)
    (let [found (conj found value)]
      (if-not (vector? value)
        found
        (case (first value)
          :option (walk (second value) found)
          :result (->> found (walk (second value)) (walk (nth value 2)))
          :variant (reduce (fn [result [_ type]] (walk type result)) found (nth value 2))
          :vector (reduce (fn [result type] (walk type result)) found (second value))
          :set (walk (second value) found)
          :map (->> found (walk (second value)) (walk (nth value 2)))
          :record (reduce (fn [result [_ type]] (walk type result)) found (nth value 2))
          found)))

    (and (seq? value) (contains? boolean-result-ops (first value)))
    (reduce (fn [result item] (walk item result))
            (conj found :bool)
            value)
    (and (seq? value)
         (contains? '#{vector-f64-new vector-f64-count vector-f64-get vector-f64-at
                      vector-f64-drop vector-f64-assoc vector-f64-conj}
                    (first value)))
    (reduce (fn [result item] (walk item result))
            (conj found :vector-f64)
            value)
    (map? value) (reduce (fn [result item] (walk item result)) found (vals value))
    (coll? value) (reduce (fn [result item] (walk item result)) found value)
    (string? value) (conj found :string)
    (keyword? value) (conj found :keyword)
    (boolean? value) (conj found :bool)
    :else found))

(defn descriptor-table [kir]
  (->> (walk kir #{})
       (sort-by pr-str)
       vec))

(defn descriptor-indices [kir]
  (into {} (map-indexed (fn [index descriptor] [descriptor index])
                        (descriptor-table kir))))

(declare literal-table reference-type?)

(defn requires-host-runtime? [kir]
  ;; i64, f32, and f64 are native Wasm scalars. A module whose sealed
  ;; descriptor table contains only those types must not acquire the
  ;; externref host ABI merely because KIR v4 is used.
  (let [signature-types (mapcat (fn [{:keys [param-types result]}]
                                  (conj (vec param-types) result))
                                (:functions kir))
        ;; `descriptor-table` walks the sealed KIR map and therefore also
        ;; observes the map's own keyword-valued metadata as `:keyword`.
        ;; Actual guest keyword literals are independently present in the
        ;; body-only literal table, so discard only that metadata artefact.
        body-descriptors (disj (set (descriptor-table kir)) :keyword)]
    (or (some reference-type? signature-types)
        (some #(not (contains? #{:i64 :f32 :f64} %)) body-descriptors)
        (seq (literal-table kir)))))

(defn- literal-walk [form found]
  (cond
    (descriptor? form) found
    (string? form) (conj found [:string form])
    (keyword? form) (conj found [:keyword (str form)])
    (boolean? form) (conj found [:bool form])
    (coll? form) (reduce (fn [result item] (literal-walk item result)) found form)
    :else found))

(defn literal-table [kir]
  (->> (:functions kir)
       (reduce (fn [found function]
                 (literal-walk (:body function) found)) #{})
       (sort-by pr-str)
       vec))

(defn literal-indices [kir]
  (into {} (map-indexed (fn [index literal] [literal index]) (literal-table kir))))

(defn- encode-literal [[kind value]]
  (case kind
    :string (into [0] (text-bytes value))
    :keyword (into [1] (text-bytes value))
    :bool [(if value 3 2)]
    (throw (ex-info "unsupported Wasm typed literal"
                    {:phase :wasm-typed-metadata :literal [kind value]}))))

(defn metadata-bytes [kir]
  (let [descriptors (descriptor-table kir)
        literals (literal-table kir)]
    (vec (concat [abi-version]
                 (uleb (count descriptors))
                 (mapcat encode-descriptor descriptors)
                 (uleb (count literals))
                 (mapcat encode-literal literals)))))

(defn reference-type? [type]
  (not (contains? #{:i64 :f32 :f64} type)))

(defn wasm-type [type]
  (case type :i64 0x7e :f32 0x7d :f64 0x7c 0x6f))

(declare infer-type)

(defn infer-type [form env signatures]
  (cond
    #?(:clj (integer? form) :cljs (or (i64/bigint-value? form) (integer? form))) :i64
    #?(:clj (instance? Float form) :cljs false) :f32
    #?(:clj (instance? Double form) :cljs (number? form)) :f64
    (string? form) :string
    (keyword? form) :keyword
    (boolean? form) :bool
    (symbol? form) (or (get env form)
                       (throw (ex-info "unbound typed Wasm symbol"
                                       {:phase :wasm-typed-lowering :symbol form})))
    :else
    (let [[op & args] form]
      (cond
        (= op 'let)
        (let [[bindings body] args
              env' (reduce (fn [current [name value]]
                             (assoc current name (infer-type value current signatures)))
                           env (partition 2 bindings))]
          (infer-type body env' signatures))
        (= op 'if) (infer-type (second args) env signatures)
        (= op 'do) (infer-type (last args) env signatures)
        (contains? '#{+ - * quot bit-xor bit-and cap-call pair pair-first pair-second
                      string-byte-length map-get vector-count vector-get vector-f64-count
                      vector-at hetero-vector-count typed-set-count
                      typed-map-count} op) :i64
        (= op 'f64-to-bits) :i64
        (= op 'f64-from-bits) :f64
        (contains? '#{i64-to-f64-checked i64-to-f64-rounded} op) :f64
        (contains? '#{f64-to-i64-checked f64-to-i64-truncating} op) :i64
        (contains? '#{f64-add f64-sub f64-mul f64-div f64-min f64-max f64-neg f64-abs f64-sqrt
                      f64-sin-quarter-turn f64-cos-quarter-turn
                      f64-sin-bounded f64-cos-bounded
                      f64-exp-near-zero f64-log-near-one f64-atan2-bounded
                      f64-exp-bounded f64-log-bounded} op) :f64
        (contains? '#{f64-eq f64-lt f64-le f64-gt f64-ge f64-unordered} op) :bool
        (= op 'f32-to-bits) :i64
        (= op 'f32-from-bits) :f32
        (= op 'f64-to-f32-rounded) :f32
        (= op 'f32-to-f64-exact) :f64
        (contains? '#{i64-to-f32-checked i64-to-f32-rounded} op) :f32
        (contains? '#{f32-to-i64-checked f32-to-i64-truncating} op) :i64
        (contains? '#{f32-add f32-sub f32-mul f32-div f32-min f32-max f32-neg f32-abs f32-sqrt} op) :f32
        (contains? '#{f32-eq f32-lt f32-le f32-gt f32-ge f32-unordered} op) :bool
        (contains? '#{= < > <= >= hetero-vector-equal typed-set-equal
                      typed-map-equal record-equal} op) :i64
        (contains? '#{string=? bool-not option-some? result-ok?
                      result-ok?-of option-some?-of typed-set-contains
                      typed-map-contains} op) :bool
        (= op 'string-concat) :string
        (= op 'vector-new) :vector-i64
        (= op 'vector-f64-new) :vector-f64
        (contains? '#{vector-f64-get vector-f64-at} op) :f64
        (contains? '#{vector-f64-drop vector-f64-assoc vector-f64-conj} op) :vector-f64
        (contains? '#{vector-drop vector-assoc vector-conj} op) :vector-i64
        (= op 'variant-new) (first args)
        (contains? '#{option-some-of option-none-of result-ok-of result-err-of
                      hetero-vector-new typed-set-new typed-map-new record-new} op) (first args)
        (= op 'result-match-of)
        (let [[type _ ok-name ok-body] args]
          (infer-type ok-body (assoc env ok-name (second type)) signatures))
        (= op 'variant-match)
        (let [[type _ branches] args
              [[_ binder body]] branches
              payload-type (second (first (nth type 2)))]
          (infer-type body (assoc env binder payload-type) signatures))
        (= op 'option-match)
        (let [[type _ _ some-name some-body] args]
          (infer-type some-body (assoc env some-name (second type)) signatures))
        (contains? '#{result-value-of result-error-of} op)
        (if (= op 'result-value-of) (second (first args)) (nth (first args) 2))
        (= op 'option-value-of) (second (first args))
        (= op 'typed-map-get) [:option (nth (first args) 2)]
        (= op 'typed-map-entry-at)
        [:option [:vector [(second (first args)) (nth (first args) 2)]]]
        (= op 'hetero-vector-at) (nth (second (first args)) (nth args 2))
        (= op 'record-get)
        (let [[type _ field] args]
          (second (some #(when (= field (first %)) %) (nth type 2))))
        (contains? '#{hetero-vector-assoc typed-set-conj typed-set-disj
                      typed-map-assoc typed-map-dissoc record-assoc} op)
        (first args)
        :else (or (:result (get signatures op))
                  (throw (ex-info "unsupported typed Wasm expression"
                                  {:phase :wasm-typed-lowering :operation op :form form})))))))
