(ns kotoba.compiler.backend.wasm
  ;; See `kotoba.compiler.ir`'s ns form for why the whole `:require` clause
  ;; (not just an item inside it) is behind the reader-conditional.
  #?(:clj (:require [kotoba.compiler.backend.wasm-typed :as typed]
                    [kotoba.compiler.compatibility :as compatibility])
     :cljs (:require [kotoba.compiler.backend.wasm-typed :as typed]
                     [kotoba.compiler.compatibility :as compatibility]
                     [kotoba.compiler.cljs-i64 :as i64])))

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
       ;; cljs.core/bit-and coerces through its collection-oriented SCI
       ;; implementation under nbb and cannot accept JS bigint.  Modulo 128
       ;; yields the identical low seven two's-complement bits, including for
       ;; negative values, without leaving the exact-bigint domain.
       (let [b (js/Number (mod n (js/BigInt 0x80))) n' (i64/ashr n 7)
             done (or (and (= n' i64/zero) (zero? (bit-and b 0x40)))
                      (and (= n' (js/BigInt -1)) (not (zero? (bit-and b 0x40)))))]
         (if done (conj out b) (recur n' (conj out (bit-or b 0x80))))))))

(defn- encode-local-operands [tokens]
  (loop [remaining (seq tokens) encoded []]
    (if-not remaining
      encoded
      (let [token (first remaining)]
        (if (contains? #{::local-get ::local-set ::local-tee} token)
          (let [index (second remaining)]
            (when-not (and (integer? index) (<= 0 index))
              (throw (ex-info "invalid Wasm local index"
                              {:phase :wasm-local-encoding
                               :operation token :index index})))
            (recur (nnext remaining)
                   (into encoded
                         (concat [(case token
                                    ::local-get 0x20
                                    ::local-set 0x21
                                    ::local-tee 0x22)]
                                 (uleb index)))))
          (recur (next remaining) (conj encoded token)))))))

(defn- section [id payload] (into [id] (concat (uleb (count payload)) payload)))
(defn- utf8 [s]
  #?(:clj (mapv #(bit-and (int %) 0xff) (.getBytes ^String s "UTF-8"))
     :cljs (vec (js/Array.from (.encode (js/TextEncoder.) s)))))
(defn- name-bytes [s] (let [bs (utf8 s)] (into (uleb (count bs)) bs)))

(def compatibility-section-name "kotoba.compatibility")

(defn- wasm-runtime [target]
  (case target
    :wasm32-browser-kotoba-v1 :kotoba-browser-host-v1
    :wasm32-wasi-kotoba-v1 :kotoba-wasi-host-v1
    :kotoba-capability-host-v1))

(defn- identity-text [value]
  (if-let [ns (namespace value)] (str ns "/" (name value)) (name value)))

(defn- compatibility-bytes [kir target]
  (let [fields [compatibility/compiler-version
                (identity-text compatibility/language-version)
                (identity-text (:format kir))
                (identity-text target)
                (identity-text (wasm-runtime target))
                (identity-text (cond
                                 (some (fn [{:keys [param-types result]}]
                                         (or (some #{:f32} param-types) (= :f32 result)))
                                       (:functions kir))
                                 :kotoba.typed/mixed-f32-f64-v3
                                 (some (fn [{:keys [param-types result]}]
                                         (or (some #{:f64} param-types) (= :f64 result)))
                                       (:functions kir))
                                 :kotoba.typed/mixed-f64-v2
                                 (= :kotoba.kir/v4 (:format kir)) :kotoba.typed/externref-v1
                                 :else :kotoba.i64/direct-v1))
                (identity-text compatibility/tender-role)
                (identity-text :kotoba.capability-host/v1)]]
    (vec (concat [1] (mapcat name-bytes fields)))))

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
    (symbol? form) [::local-get (get env form)]                         ; local.get
    :else
    (let [[op & args] form]
      (cond
        (= op 'let)
        (let [[bindings body] args]
          (loop [pairs (partition 2 bindings) env env out [] cursor next-local]
            (if-let [[name value] (first pairs)]
              (let [value-code (emit-expr value env (assoc ctx :next-local cursor))]
                (recur (next pairs) (assoc env name cursor)
                       (into out (concat value-code [::local-set cursor])) (inc cursor))) ; local.set
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

        (contains? '#{+ - * quot bit-xor bit-and} op)
        (let [opcode ({'+ 0x7c '- 0x7d '* 0x7e 'quot 0x7f
                       'bit-and 0x83 'bit-xor 0x85} op)]
          (if (and (= op '-) (= 1 (count args)))
            (concat [0x42 0] (emit-expr (first args) env ctx) [0x7d])
            (concat (emit-expr (first args) env ctx)
                    (mapcat #(concat (emit-expr % env ctx) [opcode]) (rest args)))))

        (= op 'i32-wrap)
        (concat (emit-expr (first args) env ctx) [0xa7 0xac])

        (= op 'u32-wrap)
        (concat (emit-expr (first args) env ctx) [0xa7 0xad])

        (contains? '#{i32-wrapping-add i32-wrapping-mul i32-xor} op)
        (concat (emit-expr (first args) env ctx) [0xa7]
                (emit-expr (second args) env ctx) [0xa7]
                [({'i32-wrapping-add 0x6a 'i32-wrapping-mul 0x6c 'i32-xor 0x73} op)
                 0xac])

        (contains? '#{i32-shift-left i32-shift-right u32-shift-right} op)
        (concat (emit-expr (first args) env ctx) [0xa7]
                (emit-expr (second args) env ctx) [0xa7]
                [({'i32-shift-left 0x74 'i32-shift-right 0x75 'u32-shift-right 0x76} op)
                 (if (= op 'u32-shift-right) 0xad 0xac)])

        (contains? '#{= < > <= >=} op)
        (concat (emit-many args env ctx)
                [({'= 0x51 '< 0x53 '> 0x55 '<= 0x57 '>= 0x59} op)
                 0xad])                                          ; extend i32 result to i64

        :else
        (concat (emit-many args env ctx) [0x10 (get function-indices op)]))))) ; call

(defn- i32-const [value] (into [0x41] (sleb value)))

(defn- typed-function-signatures [functions]
  (into {} (map (fn [function] [(:name function) function]) functions)))

(defn- typed-function-type [{:keys [param-types result]}]
  (concat [0x60] (uleb (count param-types)) (map typed/wasm-type param-types)
          [1 (typed/wasm-type result)]))

(defn- exact-i64 [text]
  #?(:clj (Long/parseLong text) :cljs (js/BigInt text)))

(defn- f64-constant [bits]
  (list 'f64-from-bits (if (string? bits) (exact-i64 bits) bits)))

(defn- f64-horner [z coefficient-bits]
  (reduce (fn [polynomial bits]
            (list 'f64-add (f64-constant bits) (list 'f64-mul z polynomial)))
          (f64-constant (first coefficient-bits))
          (rest coefficient-bits)))

(defn- bounded-sin-form [x]
  (let [z (list 'f64-mul x x)
        p (f64-horner z ["4389130328383466826" "-4797767418267846625"
                         "4460272573143870729" "-4730215272828025628"
                         "4523617214285662004" "-4671919876300759014"
                         "4575957461383581969" "-4628199217061079723"])]
    (list 'f64-add x (list 'f64-mul (list 'f64-mul x z) p))))

(defn- bounded-cos-form [x]
  (let [z (list 'f64-mul x x)
        p (f64-horner z ["4407590220077447199" "-4780226356266894179"
                         "4477122120089393304" "-4714566979057836196"
                         "4537941361671905306" "-4659324094485795817"
                         "4586165620538955093" "-4620693217682128896"])]
    (list 'f64-add (f64-constant "4607182418800017408") (list 'f64-mul z p))))

(defn- bounded-exp-form [x]
  (let [p (f64-horner x ["4370323941990432151" "4389130328383466826"
                          "4407590220077447199" "4425604618586929183"
                          "4443145680587881629" "4460272573143870729"
                          "4477122120089393304" "4493156764026750180"
                          "4508805057796939612" "4523617214285662004"
                          "4537941361671905306" "4551452160554016794"
                          "4564047942368979991" "4575957461383581969"
                          "4586165620538955093" "4595172819793696085"
                          "4602678819172646912" "4607182418800017408"])]
    (list 'f64-add (f64-constant "4607182418800017408") (list 'f64-mul x p))))

(defn- bounded-log-form [x]
  (let [one (f64-constant "4607182418800017408")
        y (list 'f64-div (list 'f64-sub x one) (list 'f64-add x one))
        z (list 'f64-mul y y)
        p (f64-horner z ["4587023449039406616" "4587745830934523688"
                          "4588638185040256542" "4589468260265693457"
                          "4590207312512236308" "4591215111030249286"
                          "4592670820000712476" "4594314991293244562"
                          "4596373779694328218" "4599676419421066581"
                          "4607182418800017408"])]
    (list 'f64-mul (list 'f64-mul (f64-constant "4611686018427387904") y) p)))

(defn- bounded-atan-unit-form [value]
  (let [one (f64-constant "4607182418800017408")
        threshold? (list '<= (list 'f64-to-bits value) (exact-i64 "4601133428098527028"))
        t (list 'if threshold?
                value
                (list 'f64-div (list 'f64-sub value one) (list 'f64-add value one)))]
    (list 'let ['t t
                'z (list 'f64-mul 't 't)
                'p (f64-horner 'z ["-4640324292980923366" "4583447231574686416"
                                   "-4639479661842017251" "4584391475231203080"
                                   "-4638562338784276348" "4585130310279789462"
                                   "-4637873616260616344" "4585925428558828667"
                                   "-4636945338076552860" "4587023449039406616"
                                   "-4635626205920252120" "4588638185040256542"
                                   "-4633903776589082351" "4590207312512236308"
                                   "-4632156925824526522" "4592670820000712476"
                                   "-4629057045561531246" "4596373779694328218"
                                   "-4623695617433709227" "4607182418800017408"])
                'a (list 'f64-mul 't 'p)]
          (list 'if threshold?
                'a
                (list 'f64-add (f64-constant "4605249457297304856") 'a)))))

(defn- bounded-atan2-form [y x]
  (let [pi (f64-constant "4614256656552045848")
        half-pi (f64-constant "4609753056924675352")
        negative? (fn [v] (list '< (list 'f64-to-bits v) 0))
        zero? (fn [v] (list '= (list 'bit-and (list 'f64-to-bits v)
                                      (exact-i64 "9223372036854775807")) 0))]
    (list 'if (zero? y)
          (list 'if (negative? x)
                (list 'if (negative? y) (list 'f64-neg pi) pi)
                y)
          (list 'if (zero? x)
                (list 'if (negative? y) (list 'f64-neg half-pi) half-pi)
                (list 'let ['ay (list 'f64-abs y)
                            'ax (list 'f64-abs x)
                            'swap (list '> (list 'f64-to-bits 'ay) (list 'f64-to-bits 'ax))
                            'ratio (list 'if 'swap
                                         (list 'f64-div 'ax 'ay)
                                         (list 'f64-div 'ay 'ax))
                            'base (bounded-atan-unit-form 'ratio)
                            'angle1 (list 'if 'swap
                                          (list 'f64-sub half-pi 'base)
                                          'base)
                            'angle2 (list 'if (negative? x)
                                          (list 'f64-sub pi 'angle1)
                                          'angle1)]
                      (list 'if (negative? y) (list 'f64-neg 'angle2) 'angle2))))))

(defn- wide-exp-form [value]
  (let [half (f64-constant "4602678819172646912")]
    (list 'let ['scaled (list 'f64-mul value (f64-constant "4609176140021203710"))
                'exponent (list 'if (list '< (list 'f64-to-bits 'scaled) 0)
                                (list 'f64-to-i64-truncating (list 'f64-sub 'scaled half))
                                (list 'f64-to-i64-truncating (list 'f64-add 'scaled half)))
                'exponent-f64 (list 'i64-to-f64-checked 'exponent)
                'reduced (list 'f64-sub
                               (list 'f64-sub value
                                     (list 'f64-mul 'exponent-f64
                                           (f64-constant "4604418534313441775")))
                               (list 'f64-mul 'exponent-f64
                                     (f64-constant "4358002977218854975")))
                'scale-bits (list '* (list '+ 'exponent 1023)
                                  (exact-i64 "4503599627370496"))
                'scale (list 'f64-from-bits 'scale-bits)]
          (list 'f64-mul (bounded-exp-form 'reduced) 'scale))))

(defn- wide-log-form [value]
  (let [unit (exact-i64 "4503599627370496")
        mask (exact-i64 "4503599627370495")
        one-bits (exact-i64 "4607182418800017408")
        one-and-half-bits (exact-i64 "4609434218613702656")]
    (list 'let ['bits (list 'f64-to-bits value)
                'exponent0 (list '- (list 'quot 'bits unit) 1023)
                'mantissa0 (list 'f64-from-bits
                                 (list '+ (list 'bit-and 'bits mask) one-bits))
                'high (list '> (list 'f64-to-bits 'mantissa0) one-and-half-bits)
                'mantissa (list 'if 'high
                                (list 'f64-mul 'mantissa0
                                      (f64-constant "4602678819172646912"))
                                'mantissa0)
                'exponent (list 'if 'high (list '+ 'exponent0 1) 'exponent0)
                'exponent-f64 (list 'i64-to-f64-checked 'exponent)
                'kernel (bounded-log-form 'mantissa)]
          (list 'f64-add
                (list 'f64-add 'kernel
                      (list 'f64-mul 'exponent-f64
                            (f64-constant "4604418534313441775")))
                (list 'f64-mul 'exponent-f64
                      (f64-constant "4358002977218854975"))))))

(defn- emit-typed-function-body
  [function function-indices intrinsic-indices descriptor-indices literal-indices signatures]
  (let [locals (volatile! [])
        param-count (count (:params function))
        allocate! (fn [wasm-type]
                    (let [index (+ param-count (count @locals))]
                      (vswap! locals conj wasm-type)
                      index))
        descriptor-id (fn [type]
                        (or (get descriptor-indices type)
                            (throw (ex-info "typed Wasm descriptor is not sealed"
                                            {:phase :wasm-typed-lowering :descriptor type}))))
        scalar-suffix (fn [type]
                        (case type :i64 "i64" :f64 "f64" :f32 "f32" "ref"))
        env (into {} (map-indexed (fn [index [name type]]
                                    [name {:index index :type type}])
                                  (map vector (:params function) (:param-types function))))]
    (letfn [(emit-builder [type tag item-forms item-types env]
              (let [initial (concat (i32-const (descriptor-id type)) (i32-const tag)
                                    [0x10 (get intrinsic-indices 'typed-new)])
                    pushed (reduce (fn [code [item item-type]]
                                     (concat code (emit* item env)
                                             [0x10 (get intrinsic-indices
                                                        (symbol (str "typed-push-"
                                                                     (scalar-suffix item-type))))]))
                                   initial (map vector item-forms item-types))]
                (concat (i32-const (descriptor-id type)) pushed
                        [0x10 (get intrinsic-indices 'typed-seal)])))
            (emit-get [type value-form index item-type env]
              (concat (i32-const (descriptor-id type)) (emit* value-form env)
                      (i32-const index)
                      [0x10 (get intrinsic-indices
                                 (symbol (str "typed-get-" (scalar-suffix item-type))))]))
            (emit-bool [code]
              (concat code [0x10 (get intrinsic-indices 'typed-bool)]))
            (emit-equal [type left right env]
              (concat (i32-const (descriptor-id type))
                      (emit* left env) (emit* right env)
                      [0x10 (get intrinsic-indices 'typed-equal) 0xad]))
            (emit-test [form env]
              (let [type (typed/infer-type
                          form
                          (into {} (map (fn [[key item]] [key (:type item)]) env))
                          signatures)]
                (case type
                  :i64 (concat (emit* form env) [0x50 0x45])
                  :bool (concat (i32-const (descriptor-id :bool))
                                (emit* form env)
                                [0x10 (get intrinsic-indices 'typed-tag)])
                  (throw (ex-info "typed Wasm condition must be bool or i64"
                                  {:phase :wasm-typed-lowering
                                   :type type :form form})))))
            (emit-assoc [type value index replacement replacement-type env]
              (concat (i32-const (descriptor-id type)) (emit* value env)
                      (i32-const index) (emit* replacement env)
                      [0x10 (get intrinsic-indices
                                 (symbol (str "typed-assoc-"
                                              (scalar-suffix replacement-type))))]))
            (emit-match [type value-form branches env]
              (let [value-local (allocate! 0x6f)
                    tag-local (allocate! 0x7f)
                    result-type (typed/infer-type
                                 (nth (first branches) 2)
                                 (assoc (into {} (map (fn [[key item]] [key (:type item)]) env))
                                        (second (first branches))
                                        (second (first (nth type 2))))
                                 signatures)
                    setup (concat (emit* value-form env) [::local-set value-local]
                                  (i32-const (descriptor-id type)) [::local-get value-local]
                                  [0x10 (get intrinsic-indices 'typed-tag) ::local-set tag-local])
                    emit-branches
                    (fn emit-branches [index remaining]
                      (let [[_ binder body] (first remaining)
                            payload-type (second (nth (nth type 2) index))
                            binder-local (allocate! (typed/wasm-type payload-type))
                            branch-env (assoc env binder {:index binder-local :type payload-type})
                            body-code (concat (emit-get type {:wasm-local value-local} index payload-type env)
                                              [::local-set binder-local] (emit* body branch-env))]
                        (if (= 1 (count remaining))
                          body-code
                          (concat [::local-get tag-local] (i32-const index) [0x46 0x04
                                  (typed/wasm-type result-type)]
                                  body-code [0x05]
                                  (emit-branches (inc index) (rest remaining)) [0x0b]))))]
                (concat setup (emit-branches 0 branches))))
            (emit* [form env]
              (cond
                (and (map? form) (contains? form :wasm-local)) [::local-get (:wasm-local form)]
                #?(:clj (integer? form)
                   :cljs (or (i64/bigint-value? form) (integer? form)))
                (into [0x42] (sleb form))
                (or (string? form) (keyword? form) (boolean? form))
                (let [literal [(cond (string? form) :string (keyword? form) :keyword :else :bool)
                               (if (keyword? form) (str form) form)]]
                  (concat (i32-const (get literal-indices literal))
                          [0x10 (get intrinsic-indices 'typed-literal)]))
                (symbol? form) [::local-get (:index (get env form))]
                :else
                (let [[op & args] form]
                  (cond
                    (= op 'let)
                    (let [[bindings body] args]
                      (loop [remaining (partition 2 bindings) current-env env code []]
                        (if-let [[name value] (first remaining)]
                          (let [type (typed/infer-type value
                                                       (into {} (map (fn [[key item]]
                                                                      [key (:type item)]) current-env))
                                                       signatures)
                                value-code (emit* value current-env)
                                local (allocate! (typed/wasm-type type))]
                            (recur (next remaining)
                                   (assoc current-env name {:index local :type type})
                                   (concat code value-code [::local-set local])))
                          (concat code (emit* body current-env)))))
                    (= op 'if)
                    (let [[test then else] args
                          result-type (typed/infer-type
                                       then
                                       (into {} (map (fn [[key item]] [key (:type item)]) env))
                                       signatures)]
                      (concat (emit-test test env)
                              [0x04 (typed/wasm-type result-type)]
                              (emit* then env) [0x05] (emit* else env) [0x0b]))
                    (= op 'f64-to-bits)
                    (let [value-local (allocate! 0x7c)]
                      (concat (emit* (first args) env) [::local-set value-local]
                              [::local-get value-local ::local-get value-local 0x62 0x04 0x7e]
                              [0x42] (sleb 9221120237041090560)
                              [0x05 ::local-get value-local 0xbd 0x0b]))
                    (= op 'f64-from-bits)
                    (let [value-local (allocate! 0x7c)]
                      (concat (emit* (first args) env) [0xbf ::local-set value-local]
                              [::local-get value-local ::local-get value-local 0x62 0x04 0x7c]
                              [0x42] (sleb 9221120237041090560) [0xbf]
                              [0x05 ::local-get value-local 0x0b]))
                    (= op 'i64-to-f64-rounded)
                    (concat (emit* (first args) env) [0xb9])
                    (= op 'i64-to-f64-checked)
                    (let [source-local (allocate! 0x7e)
                          result-local (allocate! 0x7c)]
                      (concat (emit* (first args) env) [::local-set source-local]
                              [::local-get source-local 0xb9 ::local-set result-local]
                              ;; The truncation itself traps if rounding crossed
                              ;; the signed-i64 boundary.  Otherwise require an
                              ;; exact round-trip before returning the f64.
                              [::local-get result-local 0xb0 ::local-get source-local 0x52
                               0x04 0x40 0x00 0x0b ::local-get result-local]))
                    (= op 'f64-to-i64-truncating)
                    (concat (emit* (first args) env) [0xb0])
                    (= op 'f64-to-i64-checked)
                    (let [source-local (allocate! 0x7c)
                          result-local (allocate! 0x7e)]
                      (concat (emit* (first args) env) [::local-set source-local]
                              [::local-get source-local 0xb0 ::local-set result-local]
                              [::local-get result-local 0xb9 ::local-get source-local 0x62
                               0x04 0x40 0x00 0x0b ::local-get result-local]))
                    (contains? '#{f64-add f64-sub f64-mul f64-div f64-min f64-max} op)
                    (concat (emit* (first args) env) (emit* (second args) env)
                            [({'f64-add 0xa0 'f64-sub 0xa1 'f64-mul 0xa2 'f64-div 0xa3
                               'f64-min 0xa4 'f64-max 0xa5} op)])
                    (= op 'f64-neg)
                    (concat (emit* (first args) env) [0x9a])
                    (= op 'f64-abs)
                    (concat (emit* (first args) env) [0x99])
                    (= op 'f64-sqrt)
                    (concat (emit* (first args) env) [0x9f])
                    (contains? '#{f64-sin-quarter-turn f64-cos-quarter-turn} op)
                    (let [value-local (allocate! 0x7c)
                          value {:wasm-local value-local}
                          domain-check (concat
                                        [::local-get value-local ::local-get value-local 0x62
                                         ::local-get value-local 0x99]
                                        (emit* (f64-constant "4605249457297304856") env)
                                        [0x64 0x72 0x04 0x40 0x00 0x0b])]
                      (concat (emit* (first args) env) [::local-set value-local]
                              domain-check
                              (if (= op 'f64-sin-quarter-turn)
                                (concat [::local-get value-local]
                                        (emit* (f64-constant 0) env) [0x61 0x04 0x7c]
                                        [::local-get value-local 0x05]
                                        (emit* (bounded-sin-form value) env) [0x0b])
                                (emit* (bounded-cos-form value) env))))
                    (contains? '#{f64-sin-bounded f64-cos-bounded} op)
                    (let [value-local (allocate! 0x7c)
                          scaled-local (allocate! 0x7c)
                          nearest-local (allocate! 0x7c)
                          quadrant-local (allocate! 0x7e)
                          reduced-local (allocate! 0x7c)
                          reduced {:wasm-local reduced-local}
                          sin-code (concat [::local-get reduced-local]
                                           (emit* (f64-constant 0) env) [0x61 0x04 0x7c]
                                           [::local-get reduced-local 0x05]
                                           (emit* (bounded-sin-form reduced) env) [0x0b])
                          cos-code (emit* (bounded-cos-form reduced) env)
                          neg-sin (concat sin-code [0x9a])
                          neg-cos (concat cos-code [0x9a])
                          branch-code
                          (if (= op 'f64-sin-bounded)
                            [sin-code cos-code neg-sin neg-cos]
                            [cos-code neg-sin neg-cos sin-code])
                          choose (fn choose [quadrant]
                                   (if (= quadrant 3)
                                     (nth branch-code quadrant)
                                     (concat [::local-get quadrant-local 0x42] (sleb quadrant) [0x51 0x04 0x7c]
                                             (nth branch-code quadrant) [0x05]
                                             (choose (inc quadrant)) [0x0b])))]
                      (concat
                       (emit* (first args) env) [::local-set value-local]
                       ;; Reject NaN, infinities, and values outside ±8192π.
                       [::local-get value-local ::local-get value-local 0x62
                        ::local-get value-local 0x99]
                       (emit* (f64-constant "4672803451707862296") env)
                       [0x64 0x72 0x04 0x40 0x00 0x0b]
                       ;; scaled=x*(2/π), then nearest integer with ties away from zero.
                       [::local-get value-local]
                       (emit* (f64-constant "4603909380684499075") env)
                       [0xa2 ::local-set scaled-local ::local-get scaled-local]
                       (emit* (f64-constant "0") env) [0x66 0x04 0x7c]
                       [::local-get scaled-local]
                       (emit* (f64-constant "4602678819172646912") env) [0xa0 0x9c 0x05]
                       [::local-get scaled-local]
                       (emit* (f64-constant "-4620693217682128896") env) [0xa0 0x9b 0x0b]
                       [::local-set nearest-local]
                       ;; r=(x-n*pi/2_hi)-n*pi/2_lo.
                       [::local-get value-local ::local-get nearest-local]
                       (emit* (f64-constant "4609753056924675352") env) [0xa2 0xa1]
                       [::local-get nearest-local]
                       (emit* (f64-constant "4364452196894661639") env) [0xa2 0xa1 ::local-set reduced-local]
                       [::local-get nearest-local 0xb0 0x42] (sleb 3) [0x83 ::local-set quadrant-local]
                       (choose 0)))
                    (= op 'f64-exp-near-zero)
                    (let [value-local (allocate! 0x7c)
                          value {:wasm-local value-local}]
                      (concat (emit* (first args) env) [::local-set value-local]
                              [::local-get value-local ::local-get value-local 0x62
                               ::local-get value-local 0x99]
                              (emit* (f64-constant "4602678819172646912") env)
                              [0x64 0x72 0x04 0x40 0x00 0x0b]
                              (emit* (bounded-exp-form value) env)))
                    (= op 'f64-log-near-one)
                    (let [value-local (allocate! 0x7c)
                          value {:wasm-local value-local}]
                      (concat (emit* (first args) env) [::local-set value-local]
                              [::local-get value-local ::local-get value-local 0x62
                               ::local-get value-local]
                              (emit* (f64-constant "4604930618986332160") env)
                              [0x63 0x72 ::local-get value-local]
                              (emit* (f64-constant "4609434218613702656") env)
                              [0x64 0x72 0x04 0x40 0x00 0x0b]
                              (emit* (bounded-log-form value) env)))
                    (= op 'f64-atan2-bounded)
                    (let [y-local (allocate! 0x7c)
                          x-local (allocate! 0x7c)
                          y {:wasm-local y-local}
                          x {:wasm-local x-local}
                          finite-check (fn [local]
                                         (concat [::local-get local ::local-get local 0x62
                                                  ::local-get local 0x99]
                                                 (emit* (f64-constant "9218868437227405311") env)
                                                 [0x64 0x72]))]
                      (concat (emit* (first args) env) [::local-set y-local]
                              (emit* (second args) env) [::local-set x-local]
                              (finite-check y-local) (finite-check x-local)
                              [0x72 0x04 0x40 0x00 0x0b]
                              (emit* (bounded-atan2-form y x) env)))
                    (= op 'f64-exp-bounded)
                    (let [value-local (allocate! 0x7c)
                          value {:wasm-local value-local}]
                      (concat (emit* (first args) env) [::local-set value-local]
                              [::local-get value-local ::local-get value-local 0x62
                               ::local-get value-local 0x99]
                              (emit* (f64-constant "4644950930959776239") env)
                              [0x64 0x72 0x04 0x40 0x00 0x0b]
                              (emit* (wide-exp-form value) env)))
                    (= op 'f64-log-bounded)
                    (let [value-local (allocate! 0x7c)
                          value {:wasm-local value-local}]
                      (concat (emit* (first args) env) [::local-set value-local]
                              [::local-get value-local ::local-get value-local 0x62
                               ::local-get value-local]
                              (emit* (f64-constant "2301339409586323456") env)
                              [0x63 0x72 ::local-get value-local]
                              (emit* (f64-constant "6913025428013711360") env)
                              [0x64 0x72 0x04 0x40 0x00 0x0b]
                              (emit* (wide-log-form value) env)))
                    (contains? '#{f64-eq f64-lt f64-le f64-gt f64-ge} op)
                    (concat (emit* (first args) env) (emit* (second args) env)
                            [({'f64-eq 0x61 'f64-lt 0x63 'f64-gt 0x64
                               'f64-le 0x65 'f64-ge 0x66} op)
                             0x10 (get intrinsic-indices 'typed-bool)])
                    (= op 'f64-unordered)
                    (let [left-local (allocate! 0x7c)
                          right-local (allocate! 0x7c)]
                      (concat (emit* (first args) env) [::local-set left-local]
                              (emit* (second args) env) [::local-set right-local]
                              [::local-get left-local ::local-get left-local 0x62
                               ::local-get right-local ::local-get right-local 0x62 0x72
                               0x10 (get intrinsic-indices 'typed-bool)]))
                    (= op 'f32-to-bits)
                    (let [value-local (allocate! 0x7d)]
                      (concat (emit* (first args) env) [::local-set value-local]
                              [::local-get value-local ::local-get value-local 0x5c 0x04 0x7e]
                              [0x42] (sleb 2143289344)
                              [0x05 ::local-get value-local 0xbc 0xac 0x0b]))
                    (= op 'f32-from-bits)
                    (let [bits-local (allocate! 0x7e)
                          value-local (allocate! 0x7d)]
                      (concat (emit* (first args) env) [::local-set bits-local]
                              [::local-get bits-local 0x42] (sleb -2147483648) [0x53]
                              [::local-get bits-local 0x42] (sleb 2147483647) [0x55 0x72]
                              [0x04 0x40 0x00 0x0b]
                              [::local-get bits-local 0xa7 0xbe ::local-set value-local]
                              [::local-get value-local ::local-get value-local 0x5c 0x04 0x7d]
                              [0x42] (sleb 2143289344) [0xa7 0xbe]
                              [0x05 ::local-get value-local 0x0b]))
                    (= op 'f64-to-f32-rounded)
                    (concat (emit* (first args) env) [0xb6])
                    (= op 'f32-to-f64-exact)
                    (concat (emit* (first args) env) [0xbb])
                    (= op 'i64-to-f32-rounded)
                    (concat (emit* (first args) env) [0xb4])
                    (= op 'i64-to-f32-checked)
                    (let [source-local (allocate! 0x7e)
                          result-local (allocate! 0x7d)]
                      (concat (emit* (first args) env) [::local-set source-local]
                              [::local-get source-local 0xb4 ::local-set result-local]
                              [::local-get result-local 0xae ::local-get source-local 0x52
                               0x04 0x40 0x00 0x0b ::local-get result-local]))
                    (= op 'f32-to-i64-truncating)
                    (concat (emit* (first args) env) [0xae])
                    (= op 'f32-to-i64-checked)
                    (let [source-local (allocate! 0x7d)
                          result-local (allocate! 0x7e)]
                      (concat (emit* (first args) env) [::local-set source-local]
                              [::local-get source-local 0xae ::local-set result-local]
                              [::local-get result-local 0xb4 ::local-get source-local 0x5c
                               0x04 0x40 0x00 0x0b ::local-get result-local]))
                    (contains? '#{f32-add f32-sub f32-mul f32-div f32-min f32-max} op)
                    (concat (emit* (first args) env) (emit* (second args) env)
                            [({'f32-add 0x92 'f32-sub 0x93 'f32-mul 0x94 'f32-div 0x95
                               'f32-min 0x96 'f32-max 0x97} op)])
                    (= op 'f32-neg)
                    (concat (emit* (first args) env) [0x8c])
                    (= op 'f32-abs)
                    (concat (emit* (first args) env) [0x8b])
                    (= op 'f32-sqrt)
                    (concat (emit* (first args) env) [0x91])
                    (contains? '#{f32-eq f32-lt f32-le f32-gt f32-ge} op)
                    (concat (emit* (first args) env) (emit* (second args) env)
                            [({'f32-eq 0x5b 'f32-lt 0x5d 'f32-gt 0x5e
                               'f32-le 0x5f 'f32-ge 0x60} op)
                             0x10 (get intrinsic-indices 'typed-bool)])
                    (= op 'f32-unordered)
                    (let [left-local (allocate! 0x7d)
                          right-local (allocate! 0x7d)]
                      (concat (emit* (first args) env) [::local-set left-local]
                              (emit* (second args) env) [::local-set right-local]
                              [::local-get left-local ::local-get left-local 0x5c
                               ::local-get right-local ::local-get right-local 0x5c 0x72
                               0x10 (get intrinsic-indices 'typed-bool)]))
                    (contains? '#{+ - * quot bit-xor bit-and} op)
                    (let [opcode ({'+ 0x7c '- 0x7d '* 0x7e 'quot 0x7f
                                   'bit-and 0x83 'bit-xor 0x85} op)]
                      (if (and (= op '-) (= 1 (count args)))
                        (concat [0x42 0] (emit* (first args) env) [0x7d])
                        (concat (emit* (first args) env)
                                (mapcat #(concat (emit* % env) [opcode]) (rest args)))))
                    (= op 'i32-wrap)
                    (concat (emit* (first args) env) [0xa7 0xac])
                    (= op 'u32-wrap)
                    (concat (emit* (first args) env) [0xa7 0xad])
                    (contains? '#{i32-wrapping-add i32-wrapping-mul i32-xor} op)
                    (concat (emit* (first args) env) [0xa7]
                            (emit* (second args) env) [0xa7]
                            [({'i32-wrapping-add 0x6a 'i32-wrapping-mul 0x6c 'i32-xor 0x73} op)
                             0xac])
                    (contains? '#{i32-shift-left i32-shift-right u32-shift-right} op)
                    (concat (emit* (first args) env) [0xa7]
                            (emit* (second args) env) [0xa7]
                            [({'i32-shift-left 0x74 'i32-shift-right 0x75 'u32-shift-right 0x76} op)
                             (if (= op 'u32-shift-right) 0xad 0xac)])
                    (= op 'string-byte-length)
                    (concat (i32-const (descriptor-id :string)) (emit* (first args) env)
                            [0x10 (get intrinsic-indices 'typed-count)])
                    (= op 'vector-new)
                    (emit-builder :vector-i64 -1 args (repeat (count args) :i64) env)
                    (= op 'vector-count)
                    (concat (i32-const (descriptor-id :vector-i64)) (emit* (first args) env)
                            [0x10 (get intrinsic-indices 'typed-count)])
                    (= op 'vector-at)
                    (concat (i32-const (descriptor-id :vector-i64))
                            (emit* (first args) env) (emit* (second args) env)
                            [0x10 (get intrinsic-indices 'typed-vector-at-i64)])
                    (= op 'vector-get)
                    (let [[value index fallback] args
                          value-local (allocate! 0x6f)
                          index-local (allocate! 0x7e)]
                      (concat (emit* value env) [::local-set value-local]
                              (emit* index env) [::local-set index-local]
                              [::local-get index-local 0x42 0 0x59 ::local-get index-local]
                              (i32-const (descriptor-id :vector-i64)) [::local-get value-local]
                              [0x10 (get intrinsic-indices 'typed-count) 0x54 0x71 0x04 0x7e]
                              (i32-const (descriptor-id :vector-i64))
                              [::local-get value-local ::local-get index-local
                               0x10 (get intrinsic-indices 'typed-vector-at-i64) 0x05]
                              (emit* fallback env) [0x0b]))
                    (= op 'vector-drop)
                    (concat (i32-const (descriptor-id :vector-i64))
                            (emit* (first args) env) (emit* (second args) env)
                            [0x10 (get intrinsic-indices 'typed-vector-drop)])
                    (= op 'vector-assoc)
                    (concat (i32-const (descriptor-id :vector-i64))
                            (emit* (first args) env) (emit* (second args) env)
                            (emit* (nth args 2) env)
                            [0x10 (get intrinsic-indices 'typed-vector-assoc-i64)])
                    (= op 'vector-conj)
                    (concat (i32-const (descriptor-id :vector-i64))
                            (emit* (first args) env) (emit* (second args) env)
                            [0x10 (get intrinsic-indices 'typed-vector-conj-i64)])
                    (= op 'vector-f64-new)
                    (emit-builder :vector-f64 -1 args (repeat (count args) :f64) env)
                    (= op 'vector-f64-count)
                    (concat (i32-const (descriptor-id :vector-f64)) (emit* (first args) env)
                            [0x10 (get intrinsic-indices 'typed-count)])
                    (= op 'vector-f64-at)
                    (concat (i32-const (descriptor-id :vector-f64))
                            (emit* (first args) env) (emit* (second args) env)
                            [0x10 (get intrinsic-indices 'typed-vector-at-f64)])
                    (= op 'vector-f64-get)
                    (let [[value index fallback] args
                          value-local (allocate! 0x6f)
                          index-local (allocate! 0x7e)]
                      (concat (emit* value env) [::local-set value-local]
                              (emit* index env) [::local-set index-local]
                              [::local-get index-local 0x42 0 0x59 ::local-get index-local]
                              (i32-const (descriptor-id :vector-f64)) [::local-get value-local]
                              [0x10 (get intrinsic-indices 'typed-count) 0x54 0x71 0x04 0x7c]
                              (i32-const (descriptor-id :vector-f64))
                              [::local-get value-local ::local-get index-local
                               0x10 (get intrinsic-indices 'typed-vector-at-f64) 0x05]
                              (emit* fallback env) [0x0b]))
                    (= op 'vector-f64-drop)
                    (concat (i32-const (descriptor-id :vector-f64))
                            (emit* (first args) env) (emit* (second args) env)
                            [0x10 (get intrinsic-indices 'typed-vector-drop)])
                    (= op 'vector-f64-assoc)
                    (concat (i32-const (descriptor-id :vector-f64))
                            (emit* (first args) env) (emit* (second args) env)
                            (emit* (nth args 2) env)
                            [0x10 (get intrinsic-indices 'typed-vector-assoc-f64)])
                    (= op 'vector-f64-conj)
                    (concat (i32-const (descriptor-id :vector-f64))
                            (emit* (first args) env) (emit* (second args) env)
                            [0x10 (get intrinsic-indices 'typed-vector-conj-f64)])
                    (= op 'string=?)
                    (concat (i32-const (descriptor-id :string))
                            (emit* (first args) env) (emit* (second args) env)
                            [0x10 (get intrinsic-indices 'typed-equal) 0xad])
                    (= op 'bool-not)
                    (emit-bool (concat (emit-test (first args) env) [0x45]))
                    (contains? '#{= < > <= >=} op)
                    (let [operand-type (typed/infer-type
                                        (first args)
                                        (into {} (map (fn [[key item]] [key (:type item)]) env))
                                        signatures)]
                      (if (= operand-type :i64)
                        (concat (emit* (first args) env) (emit* (second args) env)
                                [({'= 0x51 '< 0x53 '> 0x55 '<= 0x57 '>= 0x59} op)
                                 0xad])
                        (if (= op '=)
                          (emit-equal operand-type (first args) (second args) env)
                          (throw (ex-info "typed Wasm ordering requires i64 operands"
                                          {:phase :wasm-typed-lowering
                                           :operation op :type operand-type})))))
                    (contains? '#{option-some-of option-none-of} op)
                    (let [[type & payload] args]
                      (emit-builder type (if (= op 'option-some-of) 1 0) payload
                                    (if (seq payload) [(second type)] []) env))
                    (contains? '#{result-ok-of result-err-of} op)
                    (let [[type payload] args
                          tag (if (= op 'result-ok-of) 1 0)]
                      (emit-builder type tag [payload]
                                    [(if (= tag 1) (second type) (nth type 2))] env))
                    (= op 'variant-new)
                    (let [[type tag payload] args
                          index (first (keep-indexed #(when (= tag (first %2)) %1)
                                                     (nth type 2)))
                          payload-type (second (nth (nth type 2) index))]
                      (emit-builder type index [payload] [payload-type] env))
                    (= op 'hetero-vector-new)
                    (let [[type & items] args]
                      (emit-builder type -1 items (second type) env))
                    (= op 'typed-set-new)
                    (let [[type & items] args]
                      (emit-builder type -1 items (repeat (count items) (second type)) env))
                    (= op 'typed-map-new)
                    (let [[type & items] args]
                      (emit-builder type -1 items
                                    (take (count items) (cycle [(second type) (nth type 2)])) env))
                    (= op 'record-new)
                    (let [[type & items] args]
                      (emit-builder type -1 items (map second (nth type 2)) env))
                    (= op 'option-match)
                    (let [[type value none-body binder some-body] args
                          value-local (allocate! 0x6f)
                          binder-type (second type)
                          binder-local (allocate! (typed/wasm-type binder-type))
                          result-type (typed/infer-type none-body
                                                       (into {} (map (fn [[key item]] [key (:type item)]) env))
                                                       signatures)]
                      (concat (emit* value env) [::local-set value-local]
                              (i32-const (descriptor-id type)) [::local-get value-local]
                              [0x10 (get intrinsic-indices 'typed-tag) 0x04
                               (typed/wasm-type result-type)]
                              (emit-get type {:wasm-local value-local} 0 binder-type env)
                              [::local-set binder-local]
                              (emit* some-body (assoc env binder {:index binder-local :type binder-type}))
                              [0x05] (emit* none-body env) [0x0b]))
                    (= op 'result-match-of)
                    (let [[type value ok-name ok-body err-name err-body] args
                          value-local (allocate! 0x6f)
                          ok-type (second type)
                          err-type (nth type 2)
                          ok-local (allocate! (typed/wasm-type ok-type))
                          err-local (allocate! (typed/wasm-type err-type))
                          result-type (typed/infer-type ok-body
                                                       (assoc (into {} (map (fn [[key item]] [key (:type item)]) env))
                                                              ok-name ok-type)
                                                       signatures)]
                      (concat (emit* value env) [::local-set value-local]
                              (i32-const (descriptor-id type)) [::local-get value-local]
                              [0x10 (get intrinsic-indices 'typed-tag) 0x04
                               (typed/wasm-type result-type)]
                              (emit-get type {:wasm-local value-local} 0 ok-type env)
                              [::local-set ok-local]
                              (emit* ok-body (assoc env ok-name {:index ok-local :type ok-type}))
                              [0x05]
                              (emit-get type {:wasm-local value-local} 0 err-type env)
                              [::local-set err-local]
                              (emit* err-body (assoc env err-name {:index err-local :type err-type}))
                              [0x0b]))
                    (= op 'variant-match) (emit-match (first args) (second args) (nth args 2) env)
                    (= op 'hetero-vector-at)
                    (let [[type value index] args
                          item-type (nth (second type) index)]
                      (emit-get type value index item-type env))
                    (= op 'record-get)
                    (let [[type value field] args
                          index (first (keep-indexed #(when (= field (first %2)) %1) (nth type 2)))
                          item-type (second (nth (nth type 2) index))]
                      (emit-get type value index item-type env))
                    (contains? '#{option-some?-of result-ok?-of} op)
                    (let [[type value] args]
                      (emit-bool (concat (i32-const (descriptor-id type)) (emit* value env)
                                         [0x10 (get intrinsic-indices 'typed-tag)])))
                    (contains? '#{option-value-of result-value-of result-error-of} op)
                    (let [[type value fallback] args
                          wanted (if (= op 'result-error-of) 0 1)
                          payload-type (case op
                                         option-value-of (second type)
                                         result-value-of (second type)
                                         result-error-of (nth type 2))
                          value-local (allocate! 0x6f)]
                      (concat (emit* value env) [::local-set value-local]
                              (i32-const (descriptor-id type)) [::local-get value-local]
                              [0x10 (get intrinsic-indices 'typed-tag)]
                              (i32-const wanted) [0x46 0x04 (typed/wasm-type payload-type)]
                              (emit-get type {:wasm-local value-local} 0 payload-type env)
                              [0x05] (emit* fallback env) [0x0b]))
                    (= op 'hetero-vector-assoc)
                    (let [[type value index replacement] args]
                      (emit-assoc type value index replacement (nth (second type) index) env))
                    (= op 'record-assoc)
                    (let [[type value field replacement] args
                          index (first (keep-indexed #(when (= field (first %2)) %1) (nth type 2)))
                          replacement-type (second (nth (nth type 2) index))]
                      (emit-assoc type value index replacement replacement-type env))
                    (contains? '#{hetero-vector-equal typed-set-equal typed-map-equal record-equal} op)
                    (let [[type left right] args] (emit-equal type left right env))
                    (contains? '#{typed-set-contains typed-set-conj typed-set-disj} op)
                    (let [[type value item] args
                          item-type (second type)
                          operation ({'typed-set-contains 0 'typed-set-conj 1 'typed-set-disj 2} op)
                          contains? (= op 'typed-set-contains)
                          code (concat (i32-const (descriptor-id type)) (emit* value env)
                                       (when-not contains? (i32-const operation)) (emit* item env)
                                       [0x10 (get intrinsic-indices
                                                  (if contains?
                                                    (if (= item-type :i64)
                                                      'typed-set-contains-i64 'typed-set-contains-ref)
                                                    (if (= item-type :i64)
                                                      'typed-set-op-i64 'typed-set-op-ref)))])]
                      (if (= op 'typed-set-contains) (emit-bool code) code))
                    (contains? '#{hetero-vector-count typed-set-count} op)
                    (let [[type value] args]
                      (concat (i32-const (descriptor-id type)) (emit* value env)
                              [0x10 (get intrinsic-indices 'typed-count)]))
                    (= op 'typed-map-count)
                    (let [[type value] args]
                      (concat (i32-const (descriptor-id type)) (emit* value env)
                              [0x10 (get intrinsic-indices 'typed-count)]))
                    (contains? '#{typed-map-contains typed-map-get typed-map-dissoc} op)
                    (let [[type value key] args
                          key-type (second type)
                          prefix (case op
                                   typed-map-contains "typed-map-contains-"
                                   typed-map-get "typed-map-get-"
                                   typed-map-dissoc "typed-map-dissoc-")
                          intrinsic (symbol (str prefix (if (= key-type :i64) "i64" "ref")))
                          code (concat (i32-const (descriptor-id type)) (emit* value env)
                                       (emit* key env) [0x10 (get intrinsic-indices intrinsic)])]
                      (if (= op 'typed-map-contains) (emit-bool code) code))
                    (= op 'typed-map-entry-at)
                    (let [[type value index] args]
                      (concat (i32-const (descriptor-id type)) (emit* value env)
                              (emit* index env)
                              [0x10 (get intrinsic-indices 'typed-map-entry-at)]))
                    (= op 'typed-map-assoc)
                    (let [[type value key item] args
                          key-code (if (= (second type) :i64) "i" "r")
                          item-code (if (= (nth type 2) :i64) "i" "r")
                          intrinsic (symbol (str "typed-map-assoc-" key-code item-code))]
                      (concat (i32-const (descriptor-id type)) (emit* value env)
                              (emit* key env) (emit* item env)
                              [0x10 (get intrinsic-indices intrinsic)]))
                    (= op 'xml-path-count)
                    (concat (emit* (first args) env) (emit* (second args) env)
                            [0x10 (get intrinsic-indices 'xml-path-count)])
                    (= op 'xml-path-attr)
                    (concat (emit* (nth args 0) env) (emit* (nth args 1) env)
                            (emit* (nth args 2) env) (emit* (nth args 3) env)
                            [0x10 (get intrinsic-indices 'xml-path-attr)])
                    (= op 'decimal-f64-parse)
                    (concat (emit* (first args) env)
                            [0x10 (get intrinsic-indices 'decimal-f64-parse)])
                    (= op 'decimal-f64x3-parse)
                    (concat (emit* (first args) env)
                            [0x10 (get intrinsic-indices 'decimal-f64x3-parse)])
                    :else
                    (if-let [function-index (get function-indices op)]
                      (concat (mapcat #(emit* % env) args) [0x10 function-index])
                      (throw (ex-info "typed Wasm operation is not qualified"
                                      {:phase :wasm-typed-lowering
                                       :operation op :form form})))))))]
      (let [prefix (mapcat (fn [[index type]]
                             (when (typed/reference-type? type)
                               (concat (i32-const (descriptor-id type)) [::local-get index]
                                       [0x10 (get intrinsic-indices 'typed-assert-ref)
                                        ::local-set index])))
                           (map-indexed vector (:param-types function)))
            body-code (emit* (:body function) env)
            body-code (if (typed/reference-type? (:result function))
                        (concat (i32-const (descriptor-id (:result function))) body-code
                                [0x10 (get intrinsic-indices 'typed-assert-ref)])
                        body-code)
            declarations (if (empty? @locals) [0]
                           (concat (uleb (count @locals))
                                   (mapcat (fn [type] [1 type]) @locals)))
            charge [0x23 0 0x50 0x04 0x40 0x00 0x0b
                    0x23 0 0x42 1 0x7d 0x24 0]
            instructions (encode-local-operands (concat prefix charge body-code))
            body (concat declarations instructions [0x0b])]
        (concat (uleb (count body)) body)))))

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
        instructions (encode-local-operands
                      (concat charge (emit-expr (:body function) param-env
                                      {:function-indices function-indices
                                       :intrinsic-indices intrinsic-indices
                                       :next-local (count (:params function))})))
        body (concat declarations instructions [0x0b])]
    (concat (uleb (count body)) body)))

(defn- uses-operation? [functions operations]
  (boolean
   (some (fn [function]
           (some #(and (seq? %) (contains? operations (first %)))
                 (tree-seq coll? seq (:body function))))
         functions)))

(defn emit [kir target]
  (let [functions (:functions kir)
        typed? (= :kotoba.kir/v4 (:format kir))
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
        has-xml? (uses-operation? functions '#{xml-path-count xml-path-attr})
        has-decimal? (uses-operation? functions '#{decimal-f64-parse})
        has-decimal-x3? (uses-operation? functions '#{decimal-f64x3-parse})
        typed-imports (when (and typed? (typed/requires-host-runtime? kir))
                        (vec (concat
                         [['typed-literal "kotoba:typed" "literal" [0x60 1 0x7f 1 0x6f]]
                         ['typed-new "kotoba:typed" "new" [0x60 2 0x7f 0x7f 1 0x6f]]
                         ['typed-push-i64 "kotoba:typed" "push-i64" [0x60 2 0x6f 0x7e 1 0x6f]]
                         ['typed-push-f64 "kotoba:typed" "push-f64" [0x60 2 0x6f 0x7c 1 0x6f]]
                         ['typed-push-f32 "kotoba:typed" "push-f32" [0x60 2 0x6f 0x7d 1 0x6f]]
                         ['typed-push-ref "kotoba:typed" "push-ref" [0x60 2 0x6f 0x6f 1 0x6f]]
                         ['typed-seal "kotoba:typed" "seal" [0x60 2 0x7f 0x6f 1 0x6f]]
                         ['typed-assert-ref "kotoba:typed" "assert-ref" [0x60 2 0x7f 0x6f 1 0x6f]]
                         ['typed-tag "kotoba:typed" "tag" [0x60 2 0x7f 0x6f 1 0x7f]]
                         ['typed-get-i64 "kotoba:typed" "get-i64" [0x60 3 0x7f 0x6f 0x7f 1 0x7e]]
                         ['typed-get-f64 "kotoba:typed" "get-f64" [0x60 3 0x7f 0x6f 0x7f 1 0x7c]]
                         ['typed-get-f32 "kotoba:typed" "get-f32" [0x60 3 0x7f 0x6f 0x7f 1 0x7d]]
                         ['typed-get-ref "kotoba:typed" "get-ref" [0x60 3 0x7f 0x6f 0x7f 1 0x6f]]
                         ['typed-count "kotoba:typed" "count" [0x60 2 0x7f 0x6f 1 0x7e]]
                         ['typed-bool "kotoba:typed" "bool" [0x60 1 0x7f 1 0x6f]]
                         ['typed-equal "kotoba:typed" "equal" [0x60 3 0x7f 0x6f 0x6f 1 0x7f]]
                         ['typed-assoc-i64 "kotoba:typed" "assoc-i64" [0x60 4 0x7f 0x6f 0x7f 0x7e 1 0x6f]]
                         ['typed-assoc-f64 "kotoba:typed" "assoc-f64" [0x60 4 0x7f 0x6f 0x7f 0x7c 1 0x6f]]
                         ['typed-assoc-f32 "kotoba:typed" "assoc-f32" [0x60 4 0x7f 0x6f 0x7f 0x7d 1 0x6f]]
                         ['typed-assoc-ref "kotoba:typed" "assoc-ref" [0x60 4 0x7f 0x6f 0x7f 0x6f 1 0x6f]]
                         ['typed-vector-drop "kotoba:typed" "vector-drop" [0x60 3 0x7f 0x6f 0x7e 1 0x6f]]
                         ['typed-vector-at-i64 "kotoba:typed" "vector-at-i64" [0x60 3 0x7f 0x6f 0x7e 1 0x7e]]
                         ['typed-vector-assoc-i64 "kotoba:typed" "vector-assoc-i64" [0x60 4 0x7f 0x6f 0x7e 0x7e 1 0x6f]]
                         ['typed-vector-conj-i64 "kotoba:typed" "vector-conj-i64" [0x60 3 0x7f 0x6f 0x7e 1 0x6f]]
                         ['typed-vector-at-f64 "kotoba:typed" "vector-at-f64" [0x60 3 0x7f 0x6f 0x7e 1 0x7c]]
                         ['typed-vector-assoc-f64 "kotoba:typed" "vector-assoc-f64" [0x60 4 0x7f 0x6f 0x7e 0x7c 1 0x6f]]
                         ['typed-vector-conj-f64 "kotoba:typed" "vector-conj-f64" [0x60 3 0x7f 0x6f 0x7c 1 0x6f]]
                         ['typed-set-op-i64 "kotoba:typed" "set-op-i64" [0x60 4 0x7f 0x6f 0x7f 0x7e 1 0x6f]]
                         ['typed-set-op-ref "kotoba:typed" "set-op-ref" [0x60 4 0x7f 0x6f 0x7f 0x6f 1 0x6f]]
                         ['typed-set-contains-i64 "kotoba:typed" "set-contains-i64" [0x60 3 0x7f 0x6f 0x7e 1 0x7f]]
                         ['typed-set-contains-ref "kotoba:typed" "set-contains-ref" [0x60 3 0x7f 0x6f 0x6f 1 0x7f]]
                         ['typed-map-contains-i64 "kotoba:typed" "map-contains-i64" [0x60 3 0x7f 0x6f 0x7e 1 0x7f]]
                         ['typed-map-contains-ref "kotoba:typed" "map-contains-ref" [0x60 3 0x7f 0x6f 0x6f 1 0x7f]]
                         ['typed-map-get-i64 "kotoba:typed" "map-get-i64" [0x60 3 0x7f 0x6f 0x7e 1 0x6f]]
                         ['typed-map-get-ref "kotoba:typed" "map-get-ref" [0x60 3 0x7f 0x6f 0x6f 1 0x6f]]
                         ['typed-map-entry-at "kotoba:typed" "map-entry-at" [0x60 3 0x7f 0x6f 0x7e 1 0x6f]]
                         ['typed-map-assoc-ii "kotoba:typed" "map-assoc-ii" [0x60 4 0x7f 0x6f 0x7e 0x7e 1 0x6f]]
                         ['typed-map-assoc-ir "kotoba:typed" "map-assoc-ir" [0x60 4 0x7f 0x6f 0x7e 0x6f 1 0x6f]]
                         ['typed-map-assoc-ri "kotoba:typed" "map-assoc-ri" [0x60 4 0x7f 0x6f 0x6f 0x7e 1 0x6f]]
                         ['typed-map-assoc-rr "kotoba:typed" "map-assoc-rr" [0x60 4 0x7f 0x6f 0x6f 0x6f 1 0x6f]]
                         ['typed-map-dissoc-i64 "kotoba:typed" "map-dissoc-i64" [0x60 3 0x7f 0x6f 0x7e 1 0x6f]]
                         ['typed-map-dissoc-ref "kotoba:typed" "map-dissoc-ref" [0x60 3 0x7f 0x6f 0x6f 1 0x6f]]]
                         (when has-xml?
                           [['xml-path-count "kotoba:typed" "xml-path-count" [0x60 2 0x6f 0x6f 1 0x7e]]
                            ['xml-path-attr "kotoba:typed" "xml-path-attr" [0x60 4 0x6f 0x6f 0x7e 0x6f 1 0x6f]]])
                         (when has-decimal?
                           [['decimal-f64-parse "kotoba:typed" "decimal-f64-parse" [0x60 1 0x6f 1 0x6f]]])
                         (when has-decimal-x3?
                           [['decimal-f64x3-parse "kotoba:typed" "decimal-f64x3-parse" [0x60 1 0x6f 1 0x6f]]]))))
        imports (vec (concat typed-imports
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
                      (mapcat #(nth % 3) imports)
                      (mapcat (if typed? typed-function-type function-type) functions))
        import-sec (when (seq imports)
                     (concat (uleb shift)
                             (mapcat (fn [[_ module field _] index]
                                       (concat (name-bytes module) (name-bytes field)
                                               [0] (uleb index)))
                                     imports (range))))
        function-sec (concat (uleb (count functions))
                             (mapcat uleb (range shift (+ shift (count functions)))))
        ;; (global (mut i64) (i64.const 512)); fixed and low enough to trap before the
        ;; host call stack becomes the limiting resource.
        global-sec [1 0x7e 1 0x42 0x80 0x04 0x0b]
        ;; Pure functions are exported with their source names. This makes
        ;; runtime parameters observable and testable without host authority.
        export-sec (concat (uleb (count exported-functions))
                           (mapcat (fn [function]
                                     (concat (name-bytes (name (:name function))) [0]
                                             (uleb (get indices (:name function)))))
                                   exported-functions))
        descriptor-indices (when typed? (typed/descriptor-indices kir))
        literal-indices (when typed? (typed/literal-indices kir))
        signatures (when typed? (typed-function-signatures functions))
        code-sec (concat
                  (uleb (count functions))
                  (mapcat #(if typed?
                             (emit-typed-function-body % indices intrinsic-indices
                                                       descriptor-indices literal-indices signatures)
                             (function-body % indices intrinsic-indices))
                          functions))
        target-sec (concat (name-bytes "kotoba.target")
                           (utf8 (name target)))
        typed-sec (when (= :kotoba.kir/v4 (:format kir))
                    (concat (name-bytes typed/custom-section-name)
                            (typed/metadata-bytes kir)))
        compatibility-sec (concat (name-bytes compatibility-section-name)
                                  (compatibility-bytes kir target))]
    (let [bytes (concat [0 0x61 0x73 0x6d 1 0 0 0] (section 0 target-sec)
                        (section 0 compatibility-sec)
                        (when typed-sec (section 0 typed-sec))
                        (section 1 types) (when (seq imports) (section 2 import-sec))
                        (section 3 function-sec) (section 6 global-sec)
                        (section 7 export-sec) (section 10 code-sec))]
      #?(:clj (byte-array (map unchecked-byte bytes))
         :cljs (js/Uint8Array.from (clj->js (map #(bit-and % 0xff) bytes)))))))
