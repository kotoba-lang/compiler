(ns kotoba.compiler.ir)

(def ^:private default-fuel 256)
(def ^:private default-pair-capacity 4096)

(defn- trap! [reason data]
  (throw (ex-info (name reason) (merge {:phase :ir :trap reason} data))))

(defn- charge! [fuel]
  (let [remaining (vswap! fuel dec)]
    (when (neg? remaining)
      (trap! :fuel-exhausted {:limit default-fuel}))))

(defn- i64-add [x y] (unchecked-add (long x) (long y)))
(defn- i64-sub [x y] (unchecked-subtract (long x) (long y)))
(defn- i64-mul [x y] (unchecked-multiply (long x) (long y)))

(declare eval-expr)

(defn- invoke-function [function values functions fuel heap call-stack cap-call]
  (when-not function
    (trap! :unknown-function {}))
  (when-not (= (count (:params function)) (count values))
    (trap! :arity-mismatch {:function (:name function)
                            :expected (count (:params function))
                            :actual (count values)}))
  ;; Backends charge once on function entry, not once per expression.
  (charge! fuel)
  (eval-expr (:body function) (zipmap (:params function) values) functions
             fuel heap (conj call-stack (:name function)) cap-call))

(defn- allocate-pair! [heap left right]
  (let [{:keys [cells capacity]} heap
        index (count @cells)]
    (when (>= index capacity)
      (trap! :heap-exhausted {:capacity capacity}))
    (vswap! cells conj [left right])
    (inc index)))

(defn- read-pair [heap handle slot]
  (when-not (and (integer? handle) (pos? handle)
                 (<= handle (count @(:cells heap))))
    (trap! :invalid-pair-handle {:handle handle}))
  (nth (nth @(:cells heap) (dec handle)) slot))

(defn eval-expr [form env functions fuel heap call-stack cap-call]
  (cond
    (integer? form) (long form)
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
        (let [[test then else] args]
          (eval-expr (if (zero? (eval-expr test env functions fuel heap call-stack cap-call))
                       else then)
                     env functions fuel heap call-stack cap-call))

        (= op 'cap-call)
        (let [[cap-id value] args]
          (when-not cap-call
            (trap! :capability-denied {:capability cap-id}))
          (long (cap-call cap-id (eval-expr value env functions fuel heap call-stack cap-call))))

        (= op 'pair)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (allocate-pair! heap left right))

        (= op 'pair-first)
        (read-pair heap (eval-expr (first args) env functions fuel heap call-stack cap-call) 0)

        (= op 'pair-second)
        (read-pair heap (eval-expr (first args) env functions fuel heap call-stack cap-call) 1)

        (contains? '#{kernel-load-u8 kernel-load-u8-16k kernel-store-u8} op)
        (trap! :kernel-memory-unavailable {:operation op})

        (contains? '#{+ - * quot bit-xor bit-and = < > <= >=} op)
        (let [xs (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
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
            >= (if (apply >= xs) 1 0)))

        :else
        (let [values (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (invoke-function (get functions op) values functions fuel heap call-stack cap-call))))))

(defn execute
  "Executes one KIR export using the normative i64 semantics. Add/subtract/
  multiply wrap modulo 2^64; invalid division and resource exhaustion trap."
  ([kir function-name args] (execute kir function-name args {}))
  ([kir function-name args {:keys [fuel cap-call pair-capacity]
                            :or {fuel default-fuel pair-capacity default-pair-capacity}}]
   (when-not (and (integer? fuel) (pos? fuel))
     (throw (ex-info "fuel must be a positive integer" {:phase :ir :fuel fuel})))
   (when-not (and (sequential? args)
                  (every? #(and (integer? %) (<= Long/MIN_VALUE % Long/MAX_VALUE)) args))
     (throw (ex-info "arguments must be signed i64 integers" {:phase :ir :args args})))
   (when-not (and (integer? pair-capacity) (<= 0 pair-capacity default-pair-capacity))
     (throw (ex-info "pair capacity is outside runtime limits"
                     {:phase :ir :pair-capacity pair-capacity})))
   (let [functions (into {} (map (juxt :name identity) (:functions kir)))]
     (invoke-function (get functions function-name) (mapv long args) functions
                      (volatile! fuel) {:cells (volatile! []) :capacity pair-capacity}
                      [] cap-call))))

(defn lower [hir]
  (let [base {:format :kotoba.kir/v3
              :entry (:entry hir)
              :signature {:params [] :result :i64}
              :effects (:effects hir)
              :functions (mapv #(select-keys % [:name :params :result :effects :body])
                               (:functions hir))}
        ;; Effectful results require host authority and cannot be constant-oracled.
        value (when (empty? (:effects hir))
                (execute base (:entry hir) []))]
    (assoc base
           :oracle-value value
           :blocks (if (some? value)
                     [{:id 0 :instructions [[:const.i64 value] [:return]]}]
                     []))))
