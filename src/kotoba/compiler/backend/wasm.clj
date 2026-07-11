(ns kotoba.compiler.backend.wasm)

(defn- uleb [n]
  (loop [n (long n) out []]
    (let [b (bit-and n 0x7f) n' (unsigned-bit-shift-right n 7)]
      (if (zero? n') (conj out b) (recur n' (conj out (bit-or b 0x80)))))))

(defn- sleb [n]
  (loop [n (long n) out []]
    (let [b (bit-and n 0x7f) n' (bit-shift-right n 7)
          done (or (and (= n' 0) (zero? (bit-and b 0x40)))
                   (and (= n' -1) (not (zero? (bit-and b 0x40)))))]
      (if done (conj out b) (recur n' (conj out (bit-or b 0x80)))))))

(defn- section [id payload] (into [id] (concat (uleb (count payload)) payload)))

(defn emit [value]
  (let [type-sec [1 0x60 0 1 0x7e]
        func-sec [1 0]
        export-sec [1 4 0x6d 0x61 0x69 0x6e 0 0]
        body (into [0 0x42] (concat (sleb value) [0x0b]))
        code-sec (into [1] (concat (uleb (count body)) body))]
    (byte-array (map unchecked-byte (concat [0 0x61 0x73 0x6d 1 0 0 0]
                                  (section 1 type-sec) (section 3 func-sec)
                                  (section 7 export-sec) (section 10 code-sec))))))
