(ns kotoba.compiler.ir
  ;; The whole `:require` clause (not just an item inside it) is behind the
  ;; reader-conditional: on `:clj` this file needs no extra require at all
  ;; (matching the original `(ns kotoba.compiler.ir)`), and an EMPTY
  ;; `(:require)` clause -- which is what results if only an item inside it
  ;; is conditional and the branch doesn't match -- fails ns-form spec
  ;; validation ("Extra input spec: :clojure.core.specs.alpha/ns-form",
  ;; confirmed live).
  (:require [kotoba.compiler.value :as value]
            #?@(:cljs [[kotoba.compiler.cljs-i64 :as i64]])))

(def ^:private default-fuel 256)
(def ^:private default-pair-capacity 4096)

(defn- trap! [reason data]
  (throw (ex-info (name reason) (merge {:phase :ir :trap reason} data))))

(defn- charge! [fuel]
  ;; `fuel`/`remaining` are interpreter-internal bookkeeping (a plain
  ;; counter), never a `.kotoba` VALUE, so this stays plain-number on both
  ;; runtimes -- no bigint coercion needed here or anywhere else fuel is
  ;; touched.
  (let [remaining (vswap! fuel dec)]
    (when (neg? remaining)
      (trap! :fuel-exhausted {:limit default-fuel}))))

(defn- validate-runtime-value! [runtime-value type position]
  (case type
    :i64
    (when-not #?(:clj (and (integer? runtime-value)
                            (<= Long/MIN_VALUE runtime-value Long/MAX_VALUE))
                 :cljs (and (i64/bigint-value? runtime-value)
                            (i64/in-i64-range? runtime-value)))
      (trap! :value-type-mismatch {:expected :i64 :position position}))

    :string
    (try
      (value/bounded-string! runtime-value value/string-value-byte-limit)
      (catch #?(:clj Exception :cljs :default) error
        (trap! :invalid-string-value {:position position :message (ex-message error)})))

    :keyword
    (try
      (value/bounded-keyword! runtime-value value/keyword-value-byte-limit)
      (catch #?(:clj Exception :cljs :default) error
        (trap! :invalid-keyword-value {:position position :message (ex-message error)})))

    :map
    (try
      (value/bounded-map! runtime-value)
      (catch #?(:clj Exception :cljs :default) error
        (trap! :invalid-map-value {:position position :message (ex-message error)})))

    (trap! :unsupported-value-type {:type type :position position}))
  runtime-value)

;; #?(:cljs ...): every i64 arithmetic op coerces both operands via
;; `i64/->bigint` first (cheap no-op if already bigint) rather than relying
;; on callers to guarantee it -- JS throws outright ("Cannot mix BigInt and
;; other types") if a bigint and a plain number ever meet in `+`/`-`/`*`,
;; and at least one call site here (`-`'s unary-negation branch below,
;; `(i64-sub 0 (first xs))`) passes a literal plain-number `0` alongside a
;; bigint operand.
;; Each fn's whole BODY (not the `defn-` itself) is what branches --
;; wrapping several `defn-` forms together inside one `#?(:cljs (do ...))`
;; left later top-level code unable to resolve them under nbb's SCI
;; (confirmed live: "Unable to resolve symbol: i64-sub" even though it was
;; `defn-`'d moments earlier in the same file, inside such a `do`).
(defn- i64-add [x y]
  #?(:clj (unchecked-add (long x) (long y))
     :cljs (i64/wrap-i64 (+ (i64/->bigint x) (i64/->bigint y)))))
(defn- i64-sub [x y]
  #?(:clj (unchecked-subtract (long x) (long y))
     :cljs (i64/wrap-i64 (- (i64/->bigint x) (i64/->bigint y)))))
(defn- i64-mul [x y]
  #?(:clj (unchecked-multiply (long x) (long y))
     :cljs (i64/wrap-i64 (* (i64/->bigint x) (i64/->bigint y)))))

(declare eval-expr)

