(ns kotoba.compiler.backend.x86-64)

(defn- le32 [n]
  (mapv #(bit-and (unsigned-bit-shift-right (long n) (* 8 %)) 0xff) (range 4)))
(defn- le64 [n]
  (mapv #(bit-and (unsigned-bit-shift-right (long n) (* 8 %)) 0xff) (range 8)))

(def ^:private param-pushes [[0x57] [0x56] [0x52] [0x51] [0x41 0x50]])
(def ^:private arg-pops [[0x5f] [0x5e] [0x5a] [0x59] [0x41 0x58]])
(def ^:private fuel-charge
  ;; context v1: fuel is qword [r9+8].
  [0x49 0x83 0x79 0x08 0x00 0x75 0x02 0x0f 0x0b 0x49 0xff 0x49 0x08])

(defn- normalize-expr [form env]
  (cond
    (integer? form) form
    (symbol? form) (get env form form)
    :else
    (let [[op & args] form]
      (if (= op 'let)
        (let [[bindings body] args
              env' (reduce (fn [e [name value]]
                             (assoc e name (normalize-expr value e)))
                           env (partition 2 bindings))]
          (normalize-expr body env'))
        (apply list op (map #(normalize-expr % env) args))))))

(defn- token-size [token] (if (and (map? token) (:call token)) 5 1))
(defn- code-size [tokens] (reduce + (map token-size tokens)))
(declare emit-expr)

(defn- load-param [param-index param-count pad? temp-depth]
  (let [disp (* 8 (+ (if pad? 1 0) (- param-count 1 param-index) temp-depth))]
    (into [0x48 0x8b 0x84 0x24] (le32 disp))))

(defn- emit-binary [left right opcode env ctx]
  (vec (concat (emit-expr left env ctx)
               [0x50]
               (emit-expr right env (update ctx :temp-depth inc))
               [0x48 0x89 0xc1 0x58]
               opcode)))

(defn- emit-call [op args env {:keys [temp-depth] :as ctx}]
  (let [argc (count args)]
    (when (> argc 5)
      (throw (ex-info "x86-64 fuel ABI supports at most five arguments"
                      {:phase :x86-64 :function op :arity argc})))
    (loop [remaining args depth temp-depth out []]
      (if-let [arg (first remaining)]
        (recur (next remaining) (inc depth)
               (into out (concat (emit-expr arg env (assoc ctx :temp-depth depth)) [0x50])))
        (let [pops (mapcat #(nth arg-pops %) (reverse (range argc)))
              ;; SysV requires rsp%16==0 immediately before CALL. The fixed
              ;; function frame is aligned; expression temporaries may flip it.
              align? (odd? temp-depth)]
          (vec (concat out pops (when align? [0x50]) [{:call op}]
                       (when align? [0x48 0x83 0xc4 0x08]))))))))

(defn- emit-cap-call [cap-id value env {:keys [temp-depth] :as ctx}]
  (let [byte-offset (+ 16 (quot cap-id 8))
        mask (bit-shift-left 1 (mod cap-id 8))
        ;; Save context across the host ABI call. The fixed guest frame is
        ;; aligned; an even temp depth needs one additional 8-byte pad after
        ;; pushing r9.
        align? (even? temp-depth)]
    (vec (concat
          [0x41 0xf6 0x41 byte-offset mask 0x75 0x02 0x0f 0x0b]
          (emit-expr value env ctx)
          [0x48 0x89 0xc2 0x41 0x51]             ; rdx=value; push r9
          (when align? [0x50])
          [0xbe] (le32 cap-id)                    ; esi=cap-id
          [0x4c 0x89 0xcf 0x41 0xff 0x51 0x30]   ; rdi=r9; call [r9+48]
          (when align? [0x48 0x83 0xc4 0x08])
          [0x41 0x59]))))                         ; pop r9

(defn emit-expr [form env {:keys [param-count pad? temp-depth] :as ctx}]
  (cond
    (integer? form) (into [0x48 0xb8] (le64 form))
    (symbol? form) (load-param (get env form) param-count pad? temp-depth)
    :else
    (let [[op & args] form]
      (cond
        (= op 'if)
        (let [[test then else] args
              test-code (emit-expr test env ctx)
              then-code (emit-expr then env ctx)
              else-code (emit-expr else env ctx)]
          (vec (concat test-code [0x48 0x85 0xc0]
                       [0x0f 0x84] (le32 (+ (code-size then-code) 5))
                       then-code [0xe9] (le32 (code-size else-code)) else-code)))

        (= op 'cap-call)
        (emit-cap-call (first args) (second args) env ctx)

        (and (= op '-) (= 1 (count args)))
        (vec (concat (emit-expr (first args) env ctx) [0x48 0xf7 0xd8]))

        (contains? '#{+ - * quot} op)
        (reduce (fn [left-code right]
                  (vec (concat left-code [0x50]
                               (emit-expr right env (update ctx :temp-depth inc))
                               [0x48 0x89 0xc1 0x58]
                               (case op + [0x48 0x01 0xc8] - [0x48 0x29 0xc8]
                                      * [0x48 0x0f 0xaf 0xc1]
                                      quot [0x48 0x99 0x48 0xf7 0xf9]))))
                (emit-expr (first args) env ctx) (rest args))

        (contains? '#{= < > <= >=} op)
        (let [[left right] args setcc ({'= 0x94 '< 0x9c '> 0x9f '<= 0x9e '>= 0x9d} op)]
          (emit-binary left right
                       [0x48 0x39 0xc8 0x0f setcc 0xc0 0x48 0x0f 0xb6 0xc0] env ctx))

        :else (emit-call op args env ctx)))))

(defn- emit-function [{:keys [name params body]}]
  (when (> (count params) 5)
    (throw (ex-info "x86-64 fuel ABI supports at most five integer parameters"
                    {:phase :x86-64 :function name :arity (count params)})))
  (let [n (count params)
        pad? (even? n)
        normalized (normalize-expr body {})
        env (zipmap params (range))
        prologue (concat fuel-charge (mapcat #(nth param-pushes %) (range n))
                         (when pad? [0x50]))
        expression (emit-expr normalized env {:param-count n :pad? pad? :temp-depth 0})
        frame-bytes (* 8 (+ n (if pad? 1 0)))
        epilogue (concat [0x48 0x81 0xc4] (le32 frame-bytes) [0xc3])]
    (vec (concat prologue expression epilogue))))

(defn- finalize [tokens function-offset offsets]
  (loop [remaining tokens position 0 out []]
    (if-let [token (first remaining)]
      (if (and (map? token) (:call token))
        (let [absolute (+ function-offset position)
              target (get offsets (:call token))]
          (when-not target
            (throw (ex-info "unknown x86-64 call target" {:target (:call token)})))
          (recur (next remaining) (+ position 5)
                 (into out (concat [0xe8] (le32 (- target (+ absolute 5)))))))
        (recur (next remaining) (inc position) (conj out token)))
      out)))

(defn emit-program [kir]
  (let [token-bodies (mapv (fn [f] [f (emit-function f)]) (:functions kir))
        offsets (loop [items token-bodies offset 0 out {}]
                  (if-let [[f body] (first items)]
                    (recur (next items) (+ offset (code-size body)) (assoc out (:name f) offset))
                    out))]
    (loop [items token-bodies code [] exports {}]
      (if-let [[function tokens] (first items)]
        (let [offset (get offsets (:name function))
              body (finalize tokens offset offsets)]
          (recur (next items) (into code body)
                 (assoc exports (:name function)
                        {:offset offset :length (count body) :arity (count (:params function))})))
        {:code code :exports exports}))))
