(ns kotoba.compiler.backend.aarch64)

(defn- u32le [word]
  (mapv #(bit-and (unsigned-bit-shift-right (long word) (* 8 %)) 0xff) (range 4)))
(defn- insn [word] (u32le word))
(defn- mov-reg [dst src] (insn (bit-or 0xaa0003e0 (bit-shift-left src 16) dst)))
(defn- movz [rd imm shift]
  (insn (bit-or 0xd2800000 (bit-shift-left (quot shift 16) 21)
                (bit-shift-left (bit-and imm 0xffff) 5) rd)))
(defn- movk [rd imm shift]
  (insn (bit-or 0xf2800000 (bit-shift-left (quot shift 16) 21)
                (bit-shift-left (bit-and imm 0xffff) 5) rd)))
(defn- load-constant-reg [rd value]
  (vec (concat (movz rd value 0) (movk rd (unsigned-bit-shift-right value 16) 16)
               (movk rd (unsigned-bit-shift-right value 32) 32)
               (movk rd (unsigned-bit-shift-right value 48) 48))))
(defn- load-constant [value] (load-constant-reg 0 value))
(defn- b-ne [byte-offset]
  (insn (bit-or 0x54000001 (bit-shift-left (bit-and (quot byte-offset 4) 0x7ffff) 5))))

(def ^:private signed-division
  (concat (insn 0xb5000041) (insn 0xd4200000)
          (load-constant-reg 2 Long/MIN_VALUE) (insn 0xeb02001f) (b-ne 32)
          (load-constant-reg 2 -1) (insn 0xeb02003f) (b-ne 8)
          (insn 0xd4200000) (insn 0x9ac10c00)))

(def ^:private fuel-charge
  ;; context v1: fuel is qword [x7,#8].
  (concat (insn 0xf94004f0) (insn 0xb5000050) (insn 0xd4200000)
          (insn 0xd1000610) (insn 0xf90004f0)))

(defn- normalize-expr [form env]
  (cond
    (integer? form) form
    (symbol? form) (get env form form)
    :else (let [[op & args] form]
            (if (= op 'let)
              (let [[bindings body] args
                    env' (reduce (fn [e [name value]]
                                   (assoc e name (normalize-expr value e)))
                                 env (partition 2 bindings))]
                (normalize-expr body env'))
              (apply list op (map #(normalize-expr % env) args))))))

(defn- token-size [token] (if (and (map? token) (:call token)) 4 1))
(defn- code-size [tokens] (reduce + (map token-size tokens)))
(defn- sub-sp [amount] (insn (bit-or 0xd10003ff (bit-shift-left amount 10))))
(defn- add-sp [amount] (insn (bit-or 0x910003ff (bit-shift-left amount 10))))
(defn- str-sp [reg offset]
  (insn (bit-or 0xf90003e0 (bit-shift-left (quot offset 8) 10) reg)))
(defn- ldr-sp [reg offset]
  (insn (bit-or 0xf94003e0 (bit-shift-left (quot offset 8) 10) reg)))
(defn- save-x0 [] (concat (sub-sp 16) (str-sp 0 0)))
(defn- restore-to [reg] (concat (ldr-sp reg 0) (add-sp 16)))
(defn- restore-binary []
  ;; rhs is in x0; preserve it in x1, then restore lhs into x0.
  (concat (mov-reg 1 0) (ldr-sp 0 0) (add-sp 16)))
(defn- branch [byte-offset]
  (insn (bit-or 0x14000000 (bit-and (quot byte-offset 4) 0x03ffffff))))
(defn- cbz-x0 [byte-offset]
  (insn (bit-or 0xb4000000 (bit-shift-left (bit-and (quot byte-offset 4) 0x7ffff) 5))))

(declare emit-expr)
(defn- emit-binary [left right operation env]
  (vec (concat (emit-expr left env) (save-x0) (emit-expr right env)
               (restore-binary) operation)))

(defn- emit-call [op args env]
  (when (> (count args) 5)
    (throw (ex-info "AArch64 fuel ABI supports at most five arguments"
                    {:phase :aarch64 :function op :arity (count args)})))
  (let [saved (mapcat #(concat (emit-expr % env) (save-x0)) args)
        restored (mapcat #(restore-to %) (reverse (range (count args))))]
    (vec (concat saved restored [{:call op}]))))

(defn- ldr-context [reg offset]
  (insn (bit-or 0xf9400000 (bit-shift-left (quot offset 8) 10)
                (bit-shift-left 7 5) reg)))

(defn- tbnz [reg bit-index byte-offset]
  (insn (bit-or 0x37000000
                (bit-shift-left (bit-and bit-index 0x20) 26)
                (bit-shift-left (bit-and bit-index 0x1f) 19)
                (bit-shift-left (bit-and (quot byte-offset 4) 0x3fff) 5)
                reg)))

(defn- emit-cap-call [cap-id value env]
  (let [word-offset (+ 16 (* 8 (quot cap-id 64)))
        bit-index (mod cap-id 64)]
    (vec (concat
          (ldr-context 16 word-offset) (tbnz 16 bit-index 8) (insn 0xd4200000)
          (emit-expr value env)
          (mov-reg 2 0)                           ; x2=value
          (sub-sp 16) (str-sp 7 0)                ; preserve context
          (load-constant-reg 1 cap-id) (mov-reg 0 7)
          (ldr-context 16 48) (insn 0xd63f0200)   ; blr x16
          (ldr-sp 7 0) (add-sp 16)))))

(defn emit-expr [form env]
  (cond
    (integer? form) (load-constant form)
    (symbol? form) (mov-reg 0 (+ 19 (get env form)))
    :else
    (let [[op & args] form]
      (cond
        (= op 'if)
        (let [[test then else] args test-code (emit-expr test env)
              then-code (emit-expr then env) else-code (emit-expr else env)]
          (vec (concat test-code (cbz-x0 (+ 8 (code-size then-code)))
                       then-code (branch (+ 4 (code-size else-code))) else-code)))
        (= op 'cap-call)
        (emit-cap-call (first args) (second args) env)
        (and (= op '-) (= 1 (count args)))
        (vec (concat (emit-expr (first args) env) (insn 0xcb0003e0)))
        (contains? '#{+ - * quot} op)
        (reduce (fn [left-code right]
                  (vec (concat left-code (save-x0) (emit-expr right env) (restore-binary)
                               (case op + (insn 0x8b010000) - (insn 0xcb010000)
                                      * (insn 0x9b017c00) quot signed-division))))
                (emit-expr (first args) env) (rest args))
        (contains? '#{= < > <= >=} op)
        (let [[left right] args cset ({'= 0x9a9f17e0 '< 0x9a9fa7e0 '> 0x9a9fd7e0
                                      '<= 0x9a9fc7e0 '>= 0x9a9fb7e0} op)]
          (emit-binary left right (concat (insn 0xeb01001f) (insn cset)) env))
        :else (emit-call op args env)))))

(defn- emit-function [{:keys [name params body]}]
  (when (> (count params) 5)
    (throw (ex-info "AArch64 fuel ABI supports at most five integer parameters"
                    {:phase :aarch64 :function name :arity (count params)})))
  (let [n (count params) register-frame (* 16 (quot (+ n 1) 2))
        save-frame (when (pos? register-frame)
                     (concat (sub-sp register-frame)
                             (mapcat (fn [i] (str-sp (+ 19 i) (* 8 i))) (range n))))
        restore-frame (when (pos? register-frame)
                        (concat (mapcat (fn [i] (ldr-sp (+ 19 i) (* 8 i))) (range n))
                                (add-sp register-frame)))
        params-to-saved (mapcat (fn [i] (mov-reg (+ 19 i) i)) (range n))
        expression (emit-expr (normalize-expr body {}) (zipmap params (range)))]
    (vec (concat fuel-charge
                 (insn 0xa9bf7bfd) (insn 0x910003fd) ; stp fp,lr,[sp,#-16]!; mov fp,sp
                 save-frame params-to-saved expression restore-frame
                 (insn 0xa8c17bfd) (insn 0xd65f03c0))))) ; ldp fp,lr,[sp],#16; ret

(defn- finalize [tokens function-offset offsets]
  (loop [remaining tokens position 0 out []]
    (if-let [token (first remaining)]
      (if (and (map? token) (:call token))
        (let [absolute (+ function-offset position) target (get offsets (:call token))
              displacement (- target absolute)]
          (when-not target
            (throw (ex-info "unknown AArch64 call target" {:target (:call token)})))
          (when-not (zero? (mod displacement 4))
            (throw (ex-info "unaligned AArch64 BL target" {:target (:call token)})))
          (recur (next remaining) (+ position 4)
                 (into out (insn (bit-or 0x94000000
                                         (bit-and (quot displacement 4) 0x03ffffff))))))
        (recur (next remaining) (inc position) (conj out token)))
      out)))

(defn emit-program [kir]
  (let [token-bodies (mapv (fn [f] [f (emit-function f)]) (:functions kir))
        offsets (loop [items token-bodies offset 0 out {}]
                  (if-let [[f body] (first items)]
                    (recur (next items) (+ offset (code-size body)) (assoc out (:name f) offset)) out))]
    (loop [items token-bodies code [] exports {}]
      (if-let [[function tokens] (first items)]
        (let [offset (get offsets (:name function)) body (finalize tokens offset offsets)]
          (recur (next items) (into code body)
                 (assoc exports (:name function)
                        {:offset offset :length (count body) :arity (count (:params function))})))
        {:code code :exports exports}))))
