(ns kotoba.compiler.backend.wasm-typed)

(def abi-version 1)
(def custom-section-name "kotoba.typed")

(def ^:private primitive-tags
  {:i64 0 :string 1 :keyword 2 :bool 3})

(defn descriptor? [value]
  (or (contains? primitive-tags value)
      (and (vector? value)
           (contains? #{:option :result :variant :vector :set :record}
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
      :record (into [9] (concat (text-bytes (keyword-text (second descriptor)))
                                (encode-named-members (nth descriptor 2))))
      (throw (ex-info "unsupported Wasm typed descriptor"
                      {:phase :wasm-typed-metadata :descriptor descriptor})))))

(defn- walk [value found]
  (cond
    (descriptor? value)
    (let [found (conj found value)]
      (if (vector? value)
        (reduce (fn [result item]
                  (cond
                    (descriptor? item) (walk item result)
                    (and (vector? item) (= 2 (count item))
                         (descriptor? (second item)))
                    (walk (second item) result)
                    :else result))
                found value)
        found))

    (map? value) (reduce (fn [result item] (walk item result)) found (vals value))
    (coll? value) (reduce (fn [result item] (walk item result)) found value)
    :else found))

(defn descriptor-table [kir]
  (->> (walk kir #{})
       (sort-by pr-str)
       vec))

(defn descriptor-indices [kir]
  (into {} (map-indexed (fn [index descriptor] [descriptor index])
                        (descriptor-table kir))))

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