(defn- invoke-function [function values functions fuel heap call-stack cap-call]
  (when-not function
    (trap! :unknown-function {}))
  (when-not (= (count (:params function)) (count values))
    (trap! :arity-mismatch {:function (:name function)
                            :expected (count (:params function))
                            :actual (count values)}))
  (let [param-types (or (:param-types function)
                        (vec (repeat (count (:params function)) :i64)))]
    (doseq [[parameter runtime-value type] (map vector (:params function) values param-types)]
      (validate-runtime-value! runtime-value type {:function (:name function)
                                                   :parameter parameter})))
  ;; Backends charge once on function entry, not once per expression.
  (charge! fuel)
  (let [result (eval-expr (:body function) (zipmap (:params function) values) functions
                          fuel heap (conj call-stack (:name function)) cap-call)]
    (validate-runtime-value! result (or (:result function) :i64)
                             {:function (:name function) :result true})))

(defn- allocate-pair! [heap left right]
  (let [{:keys [cells capacity]} heap
        index (count @cells)]
    (when (>= index capacity)
      (trap! :heap-exhausted {:capacity capacity}))
    (vswap! cells conj [left right])
    ;; The returned handle re-enters the value stream as an ordinary
    ;; `.kotoba` i64 value (e.g. it may later be compared, or passed to
    ;; `pair-first`) -- coerce to bigint here, the one place a plain-number
    ;; heap index (`index`, interpreter-internal) becomes a kotoba value.
    #?(:clj (inc index) :cljs (i64/->bigint (inc index)))))

(defn- read-pair [heap handle slot]
  #?(:clj
     (when-not (and (integer? handle) (pos? handle)
                    (<= handle (count @(:cells heap))))
       (trap! :invalid-pair-handle {:handle handle}))
     :cljs
     (when-not (and (i64/bigint-value? handle) (i64/k-pos? handle)
                    (<= handle (count @(:cells heap))))
       (trap! :invalid-pair-handle {:handle handle})))
  ;; `handle` is a kotoba VALUE (bigint on :cljs); vector indexing needs a
  ;; plain number. Safe to narrow: heap capacity is bounded to
  ;; `default-pair-capacity`/`pair-capacity`, far inside the safe-integer
  ;; range, and an out-of-range handle already trapped above.
  (let [index #?(:clj (dec handle) :cljs (dec (js/Number handle)))]
    (nth (nth @(:cells heap) index) slot)))

(defn eval-expr [form env functions fuel heap call-stack cap-call]
  (cond
    #?(:clj (integer? form)
       ;; A literal here may be a bigint (read from `.kotoba` source) or a
       ;; plain number (synthesized by `kotoba.compiler.frontend`'s
       ;; desugaring, e.g. `when`'s trailing `0`) -- `kotoba-integer?`'s own
       ;; docstring there explains why both are admitted; this is the
       ;; single point that coerces either into the bigint value stream
       ;; every downstream op in this file assumes.
       :cljs (or (i64/bigint-value? form) (integer? form)))
    #?(:clj (long form) :cljs (i64/->bigint form))
    (string? form)
    (value/bounded-string! form value/string-literal-byte-limit)
    (keyword? form)
    (value/bounded-keyword! form value/keyword-value-byte-limit)
    (symbol? form) (if (contains? env form)
                     (get env form)
                     (trap! :unbound-symbol {:symbol form}))
    :else
    (let [[op & args] form]
      (cond
        (= op 'let)
        (let [[bindings body] args
              env' (reduce (fn [e [name value]]
                             (assoc e name (eval-expr value e functions fuel heap call-stack cap-call)))
                           env (partition 2 bindings))]
          (eval-expr body env' functions fuel heap call-stack cap-call))

        (= op 'if)
        (let [[test then else] args
              test-value (eval-expr test env functions fuel heap call-stack cap-call)]
          (eval-expr (if #?(:clj (zero? test-value) :cljs (i64/k-zero? test-value))
                       else then)
                     env functions fuel heap call-stack cap-call))

        (= op 'do)
        (last (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args))

        (= op 'cap-call)
        (let [[cap-id value] args]
          (when-not cap-call
            (trap! :capability-denied {:capability cap-id}))
          (let [result (cap-call cap-id (eval-expr value env functions fuel heap call-stack cap-call))]
            #?(:clj (long result) :cljs (i64/->bigint result))))

        (= op 'pair)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (allocate-pair! heap left right))

        (= op 'pair-first)
        (read-pair heap (eval-expr (first args) env functions fuel heap call-stack cap-call) 0)

        (= op 'pair-second)
        (read-pair heap (eval-expr (first args) env functions fuel heap call-stack cap-call) 1)

        (= op 'string-byte-length)
        (let [bytes (value/utf8-byte-count!
                     (eval-expr (first args) env functions fuel heap call-stack cap-call))]
          #?(:clj (long bytes) :cljs (i64/->bigint bytes)))

        (= op 'string=?)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          #?(:clj (if (= left right) 1 0)
             :cljs (if (= left right) i64/one i64/zero)))

        (= op 'string-concat)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (value/bounded-string! (str left right) value/string-value-byte-limit))

        (= op 'map-new)
        (let [values (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)
              result (into (sorted-map) (map vec (partition 2 values)))]
          (when-not (= (quot (count values) 2) (count result))
            (trap! :duplicate-map-key {}))
          (value/bounded-map! result))

        (= op 'map-get)
        (let [[map-form key-form default-form] args
              map-value (eval-expr map-form env functions fuel heap call-stack cap-call)
              key-value (eval-expr key-form env functions fuel heap call-stack cap-call)]
          (value/bounded-map! map-value)
          (value/bounded-keyword! key-value value/keyword-value-byte-limit)
          (if (contains? map-value key-value)
            (get map-value key-value)
            (eval-expr default-form env functions fuel heap call-stack cap-call)))

        (= op 'map-assoc)
        (let [map-value (eval-expr (first args) env functions fuel heap call-stack cap-call)
              values (mapv #(eval-expr % env functions fuel heap call-stack cap-call)
                           (rest args))
              result (reduce (fn [current [key item]] (assoc current key item))
                             (value/bounded-map! map-value) (partition 2 values))]
          (value/bounded-map! result))

        (contains? '#{kernel-load-u8 kernel-load-u8-4k kernel-load-u8-16k
                      kernel-store-u8 kernel-store-u8-4k
                      kernel-load-u32 kernel-store-u32} op)
        (trap! :kernel-memory-unavailable {:operation op})

        (contains? '#{kernel-boot-info kernel-read-cr2 kernel-read-cr3 kernel-write-cr3 kernel-invlpg
                      kernel-cli kernel-sti kernel-hlt kernel-pause
                      kernel-out-u8 kernel-out-u32} op)
        (trap! :kernel-privileged-unavailable {:operation op})

        (contains? '#{+ - * quot bit-xor bit-and = < > <= >=} op)
        (let [xs (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          #?(:clj
             (case op
               + (reduce i64-add xs)
               - (if (= 1 (count xs)) (i64-sub 0 (first xs)) (reduce i64-sub xs))
               * (reduce i64-mul xs)
               quot (let [[x y] xs]
                      (when (zero? y) (trap! :division-by-zero {}))
                      (when (and (= x Long/MIN_VALUE) (= y -1))
                        (trap! :signed-division-overflow {}))
                      (quot x y))
               bit-xor (apply bit-xor xs)
               bit-and (apply bit-and xs)
               = (if (apply = xs) 1 0)
               < (if (apply < xs) 1 0)
               > (if (apply > xs) 1 0)
               <= (if (apply <= xs) 1 0)
               >= (if (apply >= xs) 1 0))
             :cljs
             ;; `xs` are always bigint already (every literal/sub-expression
             ;; passed through the coercion above), so plain `bit-xor`/
             ;; `bit-and`/`<`/`>`/`<=`/`>=`/`=` -- all confirmed live to work
             ;; correctly on same-typed bigint args -- are used as-is.
             ;; `quot` is the one exception: cljs's own `quot` internally
             ;; converts to a JS number first and throws on bigint input
             ;; (confirmed live), so division here uses raw `/`, which JS
             ;; BigInt already truncates toward zero (confirmed live for
             ;; both a positive and a negative dividend) -- exactly `quot`'s
             ;; contract.
             (case op
               + (reduce i64-add xs)
               - (if (= 1 (count xs)) (i64-sub 0 (first xs)) (reduce i64-sub xs))
               * (reduce i64-mul xs)
               quot (let [[x y] xs]
                      (when (i64/k-zero? y) (trap! :division-by-zero {}))
                      (when (and (= x i64/min-i64) (= y (js/BigInt -1)))
                        (trap! :signed-division-overflow {}))
                      (/ x y))
               bit-xor (i64/->bigint (apply bit-xor xs))
               bit-and (i64/->bigint (apply bit-and xs))
               = (if (apply = xs) i64/one i64/zero)
               < (if (apply < xs) i64/one i64/zero)
               > (if (apply > xs) i64/one i64/zero)
               <= (if (apply <= xs) i64/one i64/zero)
               >= (if (apply >= xs) i64/one i64/zero))))

        :else
        (let [values (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (invoke-function (get functions op) values functions fuel heap call-stack cap-call))))))

(defn execute
  "Executes one KIR export using normative typed-value semantics. i64 math
  wraps modulo 2^64; bounded strings preserve Unicode text; invalid values,
  division, and resource exhaustion trap."
  ([kir function-name args] (execute kir function-name args {}))
  ([kir function-name args {:keys [fuel cap-call pair-capacity]
                            :or {fuel default-fuel pair-capacity default-pair-capacity}}]
   (when (and (contains? kir :exports)
              (not (some #{function-name} (:exports kir))))
     (throw (ex-info "function is not exported" {:phase :ir :function function-name})))
   ;; fuel/pair-capacity are interpreter-internal config, never a `.kotoba`
   ;; value -- plain `integer?` is correct for both runtimes here.
   (when-not (and (integer? fuel) (pos? fuel))
     (throw (ex-info "fuel must be a positive integer" {:phase :ir :fuel fuel})))
   (when-not (and (integer? pair-capacity) (<= 0 pair-capacity default-pair-capacity))
     (throw (ex-info "pair capacity is outside runtime limits"
                     {:phase :ir :pair-capacity pair-capacity})))
   (let [functions (into {} (map (juxt :name identity) (:functions kir)))
         function (get functions function-name)
         param-types (or (:param-types function)
                         (vec (repeat (count (:params function)) :i64)))]
     (when-not (and (sequential? args) (= (count args) (count param-types)))
       (throw (ex-info "arguments do not match function arity" {:phase :ir :args args})))
     (doseq [[arg type] (map vector args param-types)]
       (case type
         :i64 (when-not #?(:clj (and (integer? arg) (<= Long/MIN_VALUE arg Long/MAX_VALUE))
                          :cljs (and (or (i64/bigint-value? arg) (integer? arg))
                                     (i64/in-i64-range? arg)))
                (throw (ex-info "argument must be a signed i64" {:phase :ir :arg arg})))
         :string (value/bounded-string! arg value/string-value-byte-limit)
         :keyword (value/bounded-keyword! arg value/keyword-value-byte-limit)
         :map (value/bounded-map! arg)
         (throw (ex-info "argument type is unsupported" {:phase :ir :type type}))))
     (invoke-function function
                       (mapv (fn [arg type]
                               (if (= type :i64)
                                 (#?(:clj long :cljs i64/->bigint) arg)
                                 arg))
                             args param-types)
                       functions
                      (volatile! fuel) {:cells (volatile! []) :capacity pair-capacity}
                      [] cap-call))))

(defn lower [hir]
  (let [kernel-operations '#{kernel-load-u8 kernel-load-u8-4k kernel-load-u8-16k
                             kernel-store-u8 kernel-store-u8-4k kernel-read-cr2
                             kernel-load-u32 kernel-store-u32
                             kernel-boot-info kernel-read-cr3 kernel-write-cr3 kernel-invlpg
                             kernel-cli kernel-sti kernel-hlt kernel-pause
                             kernel-out-u8 kernel-out-u32}
        kernel-native? (some #(and (seq? %) (contains? kernel-operations (first %)))
                             (tree-seq coll? seq (:functions hir)))
        typed-values? (= :kotoba.hir/v3 (:format hir))
        base {:format (if typed-values? :kotoba.kir/v4 :kotoba.kir/v3)
              :entry (:entry hir)
              :exports (:exports hir)
              :signature (when (:entry hir) {:params [] :result (:result hir)})
              :effects (:effects hir)
              :functions (mapv #(select-keys % (cond-> [:name :params :result :effects :body]
                                                 typed-values? (conj :param-types)))
                               (:functions hir))}
        ;; Effectful results require host authority and cannot be constant-oracled.
        value (when (and (:entry hir) (= :i64 (:result hir))
                         (empty? (:effects hir)) (not kernel-native?))
                (execute base (:entry hir) []))]
    (assoc base
           :oracle-value value
           :blocks (if (some? value)
                     [{:id 0 :instructions [[:const.i64 value] [:return]]}]
                     []))))
