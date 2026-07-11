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
(defn- utf8 [s] (mapv #(bit-and (int %) 0xff) (.getBytes ^String s "UTF-8")))
(defn- name-bytes [s] (let [bs (utf8 s)] (into (uleb (count bs)) bs)))

(defn- local-count [form]
  (if-not (seq? form)
    0
    (let [[op & args] form]
      (if (= op 'let)
        (let [[bindings body] args]
          (+ (quot (count bindings) 2)
             (reduce + (map local-count (take-nth 2 (rest bindings))))
             (local-count body)))
        (reduce + (map local-count args))))))

(declare emit-expr)

(defn- emit-many [forms env ctx]
  (mapcat #(emit-expr % env ctx) forms))

(defn emit-expr [form env {:keys [function-indices next-local] :as ctx}]
  (cond
    (integer? form) (into [0x42] (sleb form))                    ; i64.const
    (symbol? form) [0x20 (get env form)]                         ; local.get
    :else
    (let [[op & args] form]
      (cond
        (= op 'let)
        (let [[bindings body] args]
          (loop [pairs (partition 2 bindings) env env out [] cursor next-local]
            (if-let [[name value] (first pairs)]
              (let [value-code (emit-expr value env (assoc ctx :next-local cursor))]
                (recur (next pairs) (assoc env name cursor)
                       (into out (concat value-code [0x21 cursor])) (inc cursor))) ; local.set
              (into out (emit-expr body env (assoc ctx :next-local cursor))))))

        (= op 'if)
        (let [[test then else] args]
          (concat (emit-expr test env ctx)
                  [0x50 0x45 0x04 0x7e]                         ; i64.eqz;i32.eqz;if i64
                  (emit-expr then env ctx) [0x05]
                  (emit-expr else env ctx) [0x0b]))

        (= op 'cap-call)
        (let [[cap-id value] args]
          (concat [0x42] (sleb cap-id) (emit-expr value env ctx) [0x10 0]))

        (contains? '#{+ - * quot} op)
        (let [opcode ({'+ 0x7c '- 0x7d '* 0x7e 'quot 0x7f} op)]
          (if (and (= op '-) (= 1 (count args)))
            (concat [0x42 0] (emit-expr (first args) env ctx) [0x7d])
            (concat (emit-expr (first args) env ctx)
                    (mapcat #(concat (emit-expr % env ctx) [opcode]) (rest args)))))

        (contains? '#{= < > <= >=} op)
        (concat (emit-many args env ctx)
                [({'= 0x51 '< 0x53 '> 0x55 '<= 0x57 '>= 0x59} op)
                 0xad])                                          ; extend i32 result to i64

        :else
        (concat (emit-many args env ctx) [0x10 (get function-indices op)]))))) ; call

(defn- function-type [{:keys [params]}]
  (concat [0x60] (uleb (count params)) (repeat (count params) 0x7e) [1 0x7e]))

(defn- function-body [function function-indices]
  (let [param-env (zipmap (:params function) (range))
        locals (local-count (:body function))
        declarations (if (zero? locals) [0] (concat [1] (uleb locals) [0x7e]))
        ;; Every call consumes one unit from a module-private monotonic fuel
        ;; global. It is never exported and cannot be replenished by guest code.
        charge [0x23 0 0x50 0x04 0x40 0x00 0x0b ; global.get;eqz;if;unreachable;end
                0x23 0 0x42 1 0x7d 0x24 0]       ; global.get;const 1;sub;global.set
        instructions (concat charge (emit-expr (:body function) param-env
                                {:function-indices function-indices
                                 :next-local (count (:params function))}))
        body (concat declarations instructions [0x0b])]
    (concat (uleb (count body)) body)))

(defn emit [kir]
  (let [functions (:functions kir)
        has-cap? (contains? (set (map first (:effects kir))) :cap/call)
        shift (if has-cap? 1 0)
        indices (into {} (map-indexed (fn [i f] [(:name f) (+ i shift)]) functions))
        cap-type [0x60 2 0x7e 0x7e 1 0x7e]
        types (concat (uleb (+ (count functions) shift))
                      (when has-cap? cap-type) (mapcat function-type functions))
        import-sec (when has-cap?
                     (concat [1] (name-bytes "kotoba:cap") (name-bytes "call") [0 0]))
        function-sec (concat (uleb (count functions))
                             (mapcat uleb (range shift (+ shift (count functions)))))
        ;; (global (mut i64) (i64.const 256)); low enough to trap before the
        ;; host call stack becomes the limiting resource.
        global-sec [1 0x7e 1 0x42 0x80 0x02 0x0b]
        ;; Pure functions are exported with their source names. This makes
        ;; runtime parameters observable and testable without host authority.
        export-sec (concat (uleb (count functions))
                           (mapcat (fn [[index function]]
                                     (concat (name-bytes (name (:name function))) [0]
                                             (uleb (+ index shift))))
                                   (map-indexed vector functions)))
        code-sec (concat (uleb (count functions))
                         (mapcat #(function-body % indices) functions))]
    (byte-array
     (map unchecked-byte
          (concat [0 0x61 0x73 0x6d 1 0 0 0]
                  (section 1 types) (when has-cap? (section 2 import-sec))
                  (section 3 function-sec) (section 6 global-sec)
                  (section 7 export-sec) (section 10 code-sec))))))
