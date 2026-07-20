(ns kotoba.compiler.ir
  ;; The whole `:require` clause (not just an item inside it) is behind the
  ;; reader-conditional: on `:clj` this file needs no extra require at all
  ;; (matching the original `(ns kotoba.compiler.ir)`), and an EMPTY
  ;; `(:require)` clause -- which is what results if only an item inside it
  ;; is conditional and the branch doesn't match -- fails ns-form spec
  ;; validation ("Extra input spec: :clojure.core.specs.alpha/ns-form",
  ;; confirmed live).
  (:require [kotoba.compiler.value :as value]
            [kotoba.compiler.decimal :as decimal]
            [kotoba.compiler.xml :as xml]
            #?@(:cljs [[kotoba.compiler.cljs-i64 :as i64]])))

(def ^:private default-fuel 512)
(def ^:private default-pair-capacity 4096)
(def ^:private default-kgraph-capacity 4096)
(def ^:dynamic *runtime-schemas* nil)

;; Shared between `kotoba.compiler.core` (JVM `clojure -M:run` path) and
;; `kotoba.compiler.nbb.cli` (nbb-native fast path) -- both admit
;; `:kotoba.hir/v3` (typed) HIR onto the x86_64/aarch64 native backends
;; ONLY when the actual features used are limited to string literals +
;; `string-byte-length`/`string=?`/`string-concat` (the only typed
;; features those backends implement); every other typed feature (maps,
;; options, results, variants, records, typed sets, heterogeneous
;; vectors, ...) still requires the kotoba-script web target or typed
;; Wasm target. A blanket per-backend allowance would silently let
;; unsupported ops reach the backend and crash confusingly instead of
;; rejecting cleanly -- so admission has to inspect which features are
;; actually used, not just the HIR format tag.
(def non-string-typed-ops
  '#{f32-to-bits f32-from-bits f64-to-f32-rounded f32-to-f64-exact
     f32-add f32-sub f32-mul f32-div f32-min f32-max f32-neg f32-abs f32-sqrt
     f32-eq f32-lt f32-le f32-gt f32-ge f32-unordered
     i64-to-f32-checked i64-to-f32-rounded f32-to-i64-checked f32-to-i64-truncating
     f64-to-bits f64-from-bits f64-add f64-sub f64-mul f64-div f64-min f64-max
     f64-neg f64-abs f64-sqrt f64-sin-quarter-turn f64-cos-quarter-turn
     f64-sin-bounded f64-cos-bounded f64-exp-near-zero f64-log-near-one f64-atan2-bounded
     f64-exp-bounded f64-log-bounded
     f64-eq f64-lt f64-le f64-gt f64-ge f64-unordered
     i64-to-f64-checked i64-to-f64-rounded f64-to-i64-checked f64-to-i64-truncating
     map-new map-get map-assoc
     bool-not option-some option-none option-some? option-value
     result-ok result-err result-ok? result-value result-error
     result-ok-of result-err-of result-ok?-of result-value-of result-error-of result-match-of
     variant-new variant-match
     option-some-of option-none-of option-some?-of option-value-of option-match
     hetero-vector-new hetero-vector-count hetero-vector-at hetero-vector-assoc hetero-vector-equal
     typed-set-new typed-set-count typed-set-contains typed-set-conj typed-set-disj typed-set-equal
     typed-map-new typed-map-count typed-map-contains typed-map-get
     typed-map-entry-at typed-map-assoc typed-map-dissoc typed-map-equal
     xml-path-count xml-path-attr decimal-f64-parse decimal-f64x3-parse
     record-new record-get record-assoc record-equal
     vector-count vector-get vector-at vector-drop vector-assoc vector-conj
     vector-f64-new vector-f64-count vector-f64-get vector-f64-at
     vector-f64-drop vector-f64-assoc vector-f64-conj
     string-index-new string-index-count string-index-contains string-index-get string-index-assoc
     disjoint-set-i64-new disjoint-set-i64-count disjoint-set-i64-union
     i32-wrap u32-wrap i32-wrapping-add i32-wrapping-mul i32-xor
     i32-shift-left i32-shift-right u32-shift-right xorshift32})

(defn only-string-typed-features? [hir]
  (letfn [(walk [form]
            (cond
              (or (string? form) (integer? form) (symbol? form)) true
              (or (keyword? form) (boolean? form)) false
              (seq? form)
              (let [[op & args] form]
                (and (not (contains? non-string-typed-ops op))
                     (every? walk args)))
              :else true))]
    (every? (fn [{:keys [param-types result body]}]
              (and (every? #{:i64 :string} param-types)
                   (contains? #{:i64 :string} result)
                   (walk body)))
            (:functions hir))))

(defn uses-f64? [program]
  (boolean
   (some (fn [{:keys [param-types result body]}]
           (or (some #{:f64} param-types)
               (= :f64 result)
               (some #(and (seq? %)
                           (contains? '#{f64-to-bits f64-from-bits
                                         f64-add f64-sub f64-mul f64-div f64-min f64-max
                                         f64-neg f64-abs f64-sqrt
                                         f64-sin-quarter-turn f64-cos-quarter-turn
                                         f64-sin-bounded f64-cos-bounded
                                         f64-exp-near-zero f64-log-near-one f64-atan2-bounded
                                         f64-exp-bounded f64-log-bounded
                                         f64-eq f64-lt f64-le f64-gt f64-ge f64-unordered
                                         i64-to-f64-checked i64-to-f64-rounded
                                         f64-to-i64-checked f64-to-i64-truncating}
                                      (first %)))
                     (tree-seq coll? seq body))))
         (:functions program))))

(defn uses-f32? [program]
  (boolean
   (some (fn [{:keys [param-types result body]}]
           (or (some #{:f32} param-types)
               (= :f32 result)
               (some #(and (seq? %)
                           (contains? '#{f32-to-bits f32-from-bits
                                         f64-to-f32-rounded f32-to-f64-exact
                                         f32-add f32-sub f32-mul f32-div f32-min f32-max
                                         f32-neg f32-abs f32-sqrt
                                         f32-eq f32-lt f32-le f32-gt f32-ge f32-unordered
                                         i64-to-f32-checked i64-to-f32-rounded
                                         f32-to-i64-checked f32-to-i64-truncating}
                                      (first %)))
                     (tree-seq coll? seq body))))
         (:functions program))))

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

(defn- f64-divide [left right]
  #?(:clj (let [^double left left ^double right right] (/ left right))
     :cljs (/ left right)))

(defn- as-f32 [value]
  #?(:clj (.floatValue ^Number value) :cljs (js/Math.fround value)))

(defn- f32-divide [left right]
  #?(:clj (let [^float left left ^float right right] (/ left right))
     :cljs (js/Math.fround (/ left right))))

(def ^:private quarter-turn 0.7853981633974483)

