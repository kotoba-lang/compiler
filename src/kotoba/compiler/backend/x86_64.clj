(ns kotoba.compiler.backend.x86-64)

(defn- le32 [n]
  (mapv #(bit-and (unsigned-bit-shift-right (long n) (* 8 %)) 0xff) (range 4)))

(defn- le64 [n]
  (mapv #(bit-and (unsigned-bit-shift-right (long n) (* 8 %)) 0xff) (range 8)))

(def ^:private param-pushes
  [[0x57] [0x56] [0x52] [0x51] [0x41 0x50] [0x41 0x51]])

(defn- expand-expr [form env functions stack]
  (cond
    (integer? form) form
    (symbol? form) (get env form form)
    :else
    (let [[op & args] form]
      (cond
        (= op 'let)
        (let [[bindings body] args
              env' (reduce (fn [e [name value]]
                             (assoc e name (expand-expr value e functions stack)))
                           env (partition 2 bindings))]
          (expand-expr body env' functions stack))

        (contains? '#{if + - * quot = < > <= >=} op)
        (apply list op (map #(expand-expr % env functions stack) args))

        :else
        (do
          (when (contains? (set stack) op)
            (throw (ex-info "recursive native lowering requires fuel-aware calls"
                            {:phase :x86-64 :function op})))
          (let [{:keys [params body]} (get functions op)
                values (mapv #(expand-expr % env functions stack) args)]
            (expand-expr body (zipmap params values) functions (conj stack op))))))))

(declare emit-expr)

(defn- load-param [param-index param-count temp-depth]
  (let [disp (* 8 (+ (- param-count 1 param-index) temp-depth))]
    (into [0x48 0x8b 0x84 0x24] (le32 disp)))) ; mov rax,[rsp+disp32]

(defn- emit-binary [left right opcode env param-count temp-depth]
  (vec (concat (emit-expr left env param-count temp-depth)
               [0x50]                                           ; push rax
               (emit-expr right env param-count (inc temp-depth))
               [0x48 0x89 0xc1 0x58]                            ; mov rcx,rax; pop rax
               opcode)))

(defn emit-expr [form env param-count temp-depth]
  (cond
    (integer? form) (into [0x48 0xb8] (le64 form))               ; movabs rax,imm64
    (symbol? form) (load-param (get env form) param-count temp-depth)
    :else
    (let [[op & args] form]
      (cond
        (= op 'if)
        (let [[test then else] args
              test-code (emit-expr test env param-count temp-depth)
              then-code (emit-expr then env param-count temp-depth)
              else-code (emit-expr else env param-count temp-depth)]
          (vec (concat test-code [0x48 0x85 0xc0]                ; test rax,rax
                       [0x0f 0x84] (le32 (+ (count then-code) 5)); jz else
                       then-code [0xe9] (le32 (count else-code)) ; jmp end
                       else-code)))

        (and (= op '-) (= 1 (count args)))
        (vec (concat (emit-expr (first args) env param-count temp-depth)
                     [0x48 0xf7 0xd8]))                          ; neg rax

        (contains? '#{+ - * quot} op)
        (reduce (fn [left-code right]
                  ;; Fold n-ary operations by representing the accumulated
                  ;; machine expression as a private pre-emitted marker.
                  (let [right-code (emit-expr right env param-count (inc temp-depth))]
                    (vec (concat left-code [0x50] right-code
                                 [0x48 0x89 0xc1 0x58]
                                 (case op
                                   + [0x48 0x01 0xc8]
                                   - [0x48 0x29 0xc8]
                                   * [0x48 0x0f 0xaf 0xc1]
                                   quot [0x48 0x99 0x48 0xf7 0xf9])))))
                (emit-expr (first args) env param-count temp-depth)
                (rest args))

        (contains? '#{= < > <= >=} op)
        (let [[left right] args
              setcc ({'= 0x94 '< 0x9c '> 0x9f '<= 0x9e '>= 0x9d} op)]
          (emit-binary left right
                       [0x48 0x39 0xc8 0x0f setcc 0xc0 0x48 0x0f 0xb6 0xc0]
                       env param-count temp-depth))))))

(defn- emit-function [{:keys [name params body]} functions]
  (when (> (count params) 6)
    (throw (ex-info "x86-64 v1 supports at most six integer parameters"
                    {:phase :x86-64 :function name :arity (count params)})))
  (let [expanded (expand-expr body {} functions [name])
        env (zipmap params (range))
        prologue (mapcat param-pushes (range (count params)))
        expression (emit-expr expanded env (count params) 0)
        epilogue (if (zero? (count params)) [0xc3]
                     (concat [0x48 0x81 0xc4] (le32 (* 8 (count params))) [0xc3]))]
    (vec (concat prologue expression epilogue))))

(defn emit-program [kir]
  (let [functions-by-name (into {} (map (juxt :name identity) (:functions kir)))]
    (loop [remaining (:functions kir) offset 0 code [] exports {}]
      (if-let [function (first remaining)]
        (let [body (emit-function function functions-by-name)]
          (recur (next remaining) (+ offset (count body)) (into code body)
                 (assoc exports (:name function)
                        {:offset offset :length (count body) :arity (count (:params function))})))
        {:code code :exports exports}))))
