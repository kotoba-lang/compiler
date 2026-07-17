(ns kotoba.compiler.value)

(def string-literal-byte-limit 4096)
(def string-value-byte-limit 65536)

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
