(ns kotoba.compiler.ir)

(def ^:private max-eval-steps 100000)

(defn- checked [n]
  (when-not (<= Long/MIN_VALUE n Long/MAX_VALUE)
    (throw (ex-info "integer overflow" {:phase :ir :value n})))
  (long n))

(declare eval-expr)

(defn- tick! [steps]
  (let [n (vswap! steps inc)]
    (when (> n max-eval-steps)
      (throw (ex-info "compile-time evaluation fuel exhausted" {:phase :ir :limit max-eval-steps})))))

(defn eval-expr [form env functions steps call-stack]
  (tick! steps)
  (cond
    (integer? form) (checked form)
    (symbol? form) (get env form)
    :else
    (let [[op & args] form]
      (cond
        (= op 'let)
        (let [[bindings body] args
              env' (reduce (fn [e [name value]]
                             (assoc e name (eval-expr value e functions steps call-stack)))
                           env (partition 2 bindings))]
          (eval-expr body env' functions steps call-stack))

        (= op 'if)
        (let [[test then else] args]
          (eval-expr (if (zero? (eval-expr test env functions steps call-stack)) else then)
                     env functions steps call-stack))

        (contains? '#{+ - * quot = < > <= >=} op)
        (let [xs (mapv #(eval-expr % env functions steps call-stack) args)]
          (case op
            + (checked (reduce +' xs))
            - (checked (if (= 1 (count xs)) (-' (first xs)) (reduce -' xs)))
            * (checked (reduce *' xs))
            quot (do (when (zero? (second xs))
                       (throw (ex-info "division by zero" {:phase :ir})))
                     (checked (quot (first xs) (second xs))))
            = (if (apply = xs) 1 0)
            < (if (apply < xs) 1 0)
            > (if (apply > xs) 1 0)
            <= (if (apply <= xs) 1 0)
            >= (if (apply >= xs) 1 0)))

        :else
        (let [{:keys [params body]} (get functions op)
              values (mapv #(eval-expr % env functions steps call-stack) args)]
          (when (>= (count call-stack) 128)
            (throw (ex-info "call depth exhausted" {:phase :ir :limit 128})))
          (eval-expr body (zipmap params values) functions steps (conj call-stack op)))))))

(defn lower [hir]
  (let [functions (into {} (map (juxt :name identity) (:functions hir)))
        ;; Effectful results are host-dependent and cannot be constant-oracled.
        value (when (empty? (:effects hir))
                (eval-expr (:body (get functions (:entry hir))) {} functions (volatile! 0) []))]
    {:format :kotoba.kir/v3
     :entry (:entry hir)
     :signature {:params [] :result :i64}
     :effects (:effects hir)
     ;; Runtime KIR retains executable expressions. :oracle-value is produced by
     ;; the bounded evaluator for differential checks and specialized backends;
     ;; it is not the Wasm code-generation input.
     :functions (mapv #(select-keys % [:name :params :result :effects :body]) (:functions hir))
     :oracle-value value
     :blocks (if (some? value)
               [{:id 0 :instructions [[:const.i64 value] [:return]]}]
               [])}))
