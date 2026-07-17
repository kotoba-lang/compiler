(ns kotoba.compiler.backend.wasm
  ;; See `kotoba.compiler.ir`'s ns form for why the whole `:require` clause
  ;; (not just an item inside it) is behind the reader-conditional.
  #?(:cljs (:require [kotoba.compiler.cljs-i64 :as i64])))

;; `uleb` only ever encodes small, non-negative, interpreter-internal counts
;; and indices in this file (section/payload lengths, function/type/import
;; indices) -- never an arbitrary `.kotoba` i64 VALUE -- so it stays plain
;; JS-number-based on both runtimes (`(long n)` was already a no-op cast on
;; :clj for values in this range; dropped for :cljs since cljs has no
;; `long`).
(defn- uleb [n]
  (loop [n #?(:clj (long n) :cljs n) out []]
    (let [b (bit-and n 0x7f) n' (unsigned-bit-shift-right n 7)]
      (if (zero? n') (conj out b) (recur n' (conj out (bit-or b 0x80)))))))

;; `sleb` DOES encode arbitrary `.kotoba` i64 literals (`emit-expr`'s
;; `i64.const` case, below) across the FULL signed 64-bit range, so this is
;; the highest-risk port in this file: cljs's own `bit-shift-right` throws
;; on bigint input ("Cannot mix BigInt and other types" -- confirmed live),
;; and even if it didn't, cljs bitwise ops are JS int32-coerced and would
;; silently truncate any constant outside +-2^31 -- a byte-level corruption
;; of the compiled artifact, not just a value-range check failing loudly
;; like `frontend`'s admission check does. The `:cljs` branch works over
;; bigint throughout via `cljs-i64`, using `i64/ashr` (see its own
;; docstring) in place of `bit-shift-right`.
(defn- sleb [n]
  #?(:clj
     (loop [n (long n) out []]
       (let [b (bit-and n 0x7f) n' (bit-shift-right n 7)
             done (or (and (= n' 0) (zero? (bit-and b 0x40)))
                      (and (= n' -1) (not (zero? (bit-and b 0x40)))))]
         (if done (conj out b) (recur n' (conj out (bit-or b 0x80))))))
     :cljs
     (loop [n (i64/->bigint n) out []]
       (let [b (js/Number (bit-and n (js/BigInt 0x7f))) n' (i64/ashr n 7)
             done (or (and (= n' i64/zero) (zero? (bit-and b 0x40)))
                      (and (= n' (js/BigInt -1)) (not (zero? (bit-and b 0x40)))))]
         (if done (conj out b) (recur n' (conj out (bit-or b 0x80))))))))

(defn- section [id payload] (into [id] (concat (uleb (count payload)) payload)))
(defn- utf8 [s]
  #?(:clj (mapv #(bit-and (int %) 0xff) (.getBytes ^String s "UTF-8"))
     :cljs (vec (js/Array.from (.encode (js/TextEncoder.) s)))))
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

(defn emit-expr [form env {:keys [function-indices intrinsic-indices next-local] :as ctx}]
  (cond
    ;; A literal here may be a bigint (from a `.kotoba` source literal, or
    ;; from `kotoba.compiler.ir`'s coercion once it passes through there)
    ;; or a plain number (synthesized directly by `kotoba.compiler.frontend`
    ;; -- e.g. `when`'s trailing `0`); `sleb` above accepts either.
    #?(:clj (integer? form) :cljs (or (i64/bigint-value? form) (integer? form)))
    (into [0x42] (sleb form))                                    ; i64.const
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

        ;; `do`: emit each subexpression in order; drop all but the last value
        ;; from the stack (0x1a = drop). Side effects run once, in order.
        (= op 'do)
        (let [n (count args)]
          (mapcat (fn [i arg]
                    (concat (emit-expr arg env ctx) (when (< i (dec n)) [0x1a])))
                  (range n) args))

        (= op 'cap-call)
        (let [[cap-id value] args]
          (concat [0x42] (sleb cap-id) (emit-expr value env ctx)
                  [0x10 (get intrinsic-indices 'cap-call)]))

        (contains? '#{pair pair-first pair-second} op)
        (concat (emit-many args env ctx) [0x10 (get intrinsic-indices op)])

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

(defn- function-body [function function-indices intrinsic-indices]
  (let [param-env (zipmap (:params function) (range))
        locals (local-count (:body function))
        declarations (if (zero? locals) [0] (concat [1] (uleb locals) [0x7e]))
        ;; Every call consumes one unit from a module-private monotonic fuel
        ;; global. It is never exported and cannot be replenished by guest code.
        charge [0x23 0 0x50 0x04 0x40 0x00 0x0b ; global.get;eqz;if;unreachable;end
                0x23 0 0x42 1 0x7d 0x24 0]       ; global.get;const 1;sub;global.set
        instructions (concat charge (emit-expr (:body function) param-env
                                {:function-indices function-indices
                                 :intrinsic-indices intrinsic-indices
                                 :next-local (count (:params function))}))
        body (concat declarations instructions [0x0b])]
    (concat (uleb (count body)) body)))

(defn emit [kir target]
  (let [functions (:functions kir)
        exported-names (set (or (:exports kir) (map :name functions)))
        exported-functions (filterv #(contains? exported-names (:name %)) functions)
        has-cap? (contains? (set (map first (:effects kir))) :cap/call)
        heap-ops (let [found (volatile! #{})]
                   (letfn [(walk [form]
                             (cond
                               (seq? form)
                               (do
                                 (when (contains? '#{pair pair-first pair-second} (first form))
                                   (vswap! found conj (first form)))
                                 (doseq [arg (rest form)] (walk arg)))
                               (coll? form) (doseq [item form] (walk item))))]
                     (doseq [function functions] (walk (:body function)))
                     @found))
        imports (vec (concat
                      (when has-cap? [['cap-call "kotoba:cap" "call"
                                       [0x60 2 0x7e 0x7e 1 0x7e]]])
                      (when (seq heap-ops)
                        [['pair "kotoba:heap" "pair" [0x60 2 0x7e 0x7e 1 0x7e]]
                         ['pair-first "kotoba:heap" "pair-first" [0x60 1 0x7e 1 0x7e]]
                         ['pair-second "kotoba:heap" "pair-second" [0x60 1 0x7e 1 0x7e]]])))
        shift (count imports)
        intrinsic-indices (into {} (map-indexed (fn [index [op]] [op index]) imports))
        indices (into {} (map-indexed (fn [i f] [(:name f) (+ i shift)]) functions))
        types (concat (uleb (+ (count functions) shift))
                      (mapcat #(nth % 3) imports) (mapcat function-type functions))
        import-sec (when (seq imports)
                     (concat (uleb shift)
                             (mapcat (fn [[_ module field _] index]
                                       (concat (name-bytes module) (name-bytes field)
                                               [0] (uleb index)))
                                     imports (range))))
        function-sec (concat (uleb (count functions))
                             (mapcat uleb (range shift (+ shift (count functions)))))
        ;; (global (mut i64) (i64.const 256)); low enough to trap before the
        ;; host call stack becomes the limiting resource.
        global-sec [1 0x7e 1 0x42 0x80 0x02 0x0b]
        ;; Pure functions are exported with their source names. This makes
        ;; runtime parameters observable and testable without host authority.
        export-sec (concat (uleb (count exported-functions))
                           (mapcat (fn [function]
                                     (concat (name-bytes (name (:name function))) [0]
                                             (uleb (get indices (:name function)))))
                                   exported-functions))
        code-sec (concat (uleb (count functions))
                         (mapcat #(function-body % indices intrinsic-indices) functions))
        target-sec (concat (name-bytes "kotoba.target")
                           (utf8 (name target)))]
    (let [bytes (concat [0 0x61 0x73 0x6d 1 0 0 0] (section 0 target-sec)
                        (section 1 types) (when (seq imports) (section 2 import-sec))
                        (section 3 function-sec) (section 6 global-sec)
                        (section 7 export-sec) (section 10 code-sec))]
      #?(:clj (byte-array (map unchecked-byte bytes))
         :cljs (js/Uint8Array.from (clj->js (map #(bit-and % 0xff) bytes)))))))