(defn- checked-quarter-turn [value]
  (when-not (and #?(:clj (Double/isFinite ^double value) :cljs (js/Number.isFinite value))
                 (<= (#?(:clj Math/abs :cljs js/Math.abs) value) quarter-turn))
    (trap! :f64-quarter-turn-domain {}))
  value)

(defn- f64-sin-quarter-turn [value]
  (let [value (checked-quarter-turn value)]
    (if (zero? value)
      value
      (let [z (* value value)
            p (+ -7.647163731819816e-13 (* z 2.8114572543455206e-15))
            p (+ 1.6059043836821613e-10 (* z p))
            p (+ -2.505210838544172e-8 (* z p))
            p (+ 2.7557319223985893e-6 (* z p))
            p (+ -0.0001984126984126984 (* z p))
            p (+ 0.008333333333333333 (* z p))
            p (+ -0.16666666666666666 (* z p))]
        (+ value (* (* value z) p))))))

(defn- f64-cos-quarter-turn [value]
  (let [value (checked-quarter-turn value)
        z (* value value)
        p (+ -1.1470745597729725e-11 (* z 4.779477332387385e-14))
        p (+ 2.08767569878681e-9 (* z p))
        p (+ -2.755731922398589e-7 (* z p))
        p (+ 0.0000248015873015873 (* z p))
        p (+ -0.001388888888888889 (* z p))
        p (+ 0.041666666666666664 (* z p))
        p (+ -0.5 (* z p))]
    (+ 1.0 (* z p))))

(def ^:private bounded-angle-limit 25735.927018207585)

(defn- reduce-bounded-angle [value]
  (when-not (and #?(:clj (Double/isFinite ^double value) :cljs (js/Number.isFinite value))
                 (<= (#?(:clj Math/abs :cljs js/Math.abs) value) bounded-angle-limit))
    (trap! :f64-bounded-angle-domain {}))
  (let [scaled (* value 0.6366197723675814)
        nearest (#?(:clj (fn [x] (if (neg? x) (Math/ceil (- x 0.5)) (Math/floor (+ x 0.5))))
                    :cljs (fn [x] (if (neg? x) (js/Math.ceil (- x 0.5)) (js/Math.floor (+ x 0.5)))))
                 scaled)
        reduced (- (- value (* nearest 1.5707963267948966))
                   (* nearest 6.123233995736766e-17))
        quadrant (mod #?(:clj (long nearest) :cljs nearest) 4)]
    [reduced quadrant]))

(defn- f64-sin-bounded [value]
  (let [[reduced quadrant] (reduce-bounded-angle value)]
    (case quadrant
      0 (f64-sin-quarter-turn reduced)
      1 (f64-cos-quarter-turn reduced)
      2 (- (f64-sin-quarter-turn reduced))
      (- (f64-cos-quarter-turn reduced)))))

(defn- f64-cos-bounded [value]
  (let [[reduced quadrant] (reduce-bounded-angle value)]
    (case quadrant
      0 (f64-cos-quarter-turn reduced)
      1 (- (f64-sin-quarter-turn reduced))
      2 (- (f64-cos-quarter-turn reduced))
      (f64-sin-quarter-turn reduced))))

(defn- f64-exp-near-zero [value]
  (when-not (and #?(:clj (Double/isFinite ^double value) :cljs (js/Number.isFinite value))
                 (<= (#?(:clj Math/abs :cljs js/Math.abs) value) 0.5))
    (trap! :f64-exp-near-zero-domain {}))
  (let [p (+ 2.8114572543455206e-15 (* value 1.5619206968586225e-16))
        p (+ 4.779477332387385e-14 (* value p))
        p (+ 7.647163731819816e-13 (* value p))
        p (+ 1.1470745597729725e-11 (* value p))
        p (+ 1.6059043836821613e-10 (* value p))
        p (+ 2.08767569878681e-9 (* value p))
        p (+ 2.505210838544172e-8 (* value p))
        p (+ 2.755731922398589e-7 (* value p))
        p (+ 2.7557319223985893e-6 (* value p))
        p (+ 0.0000248015873015873 (* value p))
        p (+ 0.0001984126984126984 (* value p))
        p (+ 0.001388888888888889 (* value p))
        p (+ 0.008333333333333333 (* value p))
        p (+ 0.041666666666666664 (* value p))
        p (+ 0.16666666666666666 (* value p))
        p (+ 0.5 (* value p))
        p (+ 1.0 (* value p))]
    (+ 1.0 (* value p))))

(defn- f64-log-near-one [value]
  (when-not (and #?(:clj (Double/isFinite ^double value) :cljs (js/Number.isFinite value))
                 (<= 0.75 value 1.5))
    (trap! :f64-log-near-one-domain {}))
  (let [y (/ (- value 1.0) (+ value 1.0))
        z (* y y)
        p (+ 0.05263157894736842 (* z 0.047619047619047616))
        p (+ 0.058823529411764705 (* z p))
        p (+ 0.06666666666666667 (* z p))
        p (+ 0.07692307692307693 (* z p))
        p (+ 0.09090909090909091 (* z p))
        p (+ 0.1111111111111111 (* z p))
        p (+ 0.14285714285714285 (* z p))
        p (+ 0.2 (* z p))
        p (+ 0.3333333333333333 (* z p))
        p (+ 1.0 (* z p))]
    (* (* 2.0 y) p)))

(defn- f64-atan-unit [value]
  (let [near-zero? (<= value 0.4142135623730951)
        t (if near-zero? value (/ (- value 1.0) (+ value 1.0)))
        z (* t t)
        p (+ 0.02702702702702703 (* z -0.02564102564102564))
        p (+ -0.02857142857142857 (* z p))
        p (+ 0.030303030303030304 (* z p))
        p (+ -0.03225806451612903 (* z p))
        p (+ 0.034482758620689655 (* z p))
        p (+ -0.037037037037037035 (* z p))
        p (+ 0.04 (* z p))
        p (+ -0.043478260869565216 (* z p))
        p (+ 0.047619047619047616 (* z p))
        p (+ -0.05263157894736842 (* z p))
        p (+ 0.058823529411764705 (* z p))
        p (+ -0.06666666666666667 (* z p))
        p (+ 0.07692307692307693 (* z p))
        p (+ -0.09090909090909091 (* z p))
        p (+ 0.1111111111111111 (* z p))
        p (+ -0.14285714285714285 (* z p))
        p (+ 0.2 (* z p))
        p (+ -0.3333333333333333 (* z p))
        p (+ 1.0 (* z p))
        angle (* t p)]
    (if near-zero? angle (+ 0.7853981633974483 angle))))

(defn- f64-atan2-bounded [y x]
  (when-not (and #?(:clj (Double/isFinite ^double y) :cljs (js/Number.isFinite y))
                 #?(:clj (Double/isFinite ^double x) :cljs (js/Number.isFinite x)))
    (trap! :f64-atan2-bounded-domain {}))
  (let [y-negative? (neg? (value/f64-to-i64-bits y))
        x-negative? (neg? (value/f64-to-i64-bits x))]
    (cond
      (zero? y) (if x-negative?
                  (if y-negative? -3.141592653589793 3.141592653589793)
                  y)
      (zero? x) (if y-negative? -1.5707963267948966 1.5707963267948966)
      :else (let [ay (#?(:clj Math/abs :cljs js/Math.abs) y)
                  ax (#?(:clj Math/abs :cljs js/Math.abs) x)
                  swap? (> ay ax)
                  ratio (if swap? (/ ax ay) (/ ay ax))
                  base (f64-atan-unit ratio)
                  angle (if swap? (- 1.5707963267948966 base) base)
                  angle (if x-negative? (- 3.141592653589793 angle) angle)]
              (if y-negative? (- angle) angle)))))

(def ^:private wide-exp-limit 354.891356446692)
(def ^:private wide-log-min 7.458340731200207e-155)
(def ^:private wide-log-max 1.3407807929942597e154)

(defn- binary-scale [exponent]
  (value/i64-bits-to-f64
   #?(:clj (* (+ (long exponent) 1023) 4503599627370496)
      :cljs (* (js/BigInt (+ exponent 1023)) (js/BigInt "4503599627370496")))))

(defn- f64-exp-bounded [value]
  (when-not (and #?(:clj (Double/isFinite ^double value) :cljs (js/Number.isFinite value))
                 (<= (#?(:clj Math/abs :cljs js/Math.abs) value) wide-exp-limit))
    (trap! :f64-exp-bounded-domain {}))
  (let [scaled (* value 1.4426950408889634)
        exponent (#?(:clj (fn [x] (long (if (neg? x) (Math/ceil (- x 0.5))
                                                    (Math/floor (+ x 0.5)))))
                     :cljs (fn [x] (if (neg? x) (js/Math.ceil (- x 0.5))
                                                   (js/Math.floor (+ x 0.5)))))
                  scaled)
        reduced (- (- value (* exponent 0.6931471805599453))
                   (* exponent 2.3190468138462996e-17))]
    (* (f64-exp-near-zero reduced) (binary-scale exponent))))

(defn- normalized-log-parts [input]
  #?(:clj
     (let [bits (value/f64-to-i64-bits input)
           exponent (- (quot bits 4503599627370496) 1023)
           mantissa (value/i64-bits-to-f64
                     (+ (bit-and bits 4503599627370495) 4607182418800017408))]
       (if (> mantissa 1.5) [(* mantissa 0.5) (inc exponent)] [mantissa exponent]))
     :cljs
     (let [bits (value/f64-to-i64-bits input)
           unit (js/BigInt "4503599627370496")
           field (/ bits unit)
           exponent (- (js/Number field) 1023)
           fraction (- bits (* field unit))
           mantissa (value/i64-bits-to-f64
                     (+ fraction (js/BigInt "4607182418800017408")))]
       (if (> mantissa 1.5) [(* mantissa 0.5) (inc exponent)] [mantissa exponent]))))

(defn- f64-log-bounded [input]
  (when-not (and #?(:clj (Double/isFinite ^double input) :cljs (js/Number.isFinite input))
                 (<= wide-log-min input wide-log-max))
    (trap! :f64-log-bounded-domain {}))
  (let [[mantissa exponent] (normalized-log-parts input)
        kernel (f64-log-near-one mantissa)]
    (+ (+ kernel (* exponent 0.6931471805599453))
       (* exponent 2.3190468138462996e-17))))

(defn- validate-runtime-value! [runtime-value type position]
  (if (and (vector? type) (= :ref (first type)) (= 2 (count type)))
    (if-let [descriptor (get *runtime-schemas* (second type))]
      (validate-runtime-value! runtime-value descriptor position)
      (trap! :unknown-schema-reference {:schema (second type) :position position}))
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

    :f64
    (when-not (value/f64-value? runtime-value)
      (trap! :value-type-mismatch {:expected :f64 :position position}))

    :f32
    (when-not (value/f32-value? runtime-value)
      (trap! :value-type-mismatch {:expected :f32 :position position}))

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

    :bool
    (when-not (boolean? runtime-value)
      (trap! :value-type-mismatch {:expected :bool :position position}))

    :option-i64
    (try
      (value/bounded-option-i64! runtime-value)
      (catch #?(:clj Exception :cljs :default) error
        (trap! :invalid-option-i64-value
               {:position position :message (ex-message error)})))

    :result-i64
    (try
      (value/bounded-result-i64! runtime-value)
      (catch #?(:clj Exception :cljs :default) error
        (trap! :invalid-result-i64-value
               {:position position :message (ex-message error)})))

    :vector-i64
    (try
      (value/bounded-vector-i64! runtime-value)
      (catch #?(:clj Exception :cljs :default) error
        (trap! :invalid-vector-i64-value
               {:position position :message (ex-message error)})))

    :vector-f64
    (try
      (value/bounded-vector-f64! runtime-value)
      (catch #?(:clj Exception :cljs :default) error
        (trap! :invalid-vector-f64-value
               {:position position :message (ex-message error)})))

    :string-index
    (try
      (value/bounded-string-index! runtime-value)
      (catch #?(:clj Exception :cljs :default) error
        (trap! :invalid-string-index-value
               {:position position :message (ex-message error)})))

    :disjoint-set-i64
    (try
      (value/bounded-disjoint-set-i64! runtime-value)
      (catch #?(:clj Exception :cljs :default) error
        (trap! :invalid-disjoint-set-i64-value
               {:position position :message (ex-message error)})))

    (try
      (value/bounded-typed-value! type runtime-value)
      (catch #?(:clj Exception :cljs :default) error
        (trap! :invalid-parametric-value
               {:type type :position position :message (ex-message error)})))))
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

(defn- i32-wrap [value]
  #?(:clj (long (unchecked-int (long value)))
     :cljs (js/BigInt.asIntN 32 (i64/->bigint value))))

(defn- u32-wrap [value]
  #?(:clj (bit-and (long value) 0xffffffff)
     :cljs (js/BigInt.asUintN 32 (i64/->bigint value))))

(defn- checked-shift32 [value]
  (when-not #?(:clj (and (integer? value) (<= 0 value 31))
               :cljs (and (i64/bigint-value? value)
                          (<= i64/zero value (js/BigInt 31))))
    (trap! :i32-shift-count-out-of-range {:count value}))
  #?(:clj (int value) :cljs (js/Number value)))

(defn- i32-add [x y]
  #?(:clj (long (unchecked-add-int (unchecked-int (long x)) (unchecked-int (long y))))
     :cljs (js/BigInt.asIntN 32 (+ (i32-wrap x) (i32-wrap y)))))

(defn- i32-mul [x y]
  #?(:clj (long (unchecked-multiply-int (unchecked-int (long x)) (unchecked-int (long y))))
     :cljs (js/BigInt.asIntN 32 (* (i32-wrap x) (i32-wrap y)))))

(defn- i32-xor [x y]
  #?(:clj (long (bit-xor (unchecked-int (long x)) (unchecked-int (long y))))
     :cljs (js/BigInt.asIntN 32 (bit-xor (i32-wrap x) (i32-wrap y)))))

(defn- i32-shl [value count]
  (let [shift (checked-shift32 count)]
    #?(:clj (i32-wrap (bit-shift-left (unchecked-int (long value)) shift))
       :cljs (js/BigInt.asIntN
              32 (* (i32-wrap value) (js/BigInt (js/Math.pow 2 shift)))))))

(defn- i32-shr [value count]
  (let [shift (checked-shift32 count)]
    #?(:clj (long (bit-shift-right (unchecked-int (long value)) shift))
       :cljs (i64/ashr (i32-wrap value) shift))))

(defn- u32-shr [value count]
  (let [shift (checked-shift32 count)]
    #?(:clj (u32-wrap (unsigned-bit-shift-right (u32-wrap value) shift))
       :cljs (/ (u32-wrap value) (js/BigInt (js/Math.pow 2 shift))))))

(defn- xorshift32 [value]
  (let [x (u32-wrap value)
        x (u32-wrap (bit-xor x #?(:clj (bit-shift-left x 13)
                                  :cljs (* x (js/BigInt 8192)))))
        x (u32-wrap (bit-xor x (u32-shr x #?(:clj 17 :cljs (js/BigInt 17)))))
        x (u32-wrap (bit-xor x #?(:clj (bit-shift-left x 5)
                                  :cljs (* x (js/BigInt 32)))))]
    x))

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

;; kgraph-* (ADR-2607198300): an all-integer EAVT datom store, the native
;; (JVM/Node/browser-free, `:x86_64-kotoba-v1`/`:aarch64-kotoba-v1`) analog of
;; kotoba-lang/kotoba's string/EDN-based kgraph-assert!/kgraph-query. There is
;; no addressable buffer in this native backend to carry EDN text (see
;; frontend.cljc/backend -- values here are i64 only), so entity/attribute/
;; value are caller-assigned integer ids rather than strings. Shares the
;; `heap` map's existing threading (a new `:datoms` key alongside `:cells`)
;; instead of adding a new interpreter parameter.
(defn- assert-datom! [heap e a v]
  (let [{:keys [datoms kgraph-capacity]} heap]
    (when (>= (count @datoms) kgraph-capacity)
      (trap! :kgraph-exhausted {:capacity kgraph-capacity}))
    (vswap! datoms conj [e a v])
    #?(:clj 1 :cljs i64/one)))

(defn- datom-value [datoms e a not-found]
  ;; Last-write-wins, matching kgraph-lang/kotoba's own kgraph-query
  ;; semantics for a point (entity, attribute) lookup.
  (reduce (fn [result [de da dv]]
            (if (and (= de e) (= da a)) dv result))
          not-found datoms))

(defn- get-datom [heap e a]
  (datom-value @(:datoms heap) e a #?(:clj Long/MIN_VALUE :cljs i64/min-i64)))

(defn- distinct-entities [datoms a]
  (->> datoms
       (filter (fn [[_ da _]] (= da a)))
       (map first)
       distinct
       vec))

(defn- count-entities [heap a]
  (let [n (count (distinct-entities @(:datoms heap) a))]
    #?(:clj (long n) :cljs (i64/->bigint n))))

(defn- entity-at [heap a index]
  #?(:clj
     (when-not (and (integer? index) (<= 0 index))
       (trap! :invalid-kgraph-index {:index index}))
     :cljs
     (when-not (and (i64/bigint-value? index) (not (i64/k-neg? index)))
       (trap! :invalid-kgraph-index {:index index})))
  (let [entities (distinct-entities @(:datoms heap) a)
        i #?(:clj index :cljs (js/Number index))]
    (when-not (< i (count entities))
      (trap! :invalid-kgraph-index {:index index}))
    (nth entities i)))

(defn- compact-host-index [index size code]
  (when-not (and #?(:clj (integer? index) :cljs (i64/bigint-value? index))
                 (not (neg? index)) (< index size))
    (trap! code {:index index :size size}))
  #?(:clj (int index) :cljs (js/Number index)))

(defn- disjoint-root [parents start]
  (loop [current start remaining (inc (count parents))]
    (when (zero? remaining)
      (trap! :invalid-disjoint-set-i64-value {:reason :parent-cycle}))
    (let [parent (compact-host-index (nth parents current) (count parents)
                                     :invalid-disjoint-set-i64-value)]
      (if (= parent current)
        current
        (recur parent (dec remaining))))))

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
    (value/f32-value? form) form
    (value/f64-value? form) form
    (keyword? form)
    (value/bounded-keyword! form value/keyword-value-byte-limit)
    (boolean? form) form
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
          (eval-expr (if (if (boolean? test-value)
                           (not test-value)
                           #?(:clj (zero? test-value) :cljs (i64/k-zero? test-value)))
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

        (= op 'typed-cap-call)
        (let [[cap-id request-type result-type request-form] args]
          (when-not cap-call
            (trap! :capability-denied {:capability cap-id :typed true}))
          (let [request (eval-expr request-form env functions fuel heap call-stack cap-call)]
            (validate-runtime-value! request request-type
                                     {:capability cap-id :boundary :request})
            (validate-runtime-value! (cap-call cap-id request-type result-type request)
                                     result-type
                                     {:capability cap-id :boundary :result})))

        (= op 'pair)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (allocate-pair! heap left right))

        (= op 'pair-first)
        (read-pair heap (eval-expr (first args) env functions fuel heap call-stack cap-call) 0)

        (= op 'pair-second)
        (read-pair heap (eval-expr (first args) env functions fuel heap call-stack cap-call) 1)

        (= op 'kgraph-assert!)
        (let [[e a v] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (assert-datom! heap e a v))

        (= op 'kgraph-get)
        (let [[e a] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (get-datom heap e a))

        (= op 'kgraph-count)
        (count-entities heap (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'kgraph-entity-at)
        (let [[a index] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (entity-at heap a index))

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

        (= op 'xml-path-count)
        (xml/path-count
         (eval-expr (first args) env functions fuel heap call-stack cap-call)
         (eval-expr (second args) env functions fuel heap call-stack cap-call))

        (= op 'xml-path-attr)
        (xml/path-attr
         (eval-expr (nth args 0) env functions fuel heap call-stack cap-call)
         (eval-expr (nth args 1) env functions fuel heap call-stack cap-call)
         (eval-expr (nth args 2) env functions fuel heap call-stack cap-call)
         (eval-expr (nth args 3) env functions fuel heap call-stack cap-call))

        (= op 'decimal-f64-parse)
        (decimal/parse-f64
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'decimal-f64x3-parse)
        (decimal/parse-f64x3
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-to-bits)
        (value/f64-to-i64-bits
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-from-bits)
        (value/i64-bits-to-f64
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'i64-to-f64-checked)
        (value/i64-to-f64-checked
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'i64-to-f64-rounded)
        (value/i64-to-f64-rounded
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-to-i64-checked)
        (value/f64-to-i64-checked
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-to-i64-truncating)
        (value/f64-to-i64-truncating
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (contains? '#{f64-add f64-sub f64-mul f64-div f64-min f64-max} op)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          ((case op f64-add + f64-sub - f64-mul * f64-div f64-divide
                 f64-min #?(:clj #(Math/min (double %1) (double %2)) :cljs js/Math.min)
                 f64-max #?(:clj #(Math/max (double %1) (double %2)) :cljs js/Math.max)) left right))

        (= op 'f64-neg)
        (- (double (eval-expr (first args) env functions fuel heap call-stack cap-call)))

        (= op 'f64-abs)
        (#?(:clj Math/abs :cljs js/Math.abs)
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-sqrt)
        (#?(:clj Math/sqrt :cljs js/Math.sqrt)
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-sin-quarter-turn)
        (f64-sin-quarter-turn
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-cos-quarter-turn)
        (f64-cos-quarter-turn
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-sin-bounded)
        (f64-sin-bounded
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-cos-bounded)
        (f64-cos-bounded
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-exp-near-zero)
        (f64-exp-near-zero
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-log-near-one)
        (f64-log-near-one
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-atan2-bounded)
        (let [[y x] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (f64-atan2-bounded y x))

        (= op 'f64-exp-bounded)
        (f64-exp-bounded
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-log-bounded)
        (f64-log-bounded
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (contains? '#{f64-eq f64-lt f64-le f64-gt f64-ge} op)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          ((case op f64-eq = f64-lt < f64-le <= f64-gt > f64-ge >=) left right))

        (= op 'f64-unordered)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (or #?(:clj (Double/isNaN ^double left) :cljs (js/Number.isNaN left))
              #?(:clj (Double/isNaN ^double right) :cljs (js/Number.isNaN right))))

        (= op 'f32-to-bits)
        (value/f32-to-i64-bits
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f32-from-bits)
        (value/i64-bits-to-f32
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f64-to-f32-rounded)
        (value/f64-to-f32-rounded
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f32-to-f64-exact)
        (value/f32-to-f64-exact
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'i64-to-f32-checked)
        (value/i64-to-f32-checked
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'i64-to-f32-rounded)
        (value/i64-to-f32-rounded
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f32-to-i64-checked)
        (value/f32-to-i64-checked
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'f32-to-i64-truncating)
        (value/f32-to-i64-truncating
         (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (contains? '#{f32-add f32-sub f32-mul f32-div f32-min f32-max} op)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (as-f32 ((case op f32-add + f32-sub - f32-mul * f32-div f32-divide
                         f32-min #?(:clj #(Math/min (.floatValue ^Number %1) (.floatValue ^Number %2)) :cljs js/Math.min)
                         f32-max #?(:clj #(Math/max (.floatValue ^Number %1) (.floatValue ^Number %2)) :cljs js/Math.max)) left right)))

        (= op 'f32-neg)
        (as-f32 (- (eval-expr (first args) env functions fuel heap call-stack cap-call)))

        (= op 'f32-abs)
        (as-f32 (#?(:clj Math/abs :cljs js/Math.abs)
                 (eval-expr (first args) env functions fuel heap call-stack cap-call)))

        (= op 'f32-sqrt)
        (as-f32 (#?(:clj Math/sqrt :cljs js/Math.sqrt)
                 (eval-expr (first args) env functions fuel heap call-stack cap-call)))

        (contains? '#{f32-eq f32-lt f32-le f32-gt f32-ge} op)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          ((case op f32-eq = f32-lt < f32-le <= f32-gt > f32-ge >=) left right))

        (= op 'f32-unordered)
        (let [[left right] (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (or #?(:clj (Float/isNaN ^float left) :cljs (js/Number.isNaN left))
              #?(:clj (Float/isNaN ^float right) :cljs (js/Number.isNaN right))))

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

        (= op 'bool-not)
        (not (eval-expr (first args) env functions fuel heap call-stack cap-call))

        (= op 'option-some)
        [true (eval-expr (first args) env functions fuel heap call-stack cap-call)]

        (= op 'option-none) [false]

        (= op 'option-some?)
        (let [option (value/bounded-option-i64!
                      (eval-expr (first args) env functions fuel heap call-stack cap-call))]
          (true? (first option)))

        (= op 'option-value)
        (let [[option-form fallback-form] args
              option (value/bounded-option-i64!
                      (eval-expr option-form env functions fuel heap call-stack cap-call))]
          (if (first option)
            (second option)
            (eval-expr fallback-form env functions fuel heap call-stack cap-call)))

        (= op 'result-ok)
        [true (eval-expr (first args) env functions fuel heap call-stack cap-call)]

        (= op 'result-err)
        [false (eval-expr (first args) env functions fuel heap call-stack cap-call)]

        (= op 'result-ok?)
        (let [result (value/bounded-result-i64!
                      (eval-expr (first args) env functions fuel heap call-stack cap-call))]
          (true? (first result)))

        (contains? '#{result-value result-error} op)
        (let [[result-form fallback-form] args
              result (value/bounded-result-i64!
                      (eval-expr result-form env functions fuel heap call-stack cap-call))
              selected? (if (= op 'result-value) (first result) (not (first result)))]
          (if selected?
            (second result)
            (eval-expr fallback-form env functions fuel heap call-stack cap-call)))

        (contains? '#{result-ok-of result-err-of} op)
        (let [[type payload-form] args
              tag (= op 'result-ok-of)]
          (value/bounded-typed-value!
           type [tag (eval-expr payload-form env functions fuel heap call-stack cap-call)]))

        (= op 'result-ok?-of)
        (let [[type result-form] args
              result (value/bounded-typed-value!
                      type (eval-expr result-form env functions fuel heap call-stack cap-call))]
          (true? (first result)))

        (contains? '#{result-value-of result-error-of} op)
        (let [[type result-form fallback-form] args
              result (value/bounded-typed-value!
                      type (eval-expr result-form env functions fuel heap call-stack cap-call))
              selected? (if (= op 'result-value-of) (first result) (not (first result)))
              payload-type (if (= op 'result-value-of) (second type) (nth type 2))]
          (if selected?
            (second result)
            (value/bounded-typed-value!
             payload-type
             (eval-expr fallback-form env functions fuel heap call-stack cap-call))))

        (= op 'result-match-of)
        (let [[type result-form ok-name ok-body err-name err-body] args
              result (value/bounded-typed-value!
                      type (eval-expr result-form env functions fuel heap call-stack cap-call))]
          (if (first result)
            (eval-expr ok-body (assoc env ok-name (second result))
                       functions fuel heap call-stack cap-call)
            (eval-expr err-body (assoc env err-name (second result))
                       functions fuel heap call-stack cap-call)))

        (= op 'variant-new)
        (let [[type tag payload-form] args]
          (value/bounded-typed-value!
           type [type tag (eval-expr payload-form env functions fuel heap call-stack cap-call)]))

        (= op 'variant-match)
        (let [[type value-form branches] args
              variant (value/bounded-typed-value!
                       type (eval-expr value-form env functions fuel heap call-stack cap-call))
              tag (second variant)
              [_ binder body] (some #(when (= tag (first %)) %) branches)]
          (when-not binder (trap! :unknown-variant-case {:tag tag}))
          (eval-expr body (assoc env binder (nth variant 2))
                     functions fuel heap call-stack cap-call))

        (= op 'option-some-of)
        (let [[type payload-form] args]
          (value/bounded-typed-value!
           type [type true (eval-expr payload-form env functions fuel heap call-stack cap-call)]))

        (= op 'option-none-of)
        (let [[type] args]
          (value/bounded-typed-value! type [type false]))

        (= op 'option-some?-of)
        (let [[type option-form] args
              option (value/bounded-typed-value!
                      type (eval-expr option-form env functions fuel heap call-stack cap-call))]
          (true? (second option)))

        (= op 'option-value-of)
        (let [[type option-form fallback-form] args
              option (value/bounded-typed-value!
                      type (eval-expr option-form env functions fuel heap call-stack cap-call))]
          (if (true? (second option))
            (nth option 2)
            (value/bounded-typed-value!
             (second type)
             (eval-expr fallback-form env functions fuel heap call-stack cap-call))))

        (= op 'option-match)
        (let [[type option-form none-body some-name some-body] args
              option (value/bounded-typed-value!
                      type (eval-expr option-form env functions fuel heap call-stack cap-call))]
          (if (true? (second option))
            (eval-expr some-body (assoc env some-name (nth option 2))
                       functions fuel heap call-stack cap-call)
            (eval-expr none-body env functions fuel heap call-stack cap-call)))

        (= op 'hetero-vector-new)
        (let [[type & item-forms] args
              items (mapv #(eval-expr % env functions fuel heap call-stack cap-call)
                          item-forms)]
          (value/bounded-typed-value! type (into [type] items)))

        (= op 'hetero-vector-count)
        (let [[type value-form] args
              items (value/bounded-typed-value!
                     type (eval-expr value-form env functions fuel heap call-stack cap-call))]
          #?(:clj (long (dec (count items)))
             :cljs (i64/->bigint (dec (count items)))))

        (= op 'hetero-vector-at)
        (let [[type value-form index] args
              items (value/bounded-typed-value!
                     type (eval-expr value-form env functions fuel heap call-stack cap-call))
              host-index #?(:clj (long index) :cljs (js/Number index))]
          (nth items (inc host-index)))

        (= op 'hetero-vector-assoc)
        (let [[type value-form index item-form] args
              items (value/bounded-typed-value!
                     type (eval-expr value-form env functions fuel heap call-stack cap-call))
              item (eval-expr item-form env functions fuel heap call-stack cap-call)
              host-index #?(:clj (long index) :cljs (js/Number index))]
          (value/bounded-typed-value! type (assoc items (inc host-index) item)))

        (= op 'hetero-vector-equal)
        (let [[type left-form right-form] args
              left (value/bounded-typed-value!
                    type (eval-expr left-form env functions fuel heap call-stack cap-call))
              right (value/bounded-typed-value!
                     type (eval-expr right-form env functions fuel heap call-stack cap-call))]
          #?(:clj (if (= left right) 1 0)
             :cljs (if (= left right) i64/one i64/zero)))

        (= op 'typed-set-new)
        (let [[type & item-forms] args
              items (mapv #(eval-expr % env functions fuel heap call-stack cap-call)
                          item-forms)]
          (value/bounded-typed-value! type [type items]))

        (= op 'typed-set-count)
        (let [[type value-form] args
              set-value (value/bounded-typed-value!
                         type (eval-expr value-form env functions fuel heap call-stack cap-call))]
          #?(:clj (long (count (second set-value)))
             :cljs (i64/->bigint (count (second set-value)))))

        (= op 'typed-set-contains)
        (let [[type value-form item-form] args
              set-value (value/bounded-typed-value!
                         type (eval-expr value-form env functions fuel heap call-stack cap-call))
              item (value/bounded-typed-value!
                    (second type)
                    (eval-expr item-form env functions fuel heap call-stack cap-call))]
          (boolean (some #(zero? (value/compare-typed-values (second type) % item))
                         (second set-value))))

        (= op 'typed-set-conj)
        (let [[type value-form item-form] args
              set-value (value/bounded-typed-value!
                         type (eval-expr value-form env functions fuel heap call-stack cap-call))
              item (value/bounded-typed-value!
                    (second type)
                    (eval-expr item-form env functions fuel heap call-stack cap-call))]
          (if (some #(zero? (value/compare-typed-values (second type) % item))
                    (second set-value))
            set-value
            (do (when (>= (count (second set-value)) value/typed-set-item-limit)
                  (trap! :set-too-large {:limit value/typed-set-item-limit}))
                (value/bounded-typed-value!
                 type [type (conj (second set-value) item)]))))

        (= op 'typed-set-disj)
        (let [[type value-form item-form] args
              set-value (value/bounded-typed-value!
                         type (eval-expr value-form env functions fuel heap call-stack cap-call))
              item (value/bounded-typed-value!
                    (second type)
                    (eval-expr item-form env functions fuel heap call-stack cap-call))]
          (value/bounded-typed-value!
           type [type (filterv #(not (zero? (value/compare-typed-values
                                             (second type) % item)))
                               (second set-value))]))

        (= op 'typed-set-equal)
        (let [[type left-form right-form] args
              left (value/bounded-typed-value!
                    type (eval-expr left-form env functions fuel heap call-stack cap-call))
              right (value/bounded-typed-value!
                     type (eval-expr right-form env functions fuel heap call-stack cap-call))]
          #?(:clj (if (= left right) 1 0)
             :cljs (if (= left right) i64/one i64/zero)))

        (= op 'typed-map-new)
        (let [[type & entry-forms] args
              evaluated (mapv #(eval-expr % env functions fuel heap call-stack cap-call)
                              entry-forms)
              entries (mapv vec (partition 2 evaluated))]
          (value/bounded-typed-value! type [type entries]))

        (= op 'typed-map-count)
        (let [[type value-form] args
              map-value (value/bounded-typed-value!
                         type (eval-expr value-form env functions fuel heap call-stack cap-call))]
          #?(:clj (long (count (second map-value)))
             :cljs (i64/->bigint (count (second map-value)))))

        (contains? '#{typed-map-contains typed-map-get typed-map-dissoc} op)
        (let [[type value-form key-form] args
              map-value (value/bounded-typed-value!
                         type (eval-expr value-form env functions fuel heap call-stack cap-call))
              key (value/bounded-typed-value!
                   (second type)
                   (eval-expr key-form env functions fuel heap call-stack cap-call))
              match (some (fn [[candidate item]]
                            (when (zero? (value/compare-typed-values
                                          (second type) candidate key))
                              [candidate item]))
                          (second map-value))]
          (case op
            typed-map-contains (boolean match)
            typed-map-get (let [option-type [:option (nth type 2)]]
                            (if match [option-type true (second match)]
                                [option-type false]))
            typed-map-dissoc
            (value/bounded-typed-value!
             type [type (filterv (fn [[candidate _]]
                                   (not (zero? (value/compare-typed-values
                                                (second type) candidate key))))
                                 (second map-value))])))

        (= op 'typed-map-entry-at)
        (let [[type value-form index-form] args
              map-value (value/bounded-typed-value!
                         type (eval-expr value-form env functions fuel heap call-stack cap-call))
              raw-index (eval-expr index-form env functions fuel heap call-stack cap-call)
              index #?(:clj (long raw-index) :cljs (js/Number raw-index))
              entry-type [:vector [(second type) (nth type 2)]]
              option-type [:option entry-type]]
          (if (or (neg? index) (>= index (count (second map-value))))
            [option-type false]
            (let [[key item] (nth (second map-value) index)]
              [option-type true [entry-type key item]])))

        (= op 'typed-map-assoc)
        (let [[type value-form key-form item-form] args
              map-value (value/bounded-typed-value!
                         type (eval-expr value-form env functions fuel heap call-stack cap-call))
              key (value/bounded-typed-value!
                   (second type) (eval-expr key-form env functions fuel heap call-stack cap-call))
              item (value/bounded-typed-value!
                    (nth type 2) (eval-expr item-form env functions fuel heap call-stack cap-call))
              remaining (filterv (fn [[candidate _]]
                                   (not (zero? (value/compare-typed-values
                                                (second type) candidate key))))
                                 (second map-value))]
          (when (and (= (count remaining) (count (second map-value)))
                     (>= (count remaining) value/typed-map-entry-limit))
            (trap! :map-too-large {:limit value/typed-map-entry-limit}))
          (value/bounded-typed-value! type [type (conj remaining [key item])]))

        (= op 'typed-map-equal)
        (let [[type left-form right-form] args
              left (value/bounded-typed-value!
                    type (eval-expr left-form env functions fuel heap call-stack cap-call))
              right (value/bounded-typed-value!
                     type (eval-expr right-form env functions fuel heap call-stack cap-call))]
          #?(:clj (if (= left right) 1 0)
             :cljs (if (= left right) i64/one i64/zero)))

        (= op 'record-new)
        (let [[type & value-forms] args
              values (mapv #(eval-expr % env functions fuel heap call-stack cap-call)
                           value-forms)]
          (value/bounded-typed-value! type (into [type] values)))

        (= op 'record-get)
        (let [[type value-form field] args
              record-value (value/bounded-typed-value!
                            type (eval-expr value-form env functions fuel heap call-stack cap-call))
              field-index (first (keep-indexed (fn [index [declared-field _]]
                                                 (when (= declared-field field) index))
                                               (nth type 2)))]
          (when (nil? field-index) (trap! :unknown-record-field {:field field}))
          (nth record-value (inc field-index)))

        (= op 'record-assoc)
        (let [[type value-form field replacement-form] args
              record-value (value/bounded-typed-value!
                            type (eval-expr value-form env functions fuel heap call-stack cap-call))
              replacement (eval-expr replacement-form env functions fuel heap call-stack cap-call)
              field-index (first (keep-indexed (fn [index [declared-field _]]
                                                 (when (= declared-field field) index))
                                               (nth type 2)))]
          (when (nil? field-index) (trap! :unknown-record-field {:field field}))
          (value/bounded-typed-value! type
                                      (assoc record-value (inc field-index) replacement)))

        (= op 'record-equal)
        (let [[type left-form right-form] args
              left (value/bounded-typed-value!
                    type (eval-expr left-form env functions fuel heap call-stack cap-call))
              right (value/bounded-typed-value!
                     type (eval-expr right-form env functions fuel heap call-stack cap-call))]
          #?(:clj (if (= left right) 1 0)
             :cljs (if (= left right) i64/one i64/zero)))

        (= op 'vector-new)
        (value/bounded-vector-i64!
         (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args))

        (= op 'vector-count)
        (let [items (value/bounded-vector-i64!
                     (eval-expr (first args) env functions fuel heap call-stack cap-call))]
          #?(:clj (long (count items)) :cljs (i64/->bigint (count items))))

        (= op 'vector-get)
        (let [[items-form index-form fallback-form] args
              items (value/bounded-vector-i64!
                     (eval-expr items-form env functions fuel heap call-stack cap-call))
              index (eval-expr index-form env functions fuel heap call-stack cap-call)]
          (if (and #?(:clj (integer? index) :cljs (i64/bigint-value? index))
                   (not (neg? index)) (< index (count items)))
            (nth items #?(:clj index :cljs (js/Number index)))
            (eval-expr fallback-form env functions fuel heap call-stack cap-call)))

        (= op 'vector-at)
        (let [[items-form index-form] args
              items (value/bounded-vector-i64!
                     (eval-expr items-form env functions fuel heap call-stack cap-call))
              index (eval-expr index-form env functions fuel heap call-stack cap-call)]
          (when-not (and (not (neg? index)) (< index (count items)))
            (trap! :vector-index-out-of-range {:index index}))
          (nth items #?(:clj index :cljs (js/Number index))))

        (= op 'vector-drop)
        (let [[items-form count-form] args
              items (value/bounded-vector-i64!
                     (eval-expr items-form env functions fuel heap call-stack cap-call))
              drop-count (eval-expr count-form env functions fuel heap call-stack cap-call)]
          (when-not (and (not (neg? drop-count)) (<= drop-count (count items)))
            (trap! :vector-drop-out-of-range {:count drop-count}))
          (value/bounded-vector-i64!
           (subvec items #?(:clj drop-count :cljs (js/Number drop-count)))))

        (= op 'vector-assoc)
        (let [[items-form index-form item-form] args
              items (value/bounded-vector-i64!
                     (eval-expr items-form env functions fuel heap call-stack cap-call))
              index (eval-expr index-form env functions fuel heap call-stack cap-call)
              item (eval-expr item-form env functions fuel heap call-stack cap-call)]
          (when-not (and (not (neg? index)) (< index (count items)))
            (trap! :vector-index-out-of-range {:index index}))
          (value/bounded-vector-i64!
           (assoc items #?(:clj index :cljs (js/Number index)) item)))

        (= op 'vector-conj)
        (let [[items-form item-form] args
              items (value/bounded-vector-i64!
                     (eval-expr items-form env functions fuel heap call-stack cap-call))
              item (eval-expr item-form env functions fuel heap call-stack cap-call)]
          (when (>= (count items) value/vector-item-limit)
            (trap! :vector-too-large {:limit value/vector-item-limit}))
          (value/bounded-vector-i64! (conj items item)))

        (= op 'vector-f64-new)
        (value/bounded-vector-f64!
         (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args))

        (= op 'vector-f64-count)
        (let [items (value/bounded-vector-f64!
                     (eval-expr (first args) env functions fuel heap call-stack cap-call))]
          #?(:clj (long (count items)) :cljs (i64/->bigint (count items))))

        (= op 'vector-f64-get)
        (let [[items-form index-form fallback-form] args
              items (value/bounded-vector-f64!
                     (eval-expr items-form env functions fuel heap call-stack cap-call))
              index (eval-expr index-form env functions fuel heap call-stack cap-call)]
          (if (and #?(:clj (integer? index) :cljs (i64/bigint-value? index))
                   (not (neg? index)) (< index (count items)))
            (nth items #?(:clj index :cljs (js/Number index)))
            (value/bounded-typed-value!
             :f64 (eval-expr fallback-form env functions fuel heap call-stack cap-call))))

        (= op 'vector-f64-at)
        (let [[items-form index-form] args
              items (value/bounded-vector-f64!
                     (eval-expr items-form env functions fuel heap call-stack cap-call))
              index (eval-expr index-form env functions fuel heap call-stack cap-call)]
          (when-not (and #?(:clj (integer? index) :cljs (i64/bigint-value? index))
                         (not (neg? index)) (< index (count items)))
            (trap! :vector-f64-index-out-of-range {:index index}))
          (nth items #?(:clj index :cljs (js/Number index))))

        (= op 'vector-f64-drop)
        (let [[items-form count-form] args
              items (value/bounded-vector-f64!
                     (eval-expr items-form env functions fuel heap call-stack cap-call))
              drop-count (eval-expr count-form env functions fuel heap call-stack cap-call)]
          (when-not (and #?(:clj (integer? drop-count) :cljs (i64/bigint-value? drop-count))
                         (not (neg? drop-count)) (<= drop-count (count items)))
            (trap! :vector-f64-drop-out-of-range {:count drop-count}))
          (value/bounded-vector-f64!
           (subvec items #?(:clj drop-count :cljs (js/Number drop-count)))))

        (= op 'vector-f64-assoc)
        (let [[items-form index-form item-form] args
              items (value/bounded-vector-f64!
                     (eval-expr items-form env functions fuel heap call-stack cap-call))
              index (eval-expr index-form env functions fuel heap call-stack cap-call)
              item (value/bounded-typed-value!
                    :f64 (eval-expr item-form env functions fuel heap call-stack cap-call))]
          (when-not (and #?(:clj (integer? index) :cljs (i64/bigint-value? index))
                         (not (neg? index)) (< index (count items)))
            (trap! :vector-f64-index-out-of-range {:index index}))
          (value/bounded-vector-f64!
           (assoc items #?(:clj index :cljs (js/Number index)) item)))

        (= op 'vector-f64-conj)
        (let [[items-form item-form] args
              items (value/bounded-vector-f64!
                     (eval-expr items-form env functions fuel heap call-stack cap-call))
              item (value/bounded-typed-value!
                    :f64 (eval-expr item-form env functions fuel heap call-stack cap-call))]
          (when (>= (count items) value/vector-item-limit)
            (trap! :vector-f64-too-large {:limit value/vector-item-limit}))
          (value/bounded-vector-f64! (conj items item)))

        (= op 'string-index-new) []

        (= op 'string-index-count)
        (let [index (value/bounded-string-index!
                     (eval-expr (first args) env functions fuel heap call-stack cap-call))]
          #?(:clj (long (count index)) :cljs (i64/->bigint (count index))))

        (contains? '#{string-index-contains string-index-get} op)
        (let [[index-form key-form] args
              index (value/bounded-string-index!
                     (eval-expr index-form env functions fuel heap call-stack cap-call))
              key (value/bounded-typed-value!
                   :string (eval-expr key-form env functions fuel heap call-stack cap-call))
              found (some (fn [[candidate item]] (when (= candidate key) item)) index)]
          (if (= op 'string-index-contains)
            (boolean (some? found))
            (if (some? found) [[:option :i64] true found] [[:option :i64] false])))

        (= op 'string-index-assoc)
        (let [[index-form key-form item-form] args
              index (value/bounded-string-index!
                     (eval-expr index-form env functions fuel heap call-stack cap-call))
              key (value/bounded-typed-value!
                   :string (eval-expr key-form env functions fuel heap call-stack cap-call))
              item (value/bounded-typed-value!
                    :i64 (eval-expr item-form env functions fuel heap call-stack cap-call))
              without-key (filterv #(not= key (first %)) index)]
          (when (and (= (count without-key) (count index))
                     (>= (count index) value/compact-graph-item-limit))
            (trap! :string-index-too-large {:limit value/compact-graph-item-limit}))
          (value/bounded-string-index! (vec (sort-by first (conj without-key [key item])))))

        (= op 'disjoint-set-i64-new)
        (let [size-value (eval-expr (first args) env functions fuel heap call-stack cap-call)]
          (when-not (and #?(:clj (integer? size-value) :cljs (i64/bigint-value? size-value))
                         (<= 0 size-value value/compact-graph-item-limit))
            (trap! :disjoint-set-i64-size-out-of-range
                   {:limit value/compact-graph-item-limit}))
          (let [size #?(:clj (int size-value) :cljs (js/Number size-value))
                parents #?(:clj (mapv long (range size))
                           :cljs (mapv i64/->bigint (range size)))
                ranks (vec (repeat size #?(:clj 0 :cljs i64/zero)))]
            (value/bounded-disjoint-set-i64! [parents ranks])))

        (= op 'disjoint-set-i64-count)
        (let [[parents _] (value/bounded-disjoint-set-i64!
                           (eval-expr (first args) env functions fuel heap call-stack cap-call))]
          #?(:clj (long (count parents)) :cljs (i64/->bigint (count parents))))

        (= op 'disjoint-set-i64-union)
        (let [[set-form left-form right-form] args
              [parents ranks :as disjoint-set]
              (value/bounded-disjoint-set-i64!
               (eval-expr set-form env functions fuel heap call-stack cap-call))
              left-index (compact-host-index
                          (eval-expr left-form env functions fuel heap call-stack cap-call)
                          (count parents) :disjoint-set-i64-index-out-of-range)
              right-index (compact-host-index
                           (eval-expr right-form env functions fuel heap call-stack cap-call)
                           (count parents) :disjoint-set-i64-index-out-of-range)
              left-root (disjoint-root parents left-index)
              right-root (disjoint-root parents right-index)
              option-type [:option :disjoint-set-i64]]
          (if (= left-root right-root)
            [option-type false]
            (let [left-rank (nth ranks left-root)
                  right-rank (nth ranks right-root)
                  [child root equal-rank?]
                  (cond (< left-rank right-rank) [left-root right-root false]
                        (> left-rank right-rank) [right-root left-root false]
                        :else [right-root left-root true])
                  new-parents (assoc parents child #?(:clj (long root) :cljs (i64/->bigint root)))
                  new-ranks (if equal-rank?
                              (assoc ranks root #?(:clj (inc (long left-rank))
                                                   :cljs (+ left-rank i64/one)))
                              ranks)]
              [option-type true
               (value/bounded-disjoint-set-i64! [new-parents new-ranks])])))

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

        (contains? '#{i32-wrap u32-wrap i32-wrapping-add i32-wrapping-mul i32-xor
                      i32-shift-left i32-shift-right u32-shift-right xorshift32} op)
        (let [xs (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (case op
            i32-wrap (i32-wrap (first xs))
            u32-wrap (u32-wrap (first xs))
            i32-wrapping-add (i32-add (first xs) (second xs))
            i32-wrapping-mul (i32-mul (first xs) (second xs))
            i32-xor (i32-xor (first xs) (second xs))
            i32-shift-left (i32-shl (first xs) (second xs))
            i32-shift-right (i32-shr (first xs) (second xs))
            u32-shift-right (u32-shr (first xs) (second xs))
            xorshift32 (xorshift32 (first xs))))

        :else
        (let [values (mapv #(eval-expr % env functions fuel heap call-stack cap-call) args)]
          (invoke-function (get functions op) values functions fuel heap call-stack cap-call))))))

(defn execute
  "Executes one KIR export using normative typed-value semantics. i64 math
  wraps modulo 2^64; bounded strings preserve Unicode text; invalid values,
  division, and resource exhaustion trap."
  ([kir function-name args] (execute kir function-name args {}))
  ([kir function-name args {:keys [fuel cap-call typed-cap-call pair-capacity kgraph-capacity]
                            :or {fuel default-fuel pair-capacity default-pair-capacity
                                 kgraph-capacity default-kgraph-capacity}}]
   (when (and (contains? kir :exports)
              (not (some #{function-name} (:exports kir))))
     (throw (ex-info "function is not exported" {:phase :ir :function function-name})))
   ;; fuel/pair-capacity/kgraph-capacity are interpreter-internal config,
   ;; never a `.kotoba` value -- plain `integer?` is correct for both
   ;; runtimes here.
   (when-not (and (integer? fuel) (pos? fuel))
     (throw (ex-info "fuel must be a positive integer" {:phase :ir :fuel fuel})))
   (when-not (and (integer? pair-capacity) (<= 0 pair-capacity default-pair-capacity))
     (throw (ex-info "pair capacity is outside runtime limits"
                     {:phase :ir :pair-capacity pair-capacity})))
   (when-not (and (integer? kgraph-capacity) (<= 0 kgraph-capacity default-kgraph-capacity))
     (throw (ex-info "kgraph capacity is outside runtime limits"
                     {:phase :ir :kgraph-capacity kgraph-capacity})))
   (let [cap-dispatch (when (or cap-call typed-cap-call)
                        (fn
                          ([cap-id value]
                           (if cap-call
                             (cap-call cap-id value)
                             (trap! :capability-denied {:capability cap-id})))
                          ([cap-id request-type result-type request]
                           (if typed-cap-call
                             (typed-cap-call cap-id request-type result-type request)
                             (trap! :capability-denied {:capability cap-id :typed true})))))
         functions (into {} (map (juxt :name identity) (:functions kir)))
         function (get functions function-name)
         param-types (or (:param-types function)
                         (vec (repeat (count (:params function)) :i64)))]
     (when-not (and (sequential? args) (= (count args) (count param-types)))
       (throw (ex-info "arguments do not match function arity" {:phase :ir :args args})))
     (doseq [[arg declared-type] (map vector args param-types)]
       (let [type (if (and (vector? declared-type) (= :ref (first declared-type)))
                    (or (get (:schemas kir) (second declared-type))
                        (throw (ex-info "argument references an unknown schema"
                                        {:phase :ir :schema (second declared-type)})))
                    declared-type)]
       (case type
         :i64 (when-not #?(:clj (and (integer? arg) (<= Long/MIN_VALUE arg Long/MAX_VALUE))
                          :cljs (and (or (i64/bigint-value? arg) (integer? arg))
                                     (i64/in-i64-range? arg)))
                (throw (ex-info "argument must be a signed i64" {:phase :ir :arg arg})))
         :string (value/bounded-string! arg value/string-value-byte-limit)
         :keyword (value/bounded-keyword! arg value/keyword-value-byte-limit)
         :map (value/bounded-map! arg)
         :bool (when-not (boolean? arg)
                 (throw (ex-info "argument must be a boolean" {:phase :ir :arg arg})))
         :option-i64 (value/bounded-option-i64! arg)
         :result-i64 (value/bounded-result-i64! arg)
         :vector-i64 (value/bounded-vector-i64! arg)
         :vector-f64 (value/bounded-vector-f64! arg)
         :string-index (value/bounded-string-index! arg)
         :disjoint-set-i64 (value/bounded-disjoint-set-i64! arg)
         (value/bounded-typed-value! type arg))))
     (let [invoke #(binding [*runtime-schemas* (:schemas kir)]
                     (invoke-function function
                                    (mapv (fn [arg type]
                                            (if (= type :i64)
                                              (#?(:clj long :cljs i64/->bigint) arg)
                                              arg))
                                          args param-types)
                                    functions
                                    (volatile! fuel) {:cells (volatile! []) :capacity pair-capacity
                                                      :datoms (volatile! []) :kgraph-capacity kgraph-capacity}
                                    [] cap-dispatch))]
       #?(:clj
          ;; A host JVM with a small native stack can exhaust that stack just
          ;; before the fixed Kotoba call budget does.  Host resource errors
          ;; must never escape the language boundary: normalize this one
          ;; precise failure to the same deterministic, fail-closed trap.
          (try
            (invoke)
            (catch StackOverflowError _
              (trap! :fuel-exhausted {:limit fuel :host-stack-exhausted true})))
          :cljs
          (invoke))))))

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
              :schemas (:schemas hir)
              :schema-identities (:schema-identities hir)
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
