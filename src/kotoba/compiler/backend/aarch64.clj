(ns kotoba.compiler.backend.aarch64)

(defn- u32le [word]
  (mapv #(bit-and (unsigned-bit-shift-right (long word) (* 8 %)) 0xff) (range 4)))

(defn- insn [word] (u32le word))

(defn- mov-reg [dst src]
  (insn (bit-or 0xaa0003e0 (bit-shift-left src 16) dst))) ; orr Xd,xzr,Xm

(defn- movz [rd imm shift]
  (insn (bit-or 0xd2800000 (bit-shift-left (quot shift 16) 21)
                (bit-shift-left (bit-and imm 0xffff) 5) rd)))

(defn- movk [rd imm shift]
  (insn (bit-or 0xf2800000 (bit-shift-left (quot shift 16) 21)
                (bit-shift-left (bit-and imm 0xffff) 5) rd)))

(defn- load-constant [value]
  (vec (concat (movz 0 value 0)
               (movk 0 (unsigned-bit-shift-right value 16) 16)
               (movk 0 (unsigned-bit-shift-right value 32) 32)
               (movk 0 (unsigned-bit-shift-right value 48) 48))))

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
                            {:phase :aarch64 :function op})))
          (let [{:keys [params body]} (get functions op)
                values (mapv #(expand-expr % env functions stack) args)]
            (expand-expr body (zipmap params values) functions (conj stack op))))))))

(declare emit-expr)

(def ^:private save-x0 [(insn 0xd10043ff) (insn 0xf90003e0)]) ; sub sp,#16; str x0,[sp]
(def ^:private restore-x0
  [(mov-reg 1 0) (insn 0xf94003e0) (insn 0x910043ff)]) ; x1=rhs;ldr x0;add sp,#16

(defn- emit-binary [left right operation env]
  (vec (concat (emit-expr left env) (mapcat identity save-x0)
               (emit-expr right env) (mapcat identity restore-x0) operation)))

(defn- branch [byte-offset]
  (when-not (zero? (mod byte-offset 4))
    (throw (ex-info "unaligned AArch64 branch" {:offset byte-offset})))
  (insn (bit-or 0x14000000 (bit-and (quot byte-offset 4) 0x03ffffff))))

(defn- cbz-x0 [byte-offset]
  (when-not (zero? (mod byte-offset 4))
    (throw (ex-info "unaligned AArch64 branch" {:offset byte-offset})))
  (insn (bit-or 0xb4000000 (bit-shift-left (bit-and (quot byte-offset 4) 0x7ffff) 5))))

(defn emit-expr [form env]
  (cond
    (integer? form) (load-constant form)
    (symbol? form) (mov-reg 0 (+ 9 (get env form)))
    :else
    (let [[op & args] form]
      (cond
        (= op 'if)
        (let [[test then else] args
              test-code (emit-expr test env)
              then-code (emit-expr then env)
              else-code (emit-expr else env)]
          (vec (concat test-code (cbz-x0 (+ 8 (count then-code)))
                       then-code (branch (+ 4 (count else-code))) else-code)))

        (and (= op '-) (= 1 (count args)))
        (vec (concat (emit-expr (first args) env) (insn 0xcb0003e0))) ; neg x0

        (contains? '#{+ - * quot} op)
        (reduce (fn [left-code right]
                  (vec (concat left-code (mapcat identity save-x0)
                               (emit-expr right env) (mapcat identity restore-x0)
                               (case op
                                 + (insn 0x8b010000)      ; add x0,x0,x1
                                 - (insn 0xcb010000)      ; sub x0,x0,x1
                                 * (insn 0x9b017c00)      ; mul x0,x0,x1
                                 quot (insn 0x9ac10c00))))) ; sdiv x0,x0,x1
                (emit-expr (first args) env) (rest args))

        (contains? '#{= < > <= >=} op)
        (let [[left right] args
              cset ({'= 0x9a9f17e0 '< 0x9a9fa7e0 '> 0x9a9fd7e0
                     '<= 0x9a9fc7e0 '>= 0x9a9fb7e0} op)]
          (emit-binary left right
                       (concat (insn 0xeb01001f) (insn cset)) env))))))

(defn- emit-function [{:keys [name params body]} functions]
  (when (> (count params) 7)
    (throw (ex-info "AArch64 v1 supports at most seven integer parameters"
                    {:phase :aarch64 :function name :arity (count params)})))
  (let [expanded (expand-expr body {} functions [name])
        env (zipmap params (range))
        ;; x9..x15 are caller-saved and safe because user calls are inlined.
        prologue (mapcat (fn [i] (mov-reg (+ 9 i) i)) (range (count params)))
        expression (emit-expr expanded env)]
    (vec (concat prologue expression (insn 0xd65f03c0))))) ; ret

(defn emit-program [kir]
  (let [functions-by-name (into {} (map (juxt :name identity) (:functions kir)))]
    (loop [remaining (:functions kir) offset 0 code [] exports {}]
      (if-let [function (first remaining)]
        (let [body (emit-function function functions-by-name)]
          (recur (next remaining) (+ offset (count body)) (into code body)
                 (assoc exports (:name function)
                        {:offset offset :length (count body) :arity (count (:params function))})))
        {:code code :exports exports}))))
