(ns kotoba.compiler.frontend
  ;; `clojure.set` is required on both runtimes, so `:require` is never
  ;; empty -- an empty `(:require)` (what results if EVERY item inside it
  ;; were individually `#?()`-conditional and none matched) fails ns-form
  ;; spec validation (confirmed live; see `kotoba.compiler.ir`'s ns form
  ;; for the fuller explanation). `#?@` (splicing) rather than `#?` here
  ;; because each branch below is more than one require-spec.
  (:require [clojure.set :as set]
            #?@(:clj [[clojure.tools.reader :as reader]
                      [clojure.tools.reader.reader-types :as rt]]
                :cljs [[kotoba.compiler.kotoba-reader :as kr]
                       [kotoba.compiler.cljs-i64 :as i64]])))

(defn- load-catalog-forbidden
  "P0: merge catalog forbidden-heads when guest-grammar.edn is on classpath."
  []
  #?(:clj
     (try
       (let [c (or (clojure.java.io/resource "kotoba/lang/guest-grammar.edn")
                   (clojure.java.io/resource "lang/guest-grammar.edn"))]
         (if c
           (with-open [r (clojure.java.io/reader c)]
             (let [edn (clojure.edn/read (java.io.PushbackReader. r))
                   heads (:forbidden-heads edn #{})]
               (into #{} (map (fn [x] (if (symbol? x) x (symbol (name x))))) heads)))
           #{}))
       (catch Exception _ #{}))
     :cljs #{}))

(def forbidden-heads
  (into '#{eval load load-file require use import ns-resolve resolve alter-var-root
           future pmap agent send send-off new . .. set! defmacro throw try catch
           locking dosync atom ref volatile!}
        (load-catalog-forbidden)))

(def arithmetic '#{+ - * quot bit-xor bit-and})
(def comparisons '#{= < > <= >=})
(def heap-operations '{pair 2 pair-first 1 pair-second 1})
(def kernel-memory-operations
  '{kernel-load-u8 3 kernel-load-u8-4k 3 kernel-load-u8-16k 3
    kernel-store-u8 4 kernel-store-u8-4k 4})
(def kernel-privileged-operations
  '{kernel-boot-info 0 kernel-read-cr2 0 kernel-read-cr3 0 kernel-write-cr3 1 kernel-invlpg 1
    kernel-cli 0 kernel-sti 0 kernel-hlt 0 kernel-pause 0
    kernel-out-u8 2 kernel-out-u32 2})
(def list-operations '#{list cons first second rest empty?})
(def predicate-operations '#{not zero? pos? neg? string? symbol? keyword? string-length string=})
;; ADR-2607150000: and/or/when mirror kotoba-lang/kotoba's already-proven
;; desugar-and/desugar-or (runtime.clj) -- ported here rather than reinvented,
;; closing the divergence ADR-2607141600/2607150000 identified between the
;; two independently-evolved grammars. get/assoc are new: map literals and
;; keyword literals desugar entirely to the EXISTING heap-pair/list
;; primitives (see desugar-map/keyword->i64 below) -- no backend/codegen
;; change anywhere, since `pair`/`pair-first`/`pair-second` were already
;; host-imported capabilities before this change (backend/wasm.clj), not
;; WASM-linear-memory-managed by the guest itself.
(def logical-operations '#{and or when})
(def map-operations '#{get assoc contains-key?})
(def set-operations '#{contains? conj disj})
(def higher-order-operations '#{map filter reduce})
(def collection-operations '#{count nth peek pop keys vals dissoc})
(def closure-operations '#{invoke apply fn-ref})
(def lazy-sequence-operations '#{lazy-cons lazy-first lazy-rest lazy-empty? lazy-map lazy-filter take drop})
;; ADR-2607180900 L2: `do` is surface sugar that desugars to nested `let`
;; (discard non-final expressions by binding gensym temps). Admitted in
;; reserved-function-names so authors cannot define a conflicting `do`.
(def sequencing-operations '#{do})
(def reserved-function-names
  (set/union forbidden-heads arithmetic comparisons (set (keys heap-operations))
             (set (keys kernel-memory-operations))
             (set (keys kernel-privileged-operations))
             list-operations predicate-operations logical-operations map-operations set-operations
             higher-order-operations
             collection-operations
             closure-operations
             lazy-sequence-operations
             sequencing-operations
             '#{let if fn cap-call ns defn}))
(def max-functions 1024)
(def max-expression-nodes 50000)
(def max-lowered-nodes 100000)
(def max-bindings 4096)
(def max-parameters 5)
(def max-symbol-chars 128)
(def max-list-items 128)
(def max-set-items 16)

(defn- kotoba-integer?
  "True for a value that is (or stands for) a `.kotoba` integer literal --
  plain `integer?` on `:clj`. On `:cljs` a literal may be EITHER a JS
  `bigint` (read from `.kotoba` source via `kotoba.compiler.kotoba-reader`)
  OR a plain cljs number (synthesized directly by this namespace's own
  desugaring, e.g. `desugar-and`'s vacuous `1`, `when`'s trailing `0`, `get`'s
  default `0` -- ordinary Clojure literals in THIS file's source, never
  routed through the reader) -- both are valid until
  `kotoba.compiler.ir/eval-expr` coerces every literal to `bigint` via
  `cljs-i64/->bigint` at the single point it enters the runtime value
  stream. Plain cljs `integer?`/`int?` do not recognize `bigint`
  (confirmed live), so this checks both forms explicitly."
  [form]
  #?(:clj (integer? form) :cljs (or (i64/bigint-value? form) (integer? form))))

(defn- check-reader-depth! [source]
  (loop [index 0 depth 0 in-string? false escaped? false in-comment? false]
    (when (< index (count source))
      (let [ch (.charAt ^String source index)]
        (cond
          in-comment? (recur (inc index) depth false false (not= ch \newline))
          (and in-string? escaped?) (recur (inc index) depth true false false)
          (and in-string? (= ch \\)) (recur (inc index) depth true true false)
          (= ch \newline) (recur (inc index) depth in-string? false false)
          (= ch \;) (recur (inc index) depth in-string? false (not in-string?))
          (= ch \") (recur (inc index) depth (not in-string?) false false)
          in-string? (recur (inc index) depth true false false)
          (#{\( \[ \{} ch)
          (let [next-depth (inc depth)]
            (when (> next-depth 512)
              (throw (ex-info "reader nesting exceeds admission limit" {:phase :read})))
            (recur (inc index) next-depth false false false))
          (#{\) \] \}} ch) (recur (inc index) (max 0 (dec depth)) false false false)
          :else (recur (inc index) depth false false false))))))

(defn read-forms [source]
  (when-not (string? source)
    (throw (ex-info "source must be a string" {:phase :read})))
  (when (> (count source) (* 1024 1024))
    (throw (ex-info "source exceeds 1 MiB admission limit" {:phase :read})))
  (when (re-find #"#=" source)
    (throw (ex-info "reader evaluation is forbidden" {:phase :read})))
  (check-reader-depth! source)
  #?(:clj
     (let [r (rt/string-push-back-reader source)]
       (loop [out []]
         (when (> (count out) 10000)
           (throw (ex-info "too many top-level forms" {:phase :read})))
         (let [x (try
                   (reader/read {:read-cond :allow :features #{:kotoba} :eof ::eof} r)
                   (catch Exception error
                     (throw (ex-info "source reader rejected input"
                                     {:phase :read} error))))]
           (if (= x ::eof) out (recur (conj out x))))))
     ;; kotoba-reader (kr) is a purpose-built substitute for the JVM-only
     ;; clojure.tools.reader -- see its own ns docstring for why
     ;; cljs.tools.reader (the nominal ClojureScript sibling) isn't used
     ;; instead. It parses the whole source in one pass rather than one form
     ;; at a time, so the >10000 admission check runs post-hoc here instead
     ;; of per-iteration -- equivalent outcome, same 1 MiB source cap already
     ;; bounds how large that one pass can be.
     :cljs
     (let [out (try
                 (kr/read-forms source)
                 (catch :default error
                   (throw (ex-info "source reader rejected input"
                                   {:phase :read} error))))]
       (when (> (count out) 10000)
         (throw (ex-info "too many top-level forms" {:phase :read})))
       out)))

(defn- reject! [message form]
  (throw (ex-info message {:phase :subset :form form})))

(declare desugar-expr desugar-list form-free-symbols nth-pair-second
         replace-recur valid-name?)

;; ADR-2607150000: bound (via `binding`) around each top-level defn's
;; desugaring pass in `analyze`, to an atom `loop`'s desugar-expr case
;; conjoins synthesized helper-function definitions onto -- the same
;; "collect synthesized functions on the side, inject them into `parsed`
;; afterward" pattern `map-get-helper`/`uses-map-get?` already established,
;; generalized to support MULTIPLE, uniquely-named helpers per defn (one
;; per `loop` occurrence) rather than one fixed shared name.
(def ^:dynamic *pending-loop-helpers* nil)

;; ADR-2607150000: loop-helper names are NOT `gensym` -- unlike and/or's
;; gensym'd `let`-local temp names (erased into numeric WASM local indices,
;; never appearing in the compiled bytes -- confirmed by repeated-compile
;; byte comparison), a loop-helper becomes a real EXPORTED top-level
;; function ("every function is exported by current backends," see
;; analyze's :effects comment below), so its NAME is literally baked into
;; the WASM export section. `gensym` uses a JVM-process-wide monotonic
;; counter, so two compiles of the IDENTICAL source in the same process
;; would get DIFFERENT loop-helper names and therefore DIFFERENT bytes --
;; confirmed empirically (compiled the same loop-using source twice in one
;; process: bytes differed). This compiler's own reproducible-build gates
;; (coverage_evidence.clj/release.clj) require byte-for-byte determinism,
;; so loop-helpers instead get a sequential name from *loop-counter*,
;; bound ONCE per `analyze` call (not per-defn) so the same source always
;; encounters `loop` forms in the same left-to-right desugaring order and
;; always assigns the same names.
(def ^:dynamic *loop-counter* nil)
(def ^:dynamic *hof-counter* nil)
(def ^:dynamic *pending-hof-helpers* nil)
(def ^:dynamic *multi-arity-dispatch* {})
(def ^:dynamic *function-arities* {})
(def ^:dynamic *lambda-counter* nil)
(def ^:dynamic *pending-lambdas* nil)
(def ^:dynamic *uses-apply?* nil)
(def ^:dynamic *uses-lazy?* nil)
(def ^:dynamic *lifting-lazy-thunk?* false)

(defn- invoke-dispatcher-name [arity]
  (symbol (str "__kotoba_invoke$arity" arity)))

(defn- lift-lambda [form]
  (let [[_ params-or-clause & tail] form
        raw-clauses (if (vector? params-or-clause)
                      [[params-or-clause (first tail)]]
                      (mapv (fn [clause]
                              (when-not (and (seq? clause) (vector? (first clause))
                                             (= 2 (count clause)))
                                (reject! "multi-arity fn requires ([params] body) clauses" clause))
                              [(first clause) (second clause)])
                            (cons params-or-clause tail)))
        parsed (mapv (fn [[params body :as clause]]
                       (let [amp-index (first (keep-indexed #(when (= '& %2) %1) params))]
                         (if amp-index
                           (do
                             (when-not (and (= amp-index (- (count params) 2))
                                            (<= amp-index 4)
                                            (every? valid-name? (subvec params 0 amp-index))
                                            (valid-name? (peek params)))
                               (reject! "variadic fn clause requires [fixed ... & rest]" clause))
                             {:kind :variadic :fixed (subvec params 0 amp-index)
                              :rest-name (peek params) :body body :min-arity amp-index})
                           {:kind :fixed :params params :body body :arity (count params)})))
                     raw-clauses)
        fixed (filter #(= :fixed (:kind %)) parsed)
        variadics (filter #(= :variadic (:kind %)) parsed)
        _ (when (or (> (count variadics) 1)
                    (not= (count fixed) (count (distinct (map :arity fixed)))))
            (reject! "fn requires unique fixed arities and at most one variadic clause" form))
        fixed-by-arity (into {} (map (juxt :arity identity) fixed))
        variadic (first variadics)
        arities (sort (set/union (set (keys fixed-by-arity))
                                 (if variadic (set (range (:min-arity variadic) 5)) #{})))
        clauses (mapv (fn [arity]
                        (if-let [{:keys [params body]} (get fixed-by-arity arity)]
                          [params body]
                          (let [{:keys [fixed rest-name body]} variadic
                                extras (mapv #(symbol (str "__kotoba_lambda_rest_arg_" %))
                                             (range (- arity (count fixed))))]
                            [(into (vec fixed) extras)
                             (list 'let [rest-name (apply list 'list extras)] body)])))
                      arities)]
    (when-not (and (seq clauses)
                   (every? (fn [[params body]]
                             (and (some? body) (<= (count params) 4)
                                  (every? valid-name? params)
                                  (= (count params) (count (distinct params)))))
                           clauses)
                   (= (count clauses) (count (distinct (map (comp count first) clauses)))))
      (reject! "fn value requires unique arities with zero to four unique parameters" form))
    (when (and (vector? params-or-clause) (not= 1 (count tail)))
      (reject! "single-arity fn value requires exactly one body" form))
    (let [lowered (mapv (fn [[params body]]
                          {:params params :body (desugar-expr body)})
                        clauses)
          captures (vec (sort-by str
                                 (apply set/union #{}
                                        (map (fn [{:keys [params body]}]
                                               (form-free-symbols body (set params)))
                                             lowered))))
          id (vswap! *lambda-counter* inc)]
      (doseq [{:keys [params]} lowered]
        (when (> (+ (count captures) (count params)) max-parameters)
          (reject! "fn captures plus parameters exceed ABI-supported arity" form)))
      (when *pending-lambdas*
        (swap! *pending-lambdas* into
               (mapv (fn [{:keys [params body]}]
                       (let [arity (count params)
                             helper-name (symbol (str "__kotoba_lambda_" id "_arity" arity))]
                          {:id id :arity arity :captures captures
                          :helper {:name helper-name :params (into captures params)
                                   :result :i64 :effects #{} :body body
                                   :lazy-thunk? *lifting-lazy-thunk?*}}))
                     lowered)))
      (list 'pair id (desugar-list captures form)))))

(defn- lambda-dispatchers [lambda-infos force-all-arities?]
  (mapv
   (fn [arity]
     (let [closure (symbol (str "__kotoba_closure_" arity))
           args (mapv #(symbol (str "__kotoba_invoke_arg_" %)) (range arity))
           candidates (filter #(= arity (:arity %)) lambda-infos)
           body
           (reduce
            (fn [fallback {:keys [id captures helper]}]
              (let [capture-chain (list 'pair-second closure)
                    capture-values
                    (map-indexed (fn [index _]
                                   (list 'pair-first (nth-pair-second capture-chain index)))
                                 captures)]
                (list 'if (list '= (list 'pair-first closure) id)
                      (apply list (:name helper) (concat capture-values args))
                      fallback)))
            0 (reverse candidates))]
       {:name (invoke-dispatcher-name arity) :params (into [closure] args)
        :result :i64 :effects #{} :body body}))
   (if force-all-arities?
     (range 5)
     (sort (distinct (map :arity lambda-infos))))))

(def ^:private closure-apply-helper-name '__kotoba_closure_apply)

(def ^:private closure-apply-helper
  {:name closure-apply-helper-name
   :params '[__kotoba_apply_closure __kotoba_apply_args]
   :result :i64 :effects #{}
   :body
   '(if (= __kotoba_apply_args 0)
      (__kotoba_invoke$arity0 __kotoba_apply_closure)
      (let [__kotoba_apply_a0 (pair-first __kotoba_apply_args)
            __kotoba_apply_tail1 (pair-second __kotoba_apply_args)]
        (if (= __kotoba_apply_tail1 0)
          (__kotoba_invoke$arity1 __kotoba_apply_closure __kotoba_apply_a0)
          (let [__kotoba_apply_a1 (pair-first __kotoba_apply_tail1)
                __kotoba_apply_tail2 (pair-second __kotoba_apply_tail1)]
            (if (= __kotoba_apply_tail2 0)
              (__kotoba_invoke$arity2 __kotoba_apply_closure __kotoba_apply_a0 __kotoba_apply_a1)
              (let [__kotoba_apply_a2 (pair-first __kotoba_apply_tail2)
                    __kotoba_apply_tail3 (pair-second __kotoba_apply_tail2)]
                (if (= __kotoba_apply_tail3 0)
                  (__kotoba_invoke$arity3 __kotoba_apply_closure
                                           __kotoba_apply_a0 __kotoba_apply_a1 __kotoba_apply_a2)
                  (let [__kotoba_apply_a3 (pair-first __kotoba_apply_tail3)
                        __kotoba_apply_tail4 (pair-second __kotoba_apply_tail3)]
                    (if (= __kotoba_apply_tail4 0)
                      (__kotoba_invoke$arity4 __kotoba_apply_closure
                                               __kotoba_apply_a0 __kotoba_apply_a1
                                               __kotoba_apply_a2 __kotoba_apply_a3)
                      0)))))))))})

(def ^:private lazy-take-helper-name '__kotoba_lazy_take)
(def ^:private lazy-take-helper
  {:name lazy-take-helper-name :params '[n lazy-seq] :result :i64 :effects #{}
   :body '(if (<= n 0) 0
            (if (= lazy-seq 0) 0
              (let [cell (__kotoba_invoke$arity0 lazy-seq)]
                (if (= cell 0) 0
                  (pair (__kotoba_invoke$arity0 (pair-first cell))
                        (__kotoba_lazy_take
                         (- n 1)
                         (__kotoba_invoke$arity0 (pair-second cell))))))))})

(def ^:private lazy-drop-helper-name '__kotoba_lazy_drop)
(def ^:private lazy-drop-helper
  {:name lazy-drop-helper-name :params '[n lazy-seq] :result :i64 :effects #{}
   :body '(if (<= n 0) lazy-seq
            (if (= lazy-seq 0) 0
              (let [cell (__kotoba_invoke$arity0 lazy-seq)]
                (if (= cell 0) 0
                  (__kotoba_lazy_drop
                   (- n 1)
                   (__kotoba_invoke$arity0 (pair-second cell)))))))})

(defn- callback-value [callback arity]
  (cond
    (and (symbol? callback)
         (or (contains? *function-arities* callback)
             (contains? *multi-arity-dispatch* callback)))
    (let [params (mapv #(symbol (str "__kotoba_callback_arg_" %)) (range arity))]
      (lift-lambda (list 'fn params (apply list callback params))))

    (and (seq? callback) (= 'fn (first callback)))
    (lift-lambda callback)

    :else (desugar-expr callback)))

(defn- synthesize-lazy-map [callback colls]
  (let [helper-name (symbol (str "__kotoba_lazy_map_" (vswap! *hof-counter* inc)))
        callback-param (symbol (str helper-name "_callback"))
        coll-params (mapv #(symbol (str helper-name "_coll_" %)) (range (count colls)))
        callback-value (callback-value callback (count colls))
        body (binding [*lifting-lazy-thunk?* true]
               (lift-lambda
                (list 'fn []
                      (list 'if (apply list 'or (map #(list 'lazy-empty? %) coll-params)) 0
                            (list 'pair
                                  (list 'fn []
                                        (apply list 'invoke callback-param
                                               (map #(list 'lazy-first %) coll-params)))
                                  (list 'fn []
                                        (apply list helper-name callback-param
                                               (map #(list 'lazy-rest %) coll-params))))))))
        helper {:name helper-name :params (into [callback-param] coll-params)
                :result :i64 :effects #{} :body body}]
    (when *pending-hof-helpers* (swap! *pending-hof-helpers* conj helper))
    (apply list helper-name callback-value (map desugar-expr colls))))

(defn- synthesize-lazy-filter [callback coll]
  (let [helper-name (symbol (str "__kotoba_lazy_filter_" (vswap! *hof-counter* inc)))
        callback-param (symbol (str helper-name "_callback"))
        coll-param (symbol (str helper-name "_coll"))
        value (symbol (str helper-name "_value"))
        callback-value (callback-value callback 1)
        body (binding [*lifting-lazy-thunk?* true]
               (lift-lambda
                (list 'fn []
                      (list 'if (list 'lazy-empty? coll-param) 0
                            (list 'let [value (list 'lazy-first coll-param)]
                                  (list 'if (list 'invoke callback-param value)
                                        (list 'pair
                                              (list 'fn [] value)
                                              (list 'fn []
                                                    (list helper-name callback-param
                                                          (list 'lazy-rest coll-param))))
                                        (list 'invoke
                                              (list helper-name callback-param
                                                    (list 'lazy-rest coll-param)))))))))
        helper {:name helper-name :params [callback-param coll-param]
                :result :i64 :effects #{} :body body}]
    (when *pending-hof-helpers* (swap! *pending-hof-helpers* conj helper))
    (list helper-name callback-value (desugar-expr coll))))

(defn- synthesize-reduce-no-init [callback coll]
  (let [named-arities (and (symbol? callback) (get *multi-arity-dispatch* callback))]
    (cond
      named-arities
      (let [zero-name (get named-arities 0)
            binary-name (get named-arities 2)]
        (when-not (and zero-name binary-name)
          (reject! "named no-init reduce callback must define 0 and 2 arities" callback))
        (let [helper-name (symbol (str "__kotoba_hof_" (vswap! *hof-counter* inc)))
              acc (symbol (str helper-name "_acc"))
              items (symbol (str helper-name "_coll"))
              helper {:name helper-name :params [acc items] :result :i64 :effects #{}
                      :body (list 'if (list '= items 0) acc
                                  (list helper-name
                                        (list binary-name acc (list 'pair-first items))
                                        (list 'pair-second items)))}
              coll-sym (gensym "reduce-coll__")]
          (when *pending-hof-helpers* (swap! *pending-hof-helpers* conj helper))
          (list 'let [coll-sym (desugar-expr coll)]
                (list 'if (list '= coll-sym 0)
                      (list zero-name)
                      (list helper-name (list 'pair-first coll-sym)
                            (list 'pair-second coll-sym))))))

      (and (seq? callback) (= 'fn (first callback)))
        (let [clauses (rest callback)
              parsed (into {}
                           (map (fn [clause]
                                  (when-not (and (seq? clause) (vector? (first clause))
                                                 (= 2 (count clause))
                                                 (every? valid-name? (first clause))
                                                 (= (count (first clause))
                                                    (count (distinct (first clause)))))
                                    (reject! "invalid multi-arity fn clause in no-init reduce" clause))
                                  [(count (first clause))
                                   {:params (first clause) :body (desugar-expr (second clause))}]))
                           clauses)
              zero-clause (get parsed 0)
              binary-clause (get parsed 2)]
          (when-not (and (= (count parsed) (count clauses))
                         (= #{0 2} (set (keys parsed))) zero-clause binary-clause)
            (reject! "no-init reduce callback must define exactly [] and [acc value] arities" callback))
          (let [captures (vec (sort-by str
                                       (set/union
                                        (form-free-symbols (:body zero-clause) #{})
                                        (form-free-symbols (:body binary-clause)
                                                           (set (:params binary-clause))))))
                helper-name (symbol (str "__kotoba_hof_" (vswap! *hof-counter* inc)))
                acc (symbol (str helper-name "_acc"))
                items (symbol (str helper-name "_coll"))
                [acc-param value-param] (:params binary-clause)
                invoke-binary (list 'let [acc-param acc value-param (list 'pair-first items)]
                                    (:body binary-clause))
                helper {:name helper-name :params (into captures [acc items]) :result :i64 :effects #{}
                        :body (list 'if (list '= items 0) acc
                                    (apply list helper-name
                                           (concat captures
                                                   [invoke-binary (list 'pair-second items)])))}
                coll-sym (gensym "reduce-coll__")]
            (when (> (count (:params helper)) max-parameters)
              (reject! "callback captures plus reduce state exceed ABI-supported arity" callback))
            (when *pending-hof-helpers* (swap! *pending-hof-helpers* conj helper))
          (list 'let [coll-sym (desugar-expr coll)]
                (list 'if (list '= coll-sym 0)
                      (:body zero-clause)
                      (apply list helper-name
                             (concat captures
                                     [(list 'pair-first coll-sym)
                                      (list 'pair-second coll-sym)]))))))

      :else
      (let [helper-name (symbol (str "__kotoba_hof_" (vswap! *hof-counter* inc)))
            closure-param (symbol (str helper-name "_callback"))
            acc (symbol (str helper-name "_acc"))
            items (symbol (str helper-name "_coll"))
            helper {:name helper-name :params [closure-param acc items] :result :i64 :effects #{}
                    :body (list 'if (list '= items 0) acc
                                (list helper-name closure-param
                                      (list (invoke-dispatcher-name 2) closure-param acc
                                            (list 'pair-first items))
                                      (list 'pair-second items)))}
            closure-sym (gensym "reduce-callback__")
            coll-sym (gensym "reduce-coll__")]
        (when *pending-hof-helpers* (swap! *pending-hof-helpers* conj helper))
        (list 'let [closure-sym (desugar-expr callback)
                    coll-sym (desugar-expr coll)]
              (list 'if (list '= coll-sym 0)
                    (list (invoke-dispatcher-name 0) closure-sym)
                    (list helper-name closure-sym (list 'pair-first coll-sym)
                          (list 'pair-second coll-sym))))))))

(defn- synthesize-hof-call [kind callback colls init]
  (let [expected-arity (if (= kind :reduce) 2 (count colls))
        helper-name (symbol (str "__kotoba_hof_" (vswap! *hof-counter* inc)))
        callback-info
        (cond
          (and (symbol? callback)
               (or (contains? *function-arities* callback)
                   (contains? *multi-arity-dispatch* callback)))
          {:captures [] :initial-captures []
           :invoke (fn [args] (apply list callback args))}

          (and (seq? callback) (= 'fn (first callback)))
          (let [[_ params & body] callback]
            (when-not (and (vector? params) (= expected-arity (count params))
                           (every? valid-name? params)
                           (= (count params) (count (distinct params)))
                           (= 1 (count body)))
              (reject! (str (name kind) " fn callback has invalid parameters/body") callback))
            (let [desugared-body (desugar-expr (first body))
                  captures (vec (sort-by str (form-free-symbols desugared-body (set params))))]
              {:captures captures
               :initial-captures captures
               :invoke (fn [args] (list 'let (vec (mapcat vector params args)) desugared-body))}))

          :else
          (let [closure-param (symbol (str helper-name "_callback"))]
            {:captures [closure-param]
             :initial-captures [(desugar-expr callback)]
             :invoke (fn [args]
                       (apply list (invoke-dispatcher-name expected-arity)
                              closure-param args))}))
        captures (:captures callback-info)
        initial-captures (:initial-captures callback-info)
        invoke (:invoke callback-info)
        internal (fn [suffix]
                   (let [candidate (symbol (str helper-name "_" suffix))]
                     (when (contains? (set captures) candidate)
                       (reject! "closure capture collides with compiler helper local" candidate))
                     candidate))
        helper (case kind
                 :map (let [params (mapv #(internal (str "coll" %)) (range (count colls)))
                            step (list 'pair
                                       (invoke (map #(list 'pair-first %) params))
                                       (apply list helper-name
                                              (concat captures (map #(list 'pair-second %) params))))
                            body (reduce (fn [else coll]
                                           (list 'if (list '= coll 0) 0 else))
                                         step (reverse params))]
                        {:name helper-name :params (into captures params)
                         :result :i64 :effects #{} :body body})
                 :filter (let [coll (internal "coll")]
                           {:name helper-name :params (conj captures coll) :result :i64 :effects #{}
                            :body (list 'if (list '= coll 0) 0
                                        (list 'if (invoke [(list 'pair-first coll)])
                                              (list 'pair (list 'pair-first coll)
                                                    (apply list helper-name
                                                           (concat captures [(list 'pair-second coll)])))
                                              (apply list helper-name
                                                     (concat captures [(list 'pair-second coll)]))))})
                 :reduce (let [acc (internal "acc") coll (internal "coll")]
                           {:name helper-name :params (into captures [acc coll]) :result :i64 :effects #{}
                            :body (list 'if (list '= coll 0) acc
                                        (apply list helper-name
                                               (concat captures
                                                       [(invoke [acc (list 'pair-first coll)])
                                                        (list 'pair-second coll)])))}))]
    (when (> (count (:params helper)) max-parameters)
      (reject! "callback captures plus collection state exceed ABI-supported arity" callback))
    (when *pending-hof-helpers* (swap! *pending-hof-helpers* conj helper))
    (if (= kind :reduce)
      (apply list helper-name (concat initial-captures
                                      [(desugar-expr init) (desugar-expr (first colls))]))
      (apply list helper-name (concat initial-captures (map desugar-expr colls))))))

(defn- desugar-list [args form]
  (when (> (count args) max-list-items)
    (reject! "list item count exceeds admission limit" form))
  (reduce (fn [tail item] (list 'pair (desugar-expr item) tail))
          0 (reverse args)))

(defn- desugar-and
  "`(and a b c ...)` -> nested `let`/`if`, ported from kotoba-lang/kotoba's
  runtime.clj `desugar-and` (verified live there, ADR-2607150000): binds
  each argument's value once (never re-evaluated) and branches on it.
  `(and)` is vacuously truthy (1); `(and a)` is just a."
  [args]
  (cond
    (empty? args) 1
    (empty? (rest args)) (desugar-expr (first args))
    :else (let [tmp (gensym "and-tmp__")]
            (list 'let [tmp (desugar-expr (first args))]
                  (list 'if tmp (desugar-and (rest args)) tmp)))))

(defn- desugar-or
  "Mirror of desugar-and for `(or a b c ...)`. `(or)` is vacuously falsy
  (0); `(or a)` is just a."
  [args]
  (cond
    (empty? args) 0
    (empty? (rest args)) (desugar-expr (first args))
    :else (let [tmp (gensym "or-tmp__")]
            (list 'let [tmp (desugar-expr (first args))]
                  (list 'if tmp tmp (desugar-or (rest args)))))))

(defn- desugar-do
  "ADR-2607180900 L2: `(do a b c)` -> nested `let` that evaluates each
  non-final expression for effects (none in pure KIR today) and returns
  the last. No new backend op — pure desugar to `let`/`if` primitives.
  `(do)` is 0; `(do a)` is a."
  [args]
  (cond
    (empty? args) 0
    (empty? (rest args)) (desugar-expr (first args))
    :else (let [tmp (gensym "do-tmp__")]
            (list 'let [tmp (desugar-expr (first args))]
                  (desugar-do (rest args))))))

(defn- fnv1a-i64
  "Deterministic 64-bit FNV-1a hash of S's UTF-8 bytes, used to intern
  keyword literals as distinct i64 constants (ADR-2607150000). Not
  Clojure's own `hash` -- FNV-1a is a fixed, dependency-free algorithm
  whose output is reproducible forever, matching this compiler's byte-
  for-byte reproducibility gates (coverage_evidence.clj/release.clj).
  Collision probability for the realistically small keyword vocabulary of
  one .kotoba module is astronomically low but not proven zero -- a known,
  documented limitation, not eliminated."
  [s]
  #?(:clj
     (let [bs (.getBytes ^String s "UTF-8")
           offset-basis -3750763034362895579  ; 0xcbf29ce484222325 as signed i64
           prime 1099511628211]                ; 0x100000001b3
       (reduce (fn [h b] (unchecked-multiply (bit-xor h (bit-and (long b) 0xff)) prime))
               offset-basis bs))
     :cljs
     ;; Same FNV-1a algorithm, over JS bigint instead of JVM long, so a
     ;; keyword interned on this path hashes to the IDENTICAL i64 constant
     ;; the JVM path would emit for the same keyword (this compiler's own
     ;; reproducible-build gates require byte-for-byte identical output).
     (let [bs (js/TextEncoder.)
           bytes (.encode bs s)
           offset-basis (js/BigInt "-3750763034362895579")
           prime (js/BigInt "1099511628211")]
       (reduce (fn [h b]
                 (i64/wrap-i64 (* (i64/wrap-i64 (bit-xor h (js/BigInt b))) prime)))
               offset-basis (js/Array.from bytes)))))

(def ^:private value-tag-mask 0xf0000000)
(def ^:private string-value-tag 0x50000000)
(def ^:private keyword-value-tag 0x60000000)
(def ^:private symbol-value-tag 0x70000000)

(defn- fnv1a-i32-value [s]
  #?(:clj
     (let [bs (.getBytes ^String s "UTF-8")
           basis (unchecked-int 0x811c9dc5)
           prime (unchecked-int 0x01000193)]
       (reduce (fn [h b]
                 (unchecked-multiply-int
                  (unchecked-int (bit-xor h (bit-and (int b) 0xff))) prime))
               basis bs))
     :cljs
     (let [bytes (.encode (js/TextEncoder.) s)]
       (reduce (fn [h b]
                 (bit-or 0 (* (bit-xor h b) 0x01000193)))
               (bit-or 0 0x811c9dc5) (js/Array.from bytes)))))

(defn- tagged-value [tag text bits]
  (bit-or tag (bit-and (fnv1a-i32-value text) (dec (bit-shift-left 1 bits)))))

(defn- keyword->i64 [kw] (tagged-value keyword-value-tag (str kw) 28))

(defn- utf8-length [s]
  #?(:clj (count (.getBytes ^String s "UTF-8"))
     :cljs (.-length (.encode (js/TextEncoder.) s))))

(defn- string->i64 [s]
  (let [length (utf8-length s)]
    (when (> length 127)
      (reject! "portable string literal exceeds 127 UTF-8 bytes" s))
    (bit-or string-value-tag (bit-shift-left length 21)
            (bit-and (fnv1a-i32-value s) 0x1fffff))))

(defn- symbol-value->i64 [sym]
  (tagged-value symbol-value-tag (str sym) 28))

(defn- validate-portable-value-ids! [forms]
  (let [seen (atom {})]
    (letfn [(visit [node]
              (let [[kind source id]
                    (cond
                      (string? node) [:string node (string->i64 node)]
                      (keyword? node) [:keyword node (keyword->i64 node)]
                      (and (seq? node) (= 'quote (first node))
                           (= 2 (count node)) (symbol? (second node)))
                      [:symbol (second node) (symbol-value->i64 (second node))]
                      :else nil)]
                (when id
                  (if-let [[other-kind other-source] (get @seen id)]
                    (when-not (= [kind source] [other-kind other-source])
                      (reject! "portable value ID collision"
                               {:id id :left [other-kind other-source]
                                :right [kind source]}))
                    (swap! seen assoc id [kind source])))
                (cond
                  (map? node) (doseq [[k v] node] (visit k) (visit v))
                  (coll? node) (doseq [item node] (visit item)))))]
      (doseq [form forms] (visit form)))
    true))

(defn- desugar-map
  "`{:k1 v1 :k2 v2 ...}` -> a cons-list of `(pair key value)` pairs, reusing
  the EXISTING heap-pair/list primitives (`pair`/`pair-first`/`pair-second`)
  entirely -- no backend/codegen change (ADR-2607150000). Entries are
  sorted by the SOURCE TEXT of their key (`pr-str`, not the interned i64,
  which isn't computable for non-literal keys) for deterministic codegen
  regardless of Clojure's own map-literal iteration order (unspecified for
  >8 entries) -- required by this compiler's reproducible-build gates."
  [form]
  (when (> (count form) max-list-items)
    (reject! "map entry count exceeds admission limit" form))
  ;; #?(:cljs ...): a plain `pr-str` on a `bigint` key prints as
  ;; "#object[BigInt 5]" (confirmed live), not "5" like the JVM path's Long
  ;; -- diverging sort order between an integer key and e.g. a keyword key
  ;; in the same map literal. `.toString()` on bigint gives the identical
  ;; plain-digit form Long's own `pr-str` produces, so entries sort
  ;; byte-identically to the JVM path regardless of key type mix.
  (let [entries (sort-by (fn [[k _]]
                            #?(:clj (pr-str k)
                               :cljs (if (i64/bigint-value? k) (.toString k) (pr-str k))))
                          (seq form))
        pairs (map (fn [[k v]] (list 'pair (desugar-expr k) (desugar-expr v))) entries)]
    (reduce (fn [tail item] (list 'pair item tail)) 0 (reverse pairs))))

(def ^:private map-get-helper-name '__kotoba_map_get)

(def ^:private map-get-helper
  "Compiler-synthesized recursive linear scan over a desugar-map cons-list,
  injected into a module's function set only when `get` is actually used
  (analyze's uses-map-get? scan) -- ADR-2607150000. Written directly in
  already-primitive form (if/=/pair-first/pair-second/self-call), not run
  through desugar-expr. Each recursive step costs 1 unit of this
  compiler's existing fixed 256-instruction-call fuel budget (ir.clj/
  backend/wasm.clj/core.clj's `default-fuel`/global fuel counter) -- a
  map lookup on a long map, or a miss, can exhaust it; not a new limit,
  the existing one now also bounds map-walk depth."
  {:name map-get-helper-name
   :params '[m k default]
   :result :i64
   :effects #{}
   :body '(if (= m 0)
            default
            (if (= (pair-first (pair-first m)) k)
              (pair-second (pair-first m))
              (__kotoba_map_get (pair-second m) k default)))})

(defn- uses-map-get? [form]
  (cond
    (seq? form) (or (= map-get-helper-name (first form)) (some uses-map-get? (rest form)))
    (coll? form) (some uses-map-get? form)
    :else false))

(def ^:private map-contains-key-helper-name '__kotoba_map_contains_key)
(def ^:private map-contains-key-helper
  {:name map-contains-key-helper-name
   :params '[m k]
   :result :i64
   :effects #{}
   :body '(if (= m 0)
            0
            (if (= (pair-first (pair-first m)) k)
              1
              (__kotoba_map_contains_key (pair-second m) k)))})

(def ^:private map-without-helper-name '__kotoba_map_without)

(def ^:private map-without-helper
  "Compiler-synthesized recursive filter over a desugar-map cons-list,
  removing every entry whose key equals `k` while preserving the relative
  order of the rest -- `assoc`'s desugar (below) calls this on the OLD map
  before prepending the new pair, so re-`assoc`-ing an existing key no
  longer leaves the old entry as dead weight (a real bug found and fixed
  in the same ADR-2607150000 line of work that first landed assoc: the
  original desugar only ever prepended, `get`'s first-match-wins scan made
  the RESULT correct but the map grew without bound under repeated
  re-assoc of the same key). Injected only when `assoc` is actually used
  (`uses-map-without?`), same pattern as `map-get-helper`. Recurses through
  the WHOLE tail even after a match (not just the first hit) so any
  pre-existing duplicate from before this fix, or a hand-built map literal
  with a repeated key, is fully cleaned up, not just shadowed once more."
  {:name map-without-helper-name
   :params '[m k]
   :result :i64
   :effects #{}
   :body '(if (= m 0)
            0
            (if (= (pair-first (pair-first m)) k)
              (__kotoba_map_without (pair-second m) k)
              (pair (pair-first m) (__kotoba_map_without (pair-second m) k))))})

(defn- uses-map-without? [form]
  (cond
    (seq? form) (or (= map-without-helper-name (first form)) (some uses-map-without? (rest form)))
    (coll? form) (some uses-map-without? form)
    :else false))

(def ^:private set-contains-helper-name '__kotoba_set_contains)
(def ^:private set-contains-helper
  {:name set-contains-helper-name :params '[s value] :result :i64 :effects #{}
   :body '(if (= s 0) 0
            (if (= (pair-first s) value) 1
              (__kotoba_set_contains (pair-second s) value)))})

(def ^:private set-without-helper-name '__kotoba_set_without)
(def ^:private set-without-helper
  {:name set-without-helper-name :params '[s value] :result :i64 :effects #{}
   :body '(if (= s 0) 0
            (if (= (pair-first s) value)
              (__kotoba_set_without (pair-second s) value)
              (pair (pair-first s) (__kotoba_set_without (pair-second s) value))))})

(defn- uses-helper? [helper-name form]
  (cond
    (seq? form) (or (= helper-name (first form))
                    (some #(uses-helper? helper-name %) (rest form)))
    (coll? form) (some #(uses-helper? helper-name %) form)
    :else false))

(defn- stable-form-text [form]
  #?(:clj (pr-str form)
     :cljs (if (i64/bigint-value? form) (.toString form) (pr-str form))))

(defn- set-conj-form [set-expr value-expr]
  (let [value-sym (gensym "set-value__")]
    (list 'let [value-sym value-expr]
          (list 'pair value-sym
                (list set-without-helper-name set-expr value-sym)))))

(defn- desugar-set [form]
  (when (> (count form) max-set-items)
    (reject! "set item count exceeds admission limit" form))
  ;; EDN sets have no iteration-order contract. Sorting the source forms makes
  ;; lowering reproducible; set-conj-form also removes runtime-equal values.
  (reduce (fn [set-expr item]
            (set-conj-form set-expr (desugar-expr item)))
          0 (sort-by stable-form-text form)))

(def ^:private coll-count-helper-name '__kotoba_coll_count)
(def ^:private coll-count-helper
  {:name coll-count-helper-name :params '[coll acc] :result :i64 :effects #{}
   :body '(if (= coll 0) acc (__kotoba_coll_count (pair-second coll) (+ acc 1)))})

(def ^:private coll-nth-helper-name '__kotoba_coll_nth)
(def ^:private coll-nth-helper
  {:name coll-nth-helper-name :params '[coll index default] :result :i64 :effects #{}
   :body '(if (= coll 0) default
            (if (= index 0) (pair-first coll)
              (if (< index 0) default
                (__kotoba_coll_nth (pair-second coll) (- index 1) default))))})

(def ^:private map-keys-helper-name '__kotoba_map_keys)
(def ^:private map-keys-helper
  {:name map-keys-helper-name :params '[m] :result :i64 :effects #{}
   :body '(if (= m 0) 0
            (pair (pair-first (pair-first m)) (__kotoba_map_keys (pair-second m))))})

(def ^:private map-vals-helper-name '__kotoba_map_vals)
(def ^:private map-vals-helper
  {:name map-vals-helper-name :params '[m] :result :i64 :effects #{}
   :body '(if (= m 0) 0
            (pair (pair-second (pair-first m)) (__kotoba_map_vals (pair-second m))))})

(defn- nth-pair-second
  "N nested `pair-second`s around EXPR (0 => expr itself) -- the pair-chain
  position N steps past the head, used by both vector destructuring
  (below) and vector-as-data indexing."
  [expr n]
  (nth (iterate (fn [e] (list 'pair-second e)) expr) n))

(defn- destructure-binding
  "Expand one let/parameter binding pattern into sequential plain-symbol
  bindings. Collection values are first captured in a fresh temp, so nested
  vector/map patterns, defaults, and :as never re-evaluate their source.
  Supported map forms are :keys, :or, :as, and explicit keyword-to-pattern
  entries. :strs/:syms remain rejected because the guest value model has no
  portable symbol/string-key identity contract yet."
  [pattern value-expr]
  (letfn [(expand [p expr]
            (cond
              (symbol? p) [[p expr]]

              (vector? p)
              (let [amp-index (first (keep-indexed #(when (= '& %2) %1) p))
                    positional (if amp-index (subvec p 0 amp-index) p)
                    rest-part (when amp-index (subvec p amp-index))
                    _ (when (and rest-part (not= 2 (count rest-part)))
                        (reject! "`&` in vector destructuring must be followed by exactly one binding pattern" p))
                    tmp (gensym "destr-vec__")]
                (into [[tmp expr]]
                      (concat
                       (mapcat (fn [i child]
                                 (expand child (list 'pair-first (nth-pair-second tmp i))))
                               (range) positional)
                       (when rest-part
                         (expand (second rest-part)
                                 (nth-pair-second tmp (count positional)))))))

              (map? p)
              (let [keys-vec (:keys p)
                    strs-vec (:strs p)
                    syms-vec (:syms p)
                    defaults (or (:or p) {})
                    as-pattern (:as p)
                    explicit (dissoc p :keys :strs :syms :or :as)
                    _ (when-not (and (or (nil? keys-vec)
                                         (and (vector? keys-vec) (every? symbol? keys-vec)))
                                     (or (nil? strs-vec)
                                         (and (vector? strs-vec) (every? symbol? strs-vec)))
                                     (or (nil? syms-vec)
                                         (and (vector? syms-vec) (every? symbol? syms-vec)))
                                     (map? defaults)
                                     (or (nil? as-pattern) (symbol? as-pattern))
                                     (every? #(or (keyword? %) (string? %)
                                                  (and (seq? %) (= 'quote (first %))
                                                       (symbol? (second %))))
                                             (keys explicit)))
                        (reject! "invalid map destructuring pattern" p))
                    tmp (gensym "destr-map__")
                    lookup (fn [key default]
                             (list map-get-helper-name tmp (desugar-expr key)
                                   (desugar-expr default)))]
                (into [[tmp expr]]
                      (concat
                       (mapcat (fn [name]
                                 (expand name
                                         (lookup (keyword name) (get defaults name 0))))
                               keys-vec)
                       (mapcat (fn [name]
                                 (expand name
                                         (lookup (clojure.core/name name)
                                                 (get defaults name 0))))
                               strs-vec)
                       (mapcat (fn [name]
                                 (expand name
                                         (lookup (list 'quote name)
                                                 (get defaults name 0))))
                               syms-vec)
                       (mapcat (fn [[key child]]
                                 (expand child (lookup key 0)))
                               (sort-by (comp str key) explicit))
                       (when as-pattern [[as-pattern tmp]]))))

              :else (reject! "unsupported destructuring pattern" p)))]
    (expand pattern value-expr)))

(defn- form-free-symbols
  "Symbols FORM references as VALUES (never call-heads) that aren't in
  BOUND -- a purely syntactic free-variable scan for `loop`'s closure
  conversion (see desugar-expr's `loop` case). Operates on an ALREADY
  DESUGARED form, so every `let` it sees has plain-symbol bindings only
  (destructuring has already been expanded away by this point) -- no need
  to reason about vector/map patterns here."
  [form bound]
  (cond
    (symbol? form) (if (contains? bound form) #{} #{form})
    (seq? form)
    (let [[op & args] form]
      (if (= op 'let)
        (let [[bindings & body] args]
          (loop [pairs (partition 2 bindings) bound bound acc #{}]
            (if-let [[name value] (first pairs)]
              (recur (next pairs) (conj bound name) (set/union acc (form-free-symbols value bound)))
              (apply set/union acc (map #(form-free-symbols % bound) body)))))
        (apply set/union #{} (map #(form-free-symbols % bound) args))))
    (coll? form) (apply set/union #{} (map #(form-free-symbols % bound) form))
    :else #{}))

(defn- replace-recur
  "Walks the already-desugared loop BODY, replacing every `(recur a b ...)`
  with a call to HELPER-NAME carrying the new loop-binding values (A/B/...)
  followed by CAPTURED's outer-variable values UNCHANGED (they never vary
  across iterations, only the loop's own bindings do). A `recur` belonging
  to a NESTED loop is never seen here -- desugar-expr's `loop` case already
  resolved it (into an ordinary call to ITS OWN gensym'd helper) as part of
  desugaring this loop's body, before replace-recur ever runs."
  [form helper-name loop-names captured]
  (cond
    (seq? form)
    (let [[op & args] form]
      (if (= op 'recur)
        (do (when-not (= (count args) (count loop-names))
              (reject! "recur argument count must match loop bindings" form))
            (list* helper-name (concat args captured)))
        (cons op (map #(replace-recur % helper-name loop-names captured) args))))
    :else form))

(defn- desugar-expr [form]
  (cond
    (keyword? form) (keyword->i64 form)
    (string? form) (string->i64 form)
    (map? form) (desugar-map form)
    (set? form) (desugar-set form)
    ;; ADR-2607150000: vector-as-data reuses desugar-list's pair-chain
    ;; encoding verbatim -- a vector and a `(list ...)` call become
    ;; identical runtime representations, matching this language's
    ;; existing "no runtime type tags" design. Safe to dispatch generically
    ;; here because `let`'s OWN bindings vector never reaches this branch:
    ;; the `let` case below fully owns processing it directly (via
    ;; destructure-binding) and never routes it back through desugar-expr
    ;; as a bare value. `defn` params are consumed entirely inside
    ;; `analyze`, before any desugar-expr call, for the same reason.
    (vector? form) (desugar-list (seq form) form)
    (not (seq? form)) form
    :else
    (let [[op & args] form]
      (case op
        quote (let [value (first args)]
                (when-not (and (= 1 (count args)) (symbol? value))
                  (reject! "quote only supports portable symbol values" form))
                (symbol-value->i64 value))
        list (desugar-list args form)
        fn (lift-lambda form)
        fn-ref (do
                 (when-not (and (= 1 (count args)) (symbol? (first args)))
                   (reject! "fn-ref requires one top-level function symbol" form))
                 (let [function-name (first args)
                       arities (or (some-> (get *multi-arity-dispatch* function-name) keys set)
                                   (get *function-arities* function-name))]
                   (when-not (seq arities)
                     (reject! "fn-ref requires a declared top-level function" function-name))
                   (when (some #(> % 4) arities)
                     (reject! "fn-ref target exceeds closure ABI arity four" function-name))
                   (lift-lambda
                    (cons 'fn
                          (mapv (fn [arity]
                                  (let [params (mapv #(symbol (str "__kotoba_fn_ref_arg_" %))
                                                     (range arity))]
                                    (list params (apply list function-name params))))
                                (sort arities))))))
        invoke (do
                 (when-not (<= 1 (count args) (inc 4))
                   (reject! "invoke requires a closure and zero to four arguments" form))
                 (apply list (invoke-dispatcher-name (dec (count args)))
                        (map desugar-expr args)))
        apply (do
                (when-not (<= 2 (count args) 6)
                  (reject! "apply requires a closure, up to four fixed arguments, and a final argument collection" form))
                (when *uses-apply?* (vreset! *uses-apply?* true))
                (let [closure (first args)
                      call-args (rest args)
                      trailing (last call-args)
                      fixed (butlast call-args)
                      argument-list (reduce (fn [tail value]
                                              (list 'pair (desugar-expr value) tail))
                                            (desugar-expr trailing)
                                            (reverse fixed))]
                  (list closure-apply-helper-name (desugar-expr closure) argument-list)))
        lazy-cons (do
                    (when-not (= 2 (count args))
                      (reject! "lazy-cons requires a head and lazy tail expression" form))
                    (when *uses-lazy?* (vreset! *uses-lazy?* true))
                    (binding [*lifting-lazy-thunk?* true]
                      (lift-lambda
                       (list 'fn []
                                   (list 'pair
                                   (list 'fn [] (first args))
                                   (list 'fn [] (second args)))))))
        lazy-first (do
                     (when-not (= 1 (count args))
                       (reject! "lazy-first requires one lazy sequence" form))
                     (when *uses-lazy?* (vreset! *uses-lazy?* true))
                     (let [lazy-seq (gensym "lazy-seq__")
                           cell (gensym "lazy-cell__")]
                       (list 'let [lazy-seq (desugar-expr (first args))]
                             (list 'if (list '= lazy-seq 0) 0
                                   (list 'let [cell (list (invoke-dispatcher-name 0) lazy-seq)]
                                         (list 'if (list '= cell 0) 0
                                               (list (invoke-dispatcher-name 0)
                                                     (list 'pair-first cell))))))))
        lazy-rest (do
                    (when-not (= 1 (count args))
                      (reject! "lazy-rest requires one lazy sequence" form))
                    (when *uses-lazy?* (vreset! *uses-lazy?* true))
                    (let [lazy-seq (gensym "lazy-seq__")
                          cell (gensym "lazy-cell__")]
                      (list 'let [lazy-seq (desugar-expr (first args))]
                            (list 'if (list '= lazy-seq 0) 0
                                  (list 'let [cell (list (invoke-dispatcher-name 0) lazy-seq)]
                                        (list 'if (list '= cell 0) 0
                                              (list (invoke-dispatcher-name 0)
                                                    (list 'pair-second cell))))))))
        lazy-empty? (do
                      (when-not (= 1 (count args))
                        (reject! "lazy-empty? requires one lazy sequence" form))
                      (when *uses-lazy?* (vreset! *uses-lazy?* true))
                      (let [lazy-seq (gensym "lazy-seq__")]
                        (list 'let [lazy-seq (desugar-expr (first args))]
                              (list 'if (list '= lazy-seq 0) 1
                                    (list '= (list (invoke-dispatcher-name 0) lazy-seq) 0)))))
        lazy-map (do
                   (when-not (<= 2 (count args) 5)
                     (reject! "lazy-map requires a callback and one to four lazy sequences" form))
                   (when *uses-lazy?* (vreset! *uses-lazy?* true))
                   (synthesize-lazy-map (first args) (vec (rest args))))
        lazy-filter (do
                      (when-not (= 2 (count args))
                        (reject! "lazy-filter requires a unary predicate and lazy sequence" form))
                      (when *uses-lazy?* (vreset! *uses-lazy?* true))
                      (synthesize-lazy-filter (first args) (second args)))
        take (do
               (when-not (= 2 (count args))
                 (reject! "take requires a count and lazy sequence" form))
               (when *uses-lazy?* (vreset! *uses-lazy?* true))
               (list lazy-take-helper-name
                     (desugar-expr (first args)) (desugar-expr (second args))))
        drop (do
               (when-not (= 2 (count args))
                 (reject! "drop requires a count and lazy sequence" form))
               (when *uses-lazy?* (vreset! *uses-lazy?* true))
               (list lazy-drop-helper-name
                     (desugar-expr (first args)) (desugar-expr (second args))))

        ;; ADR-2607150000: `let` gets its own case (previously handled only
        ;; by the generic default case below) for two reasons: (1) bug fix
        ;; -- the default case's `(map desugar-expr args)` called
        ;; desugar-expr on the WHOLE bindings vector as one opaque arg;
        ;; since vectors aren't `seq?`, it passed through UNCHANGED,
        ;; silently skipping map/keyword/nested-vector desugaring inside
        ;; binding VALUES (`(let [m {:a 1}] (get m :a))` failed with
        ;; "value type is outside the safe profile" before this fix,
        ;; confirmed live). (2) destructuring: each binding's PATTERN may
        ;; now be a vector `[a b & rest]` or a map `{:keys [a b]}`, not
        ;; just a plain symbol (destructure-binding above expands either
        ;; into flat symbol bindings). Malformed bindings (not an even
        ;; vector) pass through unchanged so validate-expr's own existing
        ;; "let requires an even binding vector" check still fires with
        ;; its original, clearer error.
        let (let [[bindings & body] args]
              (list* 'let
                     (if (and (vector? bindings) (even? (count bindings)))
                       ;; destructure-binding returns a seq of [name value]
                       ;; pairs per pattern; mapcat over patterns yields a
                       ;; seq OF PAIRS (not yet flat), so a second `mapcat
                       ;; identity` is needed to splice each pair's two
                       ;; elements into the flat alternating binding vector
                       ;; `let` itself expects.
                       (vec (mapcat identity
                                    (mapcat (fn [[pattern value]]
                                              (destructure-binding pattern (desugar-expr value)))
                                            (partition 2 bindings))))
                       bindings)
                     ;; `mapv`, not bare `map`: `list*`'s tail argument is
                     ;; never forced by `list*` itself, so a lazy `map`
                     ;; result here would defer these desugar-expr calls
                     ;; past the end of whatever *loop-counter*/
                     ;; *pending-loop-helpers* `binding` this `let` case is
                     ;; nested inside -- confirmed live as an NPE ("Cannot
                     ;; invoke Volatile.deref() ... is null") when a `loop`
                     ;; inside a `let` body was first forced later, by
                     ;; `uses-map-get?`'s post-analyze tree walk, long
                     ;; after `analyze`'s `binding` had already exited.
                     (mapv desugar-expr body)))

        ;; ADR-2607150000: `loop`/`recur` desugars to a compiler-synthesized
        ;; recursive helper function (like `get`'s __kotoba_map_get, but
        ;; freshly gensym'd per loop occurrence rather than one shared fixed
        ;; name) -- no backend/codegen change, since ordinary recursive
        ;; `defn` calls already work and are already fuel-metered. Any free
        ;; variable the loop body references from its ENCLOSING scope is
        ;; captured as an EXTRA helper parameter (form-free-symbols does a
        ;; purely syntactic scan -- no environment lookup needed: an
        ;; over/under-capture mistake still fails SAFELY later, as a hard
        ;; :unbound-symbol or arity-mismatch compile error from validate-expr,
        ;; never silently wrong runtime behavior). Threading the captured
        ;; values through recur unchanged is handled by replace-recur.
        loop
        (let [[bindings & body] args]
          (when-not (and (vector? bindings) (even? (count bindings))
                         (every? symbol? (take-nth 2 bindings)))
            (reject! "loop requires an even vector of plain-symbol bindings (destructure inside the body instead)" form))
          (when-not (= 1 (count body))
            (reject! "loop requires exactly one body expression (this profile has no `do`)" form))
          (let [loop-names (vec (take-nth 2 bindings))
                loop-inits (mapv desugar-expr (take-nth 2 (rest bindings)))
                desugared-body (desugar-expr (first body))
                captured (vec (sort-by str (form-free-symbols desugared-body (set loop-names))))
                helper-name (symbol (str "__kotoba_loop_" (vswap! *loop-counter* inc)))
                helper-params (into loop-names captured)]
            (when (> (count helper-params) max-parameters)
              (reject! "loop bindings plus captured outer variables exceed this compiler's ABI-supported arity" form))
            (when *pending-loop-helpers*
              (swap! *pending-loop-helpers* conj
                     {:name helper-name :params helper-params :result :i64 :effects #{}
                      :body (replace-recur desugared-body helper-name loop-names captured)}))
            (list* helper-name (concat loop-inits captured))))
        cons (do (when-not (= 2 (count args)) (reject! "cons requires two operands" form))
                 (list 'pair (desugar-expr (first args)) (desugar-expr (second args))))
        first (do (when-not (= 1 (count args)) (reject! "first requires one operand" form))
                  (list 'pair-first (desugar-expr (first args))))
        second (do (when-not (= 1 (count args)) (reject! "second requires one operand" form))
                   (list 'pair-first (list 'pair-second (desugar-expr (first args)))))
        rest (do (when-not (= 1 (count args)) (reject! "rest requires one operand" form))
                 (list 'pair-second (desugar-expr (first args))))
        empty? (do (when-not (= 1 (count args)) (reject! "empty? requires one operand" form))
                   (list '= (desugar-expr (first args)) 0))
        not (do (when-not (= 1 (count args)) (reject! "not requires one operand" form))
                (list '= (desugar-expr (first args)) 0))
        zero? (do (when-not (= 1 (count args)) (reject! "zero? requires one operand" form))
                  (list '= (desugar-expr (first args)) 0))
        pos? (do (when-not (= 1 (count args)) (reject! "pos? requires one operand" form))
                 (list '> (desugar-expr (first args)) 0))
        neg? (do (when-not (= 1 (count args)) (reject! "neg? requires one operand" form))
                 (list '< (desugar-expr (first args)) 0))
        string? (do (when-not (= 1 (count args)) (reject! "string? requires one operand" form))
                    (list '= (list 'bit-and (desugar-expr (first args)) value-tag-mask)
                          string-value-tag))
        symbol? (do (when-not (= 1 (count args)) (reject! "symbol? requires one operand" form))
                    (list '= (list 'bit-and (desugar-expr (first args)) value-tag-mask)
                          symbol-value-tag))
        keyword? (do (when-not (= 1 (count args)) (reject! "keyword? requires one operand" form))
                     (list '= (list 'bit-and (desugar-expr (first args)) value-tag-mask)
                           keyword-value-tag))
        string-length (do
                        (when-not (= 1 (count args))
                          (reject! "string-length requires one operand" form))
                        (list 'bit-and (list 'quot (desugar-expr (first args)) 2097152) 127))
        string= (do (when-not (= 2 (count args)) (reject! "string= requires two operands" form))
                    (list '= (desugar-expr (first args)) (desugar-expr (second args))))
        and (desugar-and args)
        or (desugar-or args)
        do (desugar-do args)
        ;; Multi-body when is now admitted (ADR-2607180900): desugars via `do`.
        when (do (when (empty? args)
                   (reject! "when requires a test expression" form))
                 (let [[test & body] args]
                   (list 'if (desugar-expr test)
                         (desugar-do body)
                         0)))
        get (do (when-not (<= 2 (count args) 3)
                  (reject! "get requires a map, a key, and an optional default" form))
                (let [[m k default] args]
                  (list map-get-helper-name (desugar-expr m) (desugar-expr k)
                        (if (some? default) (desugar-expr default) 0))))
        contains-key? (do
                        (when-not (= 2 (count args))
                          (reject! "contains-key? requires a map and key" form))
                        (list map-contains-key-helper-name
                              (desugar-expr (first args))
                              (desugar-expr (second args))))
        assoc (do (when-not (and (>= (count args) 3) (odd? (count args)))
                    (reject! "assoc requires a map followed by one or more key/value pairs" form))
                  (let [[m & kvs] args]
                    (reduce (fn [acc-map [k v]]
                              ;; ADR-2607150000: remove any existing entry
                              ;; for this key via __kotoba_map_without
                              ;; BEFORE prepending the new pair, so re-
                              ;; assoc-ing an existing key doesn't grow the
                              ;; map unboundedly. k/v are let-bound once
                              ;; (gensym'd LET-LOCAL temp names -- safe,
                              ;; erased to WASM local indices, same
                              ;; reasoning as and/or's temps) so the key
                              ;; expression is evaluated exactly once
                              ;; despite being referenced twice (removal
                              ;; scan + the new pair itself).
                              (let [k-sym (gensym "assoc-k__")
                                    v-sym (gensym "assoc-v__")]
                                (list 'let [k-sym (desugar-expr k) v-sym (desugar-expr v)]
                                      (list 'pair (list 'pair k-sym v-sym)
                                            (list map-without-helper-name acc-map k-sym)))))
                            (desugar-expr m)
                            (partition 2 kvs))))
        contains? (do (when-not (= 2 (count args))
                        (reject! "contains? requires a set and one value" form))
                      (list set-contains-helper-name
                            (desugar-expr (first args))
                            (desugar-expr (second args))))
        conj (do (when (< (count args) 2)
                   (reject! "conj requires a set and at least one value" form))
                 (reduce (fn [set-expr value]
                           (set-conj-form set-expr (desugar-expr value)))
                         (desugar-expr (first args)) (rest args)))
        disj (do (when (empty? args)
                   (reject! "disj requires a set" form))
                 (reduce (fn [set-expr value]
                           (let [value-sym (gensym "set-value__")]
                             (list 'let [value-sym (desugar-expr value)]
                                   (list set-without-helper-name set-expr value-sym))))
                         (desugar-expr (first args)) (rest args)))
        map (do (when-not (<= 2 (count args) (inc max-parameters))
                  (reject! "map requires a named function and one to five collections" form))
                (synthesize-hof-call :map (first args) (vec (rest args)) nil))
        filter (do (when-not (= 2 (count args))
                     (reject! "filter requires a named unary predicate and one collection" form))
                   (synthesize-hof-call :filter (first args) [(second args)] nil))
        reduce (case (count args)
                 2 (synthesize-reduce-no-init (first args) (second args))
                 3 (synthesize-hof-call :reduce (first args) [(nth args 2)] (second args))
                 (reject! "reduce requires callback+collection or callback+init+collection" form))
        count (do (when-not (= 1 (count args))
                    (reject! "count requires one collection" form))
                  (list coll-count-helper-name (desugar-expr (first args)) 0))
        nth (do (when-not (<= 2 (count args) 3)
                 (reject! "nth requires collection, index, and optional default" form))
               (list coll-nth-helper-name
                     (desugar-expr (first args))
                     (desugar-expr (second args))
                     (if (= 3 (count args)) (desugar-expr (nth args 2)) 0)))
        peek (do (when-not (= 1 (count args)) (reject! "peek requires one collection" form))
                 (let [coll (gensym "peek-coll__")]
                   (list 'let [coll (desugar-expr (first args))]
                         (list 'if (list '= coll 0) 0 (list 'pair-first coll)))))
        pop (do (when-not (= 1 (count args)) (reject! "pop requires one collection" form))
                (let [coll (gensym "pop-coll__")]
                  (list 'let [coll (desugar-expr (first args))]
                        (list 'if (list '= coll 0) 0 (list 'pair-second coll)))))
        keys (do (when-not (= 1 (count args)) (reject! "keys requires one map" form))
                 (list map-keys-helper-name (desugar-expr (first args))))
        vals (do (when-not (= 1 (count args)) (reject! "vals requires one map" form))
                 (list map-vals-helper-name (desugar-expr (first args))))
        dissoc (do (when (empty? args) (reject! "dissoc requires a map" form))
                   (reduce (fn [m k]
                             (list map-without-helper-name m (desugar-expr k)))
                           (desugar-expr (first args)) (rest args)))
        (apply list op (map desugar-expr args))))))

(defn- valid-name? [value]
  (and (simple-symbol? value) (<= (count (name value)) max-symbol-chars)))

(defn- charge-node! [budget form]
  (when (> (vswap! budget inc) max-expression-nodes)
    (reject! "program expression budget exhausted" form)))

(declare validate-expr)

(defn- validate-bindings [bindings locals functions depth budget]
  (when-not (and (vector? bindings) (even? (count bindings)))
    (reject! "let requires an even binding vector" bindings))
  (when-not (= (count (take-nth 2 bindings)) (count (distinct (take-nth 2 bindings))))
    (reject! "duplicate let binding" bindings))
  (when (> (quot (count bindings) 2) max-bindings)
    (reject! "let binding count exceeds admission limit" bindings))
  (loop [pairs (partition 2 bindings) env locals]
    (if-let [[name value] (first pairs)]
      (do
        (when-not (and (valid-name? name) (not (contains? forbidden-heads name)))
          (reject! "invalid local binding" name))
        (validate-expr value env functions (inc depth) budget)
        (recur (next pairs) (conj env name)))
      env)))

(defn validate-expr [form locals functions depth budget]
  (charge-node! budget form)
  (when (> depth 256)
    (reject! "expression nesting exceeds admission limit" form))
  (cond
    (kotoba-integer? form)
    #?(:clj (if (<= Long/MIN_VALUE form Long/MAX_VALUE) form
                (reject! "integer literal is outside i64" form))
       :cljs (if (i64/in-i64-range? form) form
                 (reject! "integer literal is outside i64" form)))
    (symbol? form) (if (contains? locals form) form
                       (reject! "unbound or dynamic symbol is forbidden" form))
    (seq? form)
    (let [[op & args] form]
      (when-not (simple-symbol? op) (reject! "computed or namespaced calls are forbidden" form))
      (when (or (contains? forbidden-heads op) (re-find #"[.]" (name op)))
        (reject! "dynamic loading, interop, mutation, and metaprogramming are forbidden" form))
      (cond
        (= op 'let)
        (let [[bindings & body] args]
          (when-not (= 1 (count body)) (reject! "let requires one result expression" form))
          (validate-expr (first body)
                         (validate-bindings bindings locals functions depth budget)
                         functions (inc depth) budget))

        (= op 'if)
        (do (when-not (= 3 (count args)) (reject! "if requires test, then, else" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (= op 'cap-call)
        (let [[cap-id value :as call-args] args]
          (when-not (and (= 2 (count call-args)) (kotoba-integer? cap-id) (<= 0 cap-id 255))
            (reject! "cap-call requires a literal capability id in [0,255] and one value" form))
          (validate-expr value locals functions (inc depth) budget))

        (contains? arithmetic op)
            (do (when (or (empty? args) (and (contains? '#{quot bit-xor bit-and} op) (not= 2 (count args))))
              (reject! "invalid arithmetic arity" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? comparisons op)
        (do (when-not (= 2 (count args)) (reject! "comparison requires two operands" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? heap-operations op)
        (do (when-not (= (get heap-operations op) (count args))
              (reject! "heap operation arity mismatch" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? kernel-memory-operations op)
        (do (when-not (= (get kernel-memory-operations op) (count args))
              (reject! "kernel memory operation arity mismatch" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? kernel-privileged-operations op)
        (do (when-not (= (get kernel-privileged-operations op) (count args))
              (reject! "kernel privileged operation arity mismatch" form))
            (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        (contains? functions op)
        (let [expected (count (get functions op))]
          (when-not (= expected (count args))
            (reject! "function call arity mismatch" form))
          (doseq [arg args] (validate-expr arg locals functions (inc depth) budget)))

        :else (reject! "operation has no admitted lowering" form))
      form)
    :else (reject! "value type is outside the safe profile" form)))

(defn- direct-facts [form function-names]
  (let [effects (volatile! #{}) calls (volatile! #{})]
    (letfn [(walk [x]
              (cond
                (seq? x)
                (let [[op & args] x]
                  (cond
                    (= op 'cap-call)
                    (do (vswap! effects conj [:cap/call (first args)])
                        (walk (second args)))
                    (contains? function-names op)
                    (do (vswap! calls conj op) (doseq [arg args] (walk arg)))
                    :else (doseq [arg args] (walk arg))))
                (coll? x) (doseq [item x] (walk item))))]
      (walk form)
      {:effects @effects :calls @calls})))

(defn- infer-effects [functions]
  (let [names (set (map :name functions))
        direct (into {} (map (fn [{:keys [name body]}]
                               [name (direct-facts body names)])) functions)]
    (loop [inferred (into {} (map (fn [[name facts]] [name (:effects facts)]) direct))]
      (let [next-effects
            (into {} (map (fn [[name {direct-effects :effects calls :calls}]]
                            [name (reduce set/union direct-effects
                                          (map #(get inferred % #{}) calls))])
                          direct))]
        (if (= inferred next-effects) inferred (recur next-effects))))))

(defn- bounded-sum [values]
  (reduce (fn [total value]
            (min (inc max-lowered-nodes) (+ total value)))
          0 values))

(defn- lowered-cost [form env]
  (cond
    (kotoba-integer? form) 1
    (symbol? form) (get env form 1)
    :else
    (let [[op & args] form]
      (if (= op 'let)
        (let [[bindings body] args
              env' (reduce (fn [current [name value]]
                             (assoc current name (lowered-cost value current)))
                           env (partition 2 bindings))]
          (lowered-cost body env'))
        (bounded-sum (cons 1 (map #(lowered-cost % env) args)))))))

(defn- check-lowering-budget! [functions]
  (let [cost (bounded-sum (map #(lowered-cost (:body %) {}) functions))]
    (when (> cost max-lowered-nodes)
      (reject! "lowered program budget exhausted" cost))))

(defn- param-name+wrap
  "For one `defn` PARAM: a plain symbol is kept as-is (identity wrap). A
  vector/map destructuring pattern (ADR-2607150000) gets a fresh gensym'd
  parameter name, plus a body-wrapping fn that binds the pattern from that
  gensym via a `let` -- reusing desugar-expr's own `let`-destructuring
  (above) rather than duplicating it: `(defn f [{:keys [a]}] body)`
  becomes params `[tmp]`, body `(let [{:keys [a]} tmp] body)`, which then
  goes through desugar-expr exactly like any other `let`. Returns
  `[param-symbol wrap-fn]`."
  [param]
  (if (symbol? param)
    [param identity]
    (let [tmp (gensym "param-destr__")]
      [tmp (fn [body] (list 'let [param tmp] body))])))

(defn- protocol-form->info [form]
  (let [[_ protocol-name & methods] form]
    (when-not (and (valid-name? protocol-name) (seq methods)
                   (every? #(and (seq? %) (= 2 (count %))
                                 (valid-name? (first %))
                                 (vector? (second %))
                                 (<= 1 (count (second %)) max-parameters)
                                 (every? valid-name? (second %))) methods))
      (reject! "defprotocol requires method signatures of (name [this ...])" form))
    (let [method-map (into {} (map (fn [[name params]] [name params]) methods))]
      (when-not (= (count methods) (count method-map))
        (reject! "defprotocol method names must be unique" form))
      {:name protocol-name :methods method-map})))

(defn- record-form->info [protocols form]
  (let [[_ record-name fields & extra] form]
    (when-not (and (valid-name? record-name) (vector? fields)
                   (<= (count fields) max-parameters)
                   (every? valid-name? fields)
                   (= (count fields) (count (distinct fields))))
      (reject! "defrecord requires a bounded name and unique field vector" form))
    (let [ctor (symbol (str "->" record-name))
          map-ctor (symbol (str "map->" record-name))
          type-key :kotoba.record/type
          type-value (keyword (name record-name))
          record-map (into {type-key type-value}
                           (map (fn [field] [(keyword (name field)) field]) fields))
          groups (loop [remaining extra out []]
                   (if (empty? remaining)
                     out
                     (let [protocol-name (first remaining)
                           [method-forms tail] (split-with seq? (rest remaining))]
                       (when-not (and (symbol? protocol-name) (seq method-forms))
                         (reject! "defrecord protocol section requires a protocol name and methods" form))
                       (recur tail (conj out [protocol-name method-forms])))))
          implementations
          (mapcat
           (fn [[protocol-name method-forms]]
             (let [protocol (get protocols protocol-name)]
               (when-not protocol
                 (reject! "defrecord references an undeclared protocol" protocol-name))
               (map (fn [method-form]
                      (let [[method-name params & body] method-form
                            declared-params (get-in protocol [:methods method-name])]
                        (when-not (and declared-params (vector? params) (= 1 (count body))
                                       (= (count declared-params) (count params))
                                       (every? valid-name? params))
                          (reject! "record protocol method does not match its declaration" method-form))
                        {:protocol protocol-name :method method-name :record record-name
                         :params params :body (first body)}))
                    method-forms)))
           groups)]
      {:name record-name
       :defs [(list 'defn ctor fields record-map)
              (list 'defn map-ctor '[m] (list 'assoc 'm type-key type-value))]
       :implementations (vec implementations)})))

(defn- protocol-dispatch-defs [protocols implementations]
  (let [named-impls (mapv #(assoc % :impl-name (symbol (str "__kotoba_protocol_impl_" %2)))
                          implementations (range))
        impl-defs (mapv (fn [{:keys [impl-name params body]}]
                          (list 'defn impl-name params body)) named-impls)
        dispatch-defs
        (mapcat
         (fn [[protocol-name {:keys [methods]}]]
           (map (fn [[method-name params]]
                  (let [self (first params)
                        candidates (filter #(and (= protocol-name (:protocol %))
                                                 (= method-name (:method %))
                                                 (not= 'default (:record %))) named-impls)
                        default-impl (first (filter #(and (= protocol-name (:protocol %))
                                                          (= method-name (:method %))
                                                          (= 'default (:record %))) named-impls))
                        fallback (if default-impl
                                   (apply list (:impl-name default-impl) params)
                                   0)
                        body (reduce (fn [fallback {:keys [record impl-name]}]
                                       (list 'if
                                             (list '= (list 'get self :kotoba.record/type)
                                                   (keyword (name record)))
                                             (apply list impl-name params)
                                             fallback))
                                     fallback (reverse candidates))]
                    (list 'defn method-name params body)))
                (sort-by (comp str key) methods)))
         (sort-by (comp str key) protocols))]
    (concat impl-defs dispatch-defs)))

(defn- extension-methods->implementations [protocols protocol-name type-name method-forms whole-form]
  (let [protocol (get protocols protocol-name)]
    (when-not (and protocol (valid-name? type-name) (seq method-forms))
      (reject! "protocol extension requires declared protocol, type, and methods" whole-form))
    (mapv (fn [method-form]
            (let [[method-name params & body] method-form
                  declared-params (get-in protocol [:methods method-name])]
              (when-not (and declared-params (vector? params) (= 1 (count body))
                             (= (count declared-params) (count params))
                             (every? valid-name? params))
                (reject! "protocol extension method does not match its declaration" method-form))
              {:protocol protocol-name :method method-name :record type-name
               :params params :body (first body)}))
          method-forms)))

(defn- extend-type-form->implementations [protocols form]
  (let [[_ type-name protocol-name & method-forms] form]
    (extension-methods->implementations protocols protocol-name type-name method-forms form)))

(defn- extend-protocol-form->implementations [protocols form]
  (let [[_ protocol-name & sections] form]
    (loop [remaining sections out []]
      (if (empty? remaining)
        out
        (let [type-name (first remaining)
              [method-forms tail] (split-with seq? (rest remaining))]
          (when-not (symbol? type-name)
            (reject! "extend-protocol requires type sections" form))
          (recur tail (into out (extension-methods->implementations
                                 protocols protocol-name type-name method-forms form))))))))

(defn- multi-arity-defn? [form]
  (and (seq? form) (= 'defn (first form))
       (or (not (vector? (nth form 2 nil)))
           (some #{'&} (nth form 2 nil)))))

(defn- expand-multi-arity-defn [form]
  (let [[_ function-name & raw-tail] form
        clauses (if (vector? (first raw-tail))
                  (do (when-not (= 2 (count raw-tail))
                        (reject! "variadic defn requires one body" form))
                      [(list (first raw-tail) (second raw-tail))])
                  raw-tail)]
    (when-not (and (valid-name? function-name) (seq clauses)
                   (every? #(and (seq? %) (vector? (first %)) (= 2 (count %))) clauses))
      (reject! "multi-arity defn requires ([params] body) clauses" form))
    (let [parsed
          (mapv (fn [[params body :as clause]]
                  (let [amp-index (first (keep-indexed #(when (= '& %2) %1) params))]
                    (if amp-index
                      (do
                        (when-not (and (= amp-index (- (count params) 2))
                                       (every? valid-name? (subvec params 0 amp-index))
                                       (valid-name? (peek params)))
                          (reject! "variadic clause requires [fixed ... & rest]" clause))
                        {:kind :variadic :fixed (subvec params 0 amp-index)
                         :rest-name (peek params) :body body :min-arity amp-index})
                      (do
                        (when-not (and (<= (count params) max-parameters)
                                       (every? valid-name? params))
                          (reject! "fixed clause has invalid ABI parameters" clause))
                        {:kind :fixed :params params :body body :arity (count params)}))))
                clauses)
          fixed (filter #(= :fixed (:kind %)) parsed)
          variadics (filter #(= :variadic (:kind %)) parsed)
          _ (when (or (> (count variadics) 1)
                      (not= (count fixed) (count (distinct (map :arity fixed)))))
              (reject! "multi-arity defn requires unique fixed arities and at most one variadic clause" form))
          fixed-by-arity (into {} (map (juxt :arity identity) fixed))
          variadic (first variadics)
          supported-arities (set/union (set (keys fixed-by-arity))
                                       (if variadic
                                         (set (range (:min-arity variadic) (inc max-parameters)))
                                         #{}))
          expanded-name (fn [arity]
                          (if (and (= function-name 'main) (zero? arity))
                            'main
                            (symbol (str function-name "$arity" arity))))
          make-def
          (fn [arity]
            (if-let [{:keys [params body]} (get fixed-by-arity arity)]
              (list 'defn (expanded-name arity) params body)
              (let [{:keys [fixed rest-name body]} variadic
                    extra-count (- arity (count fixed))
                    extras (mapv #(symbol (str "__kotoba_rest_arg_" %)) (range extra-count))
                    _ (when (some (set (conj (vec fixed) rest-name)) extras)
                        (reject! "variadic parameters collide with compiler rest locals" form))
                    params (into (vec fixed) extras)]
                (list 'defn (expanded-name arity) params
                      (list 'let [rest-name (apply list 'list extras)] body)))))]
      (when (empty? supported-arities)
        (reject! "multi-arity defn has no supported arity" form))
      {:dispatch (into {} (map (fn [arity] [arity (expanded-name arity)]) supported-arities))
       :defs (mapv make-def (sort supported-arities))})))

(defn- rewrite-multi-arity-calls [dispatch form]
  (cond
    (seq? form)
    (let [[op & args] form
          rewritten-args (map #(rewrite-multi-arity-calls dispatch %) args)]
      (if-let [arities (and (symbol? op) (get dispatch op))]
        (if-let [target (get arities (count args))]
          (apply list target rewritten-args)
          (reject! "multi-arity function call has no matching arity" form))
        (apply list op rewritten-args)))
    (vector? form) (mapv #(rewrite-multi-arity-calls dispatch %) form)
    (map? form) (into {} (map (fn [[k v]] [(rewrite-multi-arity-calls dispatch k)
                                            (rewrite-multi-arity-calls dispatch v)]) form))
    (set? form) (set (map #(rewrite-multi-arity-calls dispatch %) form))
    :else form))

(def ^:private pure-desugar-heads
  '#{if do and or not = not= < > <= >= + - * quot rem mod
     pair pair-first pair-second get assoc contains? contains-key? conj disj
     count nth peek pop keys vals dissoc map filter reduce
     lazy-cons lazy-first lazy-rest lazy-empty? lazy-map lazy-filter take drop
     invoke apply fn-ref match})

(defn- replace-desugar-params [replacements node]
  (cond
    (symbol? node) (get replacements node node)
    (list? node) (apply list (map #(replace-desugar-params replacements %) node))
    (vector? node) (mapv #(replace-desugar-params replacements %) node)
    (map? node) (into {} (map (fn [[k v]] [(replace-desugar-params replacements k)
                                            (replace-desugar-params replacements v)]) node))
    (set? node) (set (map #(replace-desugar-params replacements %) node))
    :else node))

(defn- expand-pure-desugars [forms]
  (let [definitions
        (mapv (fn [[_ name params template :as form]]
                (when-not (and (= 4 (count form)) (valid-name? name) (vector? params)
                               (<= (count params) max-parameters)
                               (every? valid-name? params)
                               (= (count params) (count (distinct params))))
                  (reject! "defdesugar requires name, unique parameter vector, and template" form))
                [name {:params params :template template}])
              (filter #(and (seq? %) (= 'defdesugar (first %))) forms))
        registry (into {} definitions)
        _ (when-not (= (count definitions) (count registry))
            (reject! "duplicate defdesugar name" definitions))
        allowed-heads (into pure-desugar-heads (keys registry))
        validate-template
        (fn validate-template [params node]
          (cond
            (symbol? node)
            (when-not (contains? (set params) node)
              (reject! "defdesugar template contains a free value symbol" node))
            (seq? node)
            (do
              (when-not (contains? allowed-heads (first node))
                (reject! "defdesugar template call head is not registered pure" (first node)))
              (doseq [arg (rest node)] (validate-template params arg)))
            (coll? node) (doseq [item node] (validate-template params item))))
        _ (doseq [[_ {:keys [params template]}] registry]
            (validate-template params template))
        counter (volatile! 0)
        expansion-count (volatile! 0)]
    (letfn [(expand [form depth]
              (when (> depth 32)
                (reject! "defdesugar expansion depth exceeded" form))
              (cond
                (vector? form) (mapv #(expand % depth) form)
                (map? form) (into {} (map (fn [[k v]] [(expand k depth) (expand v depth)]) form))
                (set? form) (set (map #(expand % depth) form))
                (not (seq? form)) form
                :else
                (let [[op & args] form]
                  (if-let [{:keys [params template]} (get registry op)]
                    (do
                      (when-not (= (count params) (count args))
                        (reject! "defdesugar call arity mismatch" form))
                      (when (> (vswap! expansion-count inc) 256)
                        (reject! "defdesugar expansion count exceeded" form))
                      (let [temps (mapv (fn [_] (symbol (str "__kotoba_desugar_arg_"
                                                             (vswap! counter inc)))) params)
                            body (replace-desugar-params (zipmap params temps) template)]
                        (list 'let (vec (mapcat vector temps (map #(expand % depth) args)))
                              (expand body (inc depth)))))
                    (apply list op (map #(expand % depth) args))))))]
      (mapv #(expand % 0)
            (remove #(and (seq? %) (= 'defdesugar (first %))) forms)))))

(defn- expand-match-forms [forms]
  (let [counter (volatile! 0)]
    (letfn [(all-tests [tests] (reduce (fn [out test] (list 'and out test)) 1 tests))
            (pattern-plan [pattern value]
              (cond
                (= '_ pattern) {:test 1 :bindings []}
                (symbol? pattern) {:test 1 :bindings [pattern value]}
                (or (kotoba-integer? pattern) (keyword? pattern) (string? pattern))
                {:test (list '= value pattern) :bindings []}
                (vector? pattern)
                (let [amp (.indexOf pattern '&)
                      positional (if (neg? amp) pattern (subvec pattern 0 amp))
                      rest-pattern (when-not (neg? amp) (nth pattern (inc amp) nil))]
                  (when (and (not (neg? amp))
                             (or (not= amp (- (count pattern) 2))
                                 (not (valid-name? rest-pattern))))
                    (reject! "match vector & requires one trailing symbol" pattern))
                  (let [children (map-indexed
                                  (fn [index child]
                                    (pattern-plan child (list 'nth value index 0))) positional)
                        length-test (if (neg? amp)
                                      (list '= (list 'count value) (count positional))
                                      (list '>= (list 'count value) (count positional)))]
                    {:test (all-tests (cons length-test (map :test children)))
                     :bindings (vec (concat (mapcat :bindings children)
                                            (when rest-pattern
                                              [rest-pattern
                                               (reduce (fn [tail _] (list 'pop tail))
                                                       value positional)])))}))
                (map? pattern)
                (let [children (mapv (fn [[key child]]
                                       (when-not (keyword? key)
                                         (reject! "match map keys must be keywords" pattern))
                                       (let [entry (pattern-plan child (list 'get value key 0))]
                                         (update entry :test
                                                 #(list 'and (list 'contains-key? value key) %))))
                                     (sort-by (comp str key) pattern))]
                  {:test (all-tests (map :test children))
                   :bindings (vec (mapcat :bindings children))})
                :else (reject! "unsupported match pattern" pattern)))
            (expand [form]
              (cond
                (vector? form) (mapv expand form)
                (map? form) (into {} (map (fn [[k v]] [(expand k) (expand v)]) form))
                (set? form) (set (map expand form))
                (not (seq? form)) form
                :else
                (let [[op & args] form]
                  (if (= op 'match)
                    (let [[value & clauses] args]
                      (when-not (and value (even? (count clauses)) (seq clauses))
                        (reject! "match requires value and pattern/result pairs" form))
                      (let [temp (symbol (str "__kotoba_match_value_" (vswap! counter inc)))
                            branch (reduce
                                    (fn [otherwise [pattern result]]
                                      (if (= :else pattern)
                                        (expand result)
                                        (let [{:keys [test bindings]} (pattern-plan pattern temp)]
                                          (list 'if test
                                                (if (seq bindings)
                                                  (list 'let bindings (expand result))
                                                  (expand result))
                                                otherwise))))
                                    0 (reverse (partition 2 clauses)))]
                        (list 'let [temp (expand value)] branch)))
                    (apply list op (map expand args))))))]
      (mapv expand forms))))

(defn analyze [source]
  (let [source-forms (read-forms source)
        _ (validate-portable-value-ids! source-forms)
        forms (-> source-forms expand-pure-desugars expand-match-forms)
        namespaces (filter #(and (seq? %) (= 'ns (first %))) forms)
        protocol-forms (filter #(and (seq? %) (contains? '#{defprotocol definterface} (first %))) forms)
        protocol-infos (mapv protocol-form->info protocol-forms)
        protocols (into {} (map (juxt :name identity) protocol-infos))
        _ (when-not (= (count protocol-infos) (count protocols))
            (reject! "duplicate protocol name" protocol-forms))
        records (filter #(and (seq? %) (= 'defrecord (first %))) forms)
        record-infos (mapv (partial record-form->info protocols) records)
        _ (when-not (= (count record-infos) (count (distinct (map :name record-infos))))
            (reject! "duplicate record name" records))
        extend-type-forms (filter #(and (seq? %) (= 'extend-type (first %))) forms)
        extend-protocol-forms (filter #(and (seq? %) (= 'extend-protocol (first %))) forms)
        implementations (concat (mapcat :implementations record-infos)
                                (mapcat (partial extend-type-form->implementations protocols)
                                        extend-type-forms)
                                (mapcat (partial extend-protocol-form->implementations protocols)
                                        extend-protocol-forms))
        _ (when-not (= (count implementations)
                       (count (distinct (map (juxt :protocol :method :record) implementations))))
            (reject! "duplicate record protocol method implementation" records))
        raw-defs (filter #(and (seq? %) (= 'defn (first %))) forms)
        multi-expanded (mapv expand-multi-arity-defn (filter multi-arity-defn? raw-defs))
        multi-dispatch (into {}
                             (map (fn [form expansion] [(second form) (:dispatch expansion)])
                                  (filter multi-arity-defn? raw-defs) multi-expanded))
        _ (when-not (= (count multi-expanded) (count multi-dispatch))
            (reject! "duplicate multi-arity function name" raw-defs))
        defs (concat (remove multi-arity-defn? raw-defs)
                     (mapcat :defs multi-expanded)
                     (mapcat :defs record-infos)
                     (protocol-dispatch-defs protocols implementations))
        function-arities (reduce (fn [out [_ name params]]
                                   (update out name (fnil conj #{}) (count params)))
                                 {} defs)
        other (remove #(or (and (seq? %) (= 'ns (first %)))
                           (and (seq? %) (= 'defn (first %)))
                           (and (seq? %) (= 'defrecord (first %)))
                           (and (seq? %) (= 'defprotocol (first %)))
                           (and (seq? %) (= 'definterface (first %)))
                           (and (seq? %) (= 'extend-type (first %)))
                           (and (seq? %) (= 'extend-protocol (first %)))) forms)
        _ (when (> (count defs) max-functions)
            (reject! "function count exceeds admission limit" (count defs)))
        _ (when (or (> (count namespaces) 1)
                    (some #(not (and (= 2 (count %)) (symbol? (second %))
                                     (<= (count (str (second %))) max-symbol-chars)))
                          namespaces))
            (reject! "ns must contain exactly one namespace symbol" namespaces))
        ;; ADR-2607150000: mapcat, not mapv -- a defn using `loop` may
        ;; expand into itself PLUS one or more synthesized loop-helper
        ;; functions (collected via *pending-loop-helpers*, bound fresh
        ;; per defn so helpers from one function's loops never leak into
        ;; another's). defn PARAMS may now be destructuring patterns
        ;; (param-name+wrap above), not just plain symbols. *loop-counter*
        ;; is bound ONCE for the whole source (not per-defn) so loop-helper
        ;; names stay unique across every defn, not just within one.
        lambda-infos (atom [])
        uses-apply? (volatile! false)
        uses-lazy? (volatile! false)
        parsed (binding [*loop-counter* (volatile! 0)
                         *hof-counter* (volatile! 0)
                         *lambda-counter* (volatile! 0)
                         *pending-lambdas* lambda-infos
                         *uses-apply?* uses-apply?
                         *uses-lazy?* uses-lazy?
                         *function-arities* function-arities
                         *multi-arity-dispatch* multi-dispatch]
               ;; `vec` (forcing) must stay INSIDE `binding`'s dynamic
               ;; extent: `mapcat` is lazy, so `(vec (binding [...]
               ;; (mapcat ...)))` would rebind *loop-counter* only around
               ;; building the (unrealized) lazy seq, then unbind it before
               ;; `vec` actually forces each element -- confirmed live as an
               ;; NPE (`*loop-counter*` back to its nil default) on any
               ;; source using `loop`.
               (vec
                     (mapcat
                     (fn [form]
                       (let [[_ name raw-params & body] form]
                         (when-not (valid-name? name) (reject! "invalid function name" name))
                         (when (contains? reserved-function-names name)
                           (reject! "reserved function name" name))
                         (when-not (vector? raw-params)
                           (reject! "function parameters must be a vector" raw-params))
                         (when (> (count raw-params) max-parameters)
                           (reject! "function parameters exceed ABI-supported arity" raw-params))
                         (when-not (= 1 (count body))
                           (reject! "function must contain one result expression" body))
                         (let [name+wraps (mapv param-name+wrap raw-params)
                               params (mapv first name+wraps)
                               wrap-body (apply comp (map second name+wraps))]
                           (when-not (and (every? valid-name? params) (= (count params) (count (distinct params))))
                             (reject! "function parameters must be unique bounded symbols with ABI-supported arity" raw-params))
                           (let [loop-helpers (atom [])
                                 hof-helpers (atom [])
                                 desugared (binding [*pending-loop-helpers* loop-helpers
                                                     *pending-hof-helpers* hof-helpers]
                                             (desugar-expr (wrap-body (first body))))]
                             (into [{:name name :params params :result :i64 :effects #{}
                                     :body desugared}]
                                   (concat @loop-helpers @hof-helpers))))))
                     defs)))
        ;; ADR-2607150000: inject the synthesized `get`/`assoc` helpers only
        ;; when a desugared body actually calls them -- keeps modules that
        ;; never use `get`/`assoc` byte-identical to before this change. A
        ;; user `defn` that collides with a helper's reserved name is
        ;; caught for free by the existing :duplicate-function-name check
        ;; below (signatures' map semantics silently drop one entry, count
        ;; mismatch trips it).
        parsed (into parsed
                     (concat (map :helper @lambda-infos)
                             (lambda-dispatchers @lambda-infos (or @uses-apply? @uses-lazy?))
                             (when @uses-apply? [closure-apply-helper])
                             (when @uses-lazy? [lazy-take-helper lazy-drop-helper])))
        parsed (mapv #(update % :body (partial rewrite-multi-arity-calls multi-dispatch)) parsed)
        parsed (cond-> parsed
                 (some #(uses-map-get? (:body %)) parsed) (conj map-get-helper)
                 (some #(uses-helper? map-contains-key-helper-name (:body %)) parsed)
                 (conj map-contains-key-helper)
                 (some #(uses-map-without? (:body %)) parsed) (conj map-without-helper)
                 (some #(uses-helper? set-contains-helper-name (:body %)) parsed) (conj set-contains-helper)
                 (some #(uses-helper? set-without-helper-name (:body %)) parsed) (conj set-without-helper)
                 (some #(uses-helper? coll-count-helper-name (:body %)) parsed) (conj coll-count-helper)
                 (some #(uses-helper? coll-nth-helper-name (:body %)) parsed) (conj coll-nth-helper)
                 (some #(uses-helper? map-keys-helper-name (:body %)) parsed) (conj map-keys-helper)
                 (some #(uses-helper? map-vals-helper-name (:body %)) parsed) (conj map-vals-helper))
        signatures (into {} (map (juxt :name :params) parsed))]
    (when (seq other) (reject! "only ns and defn are allowed at top level" (first other)))
    (when (empty? parsed) (reject! "at least one defn is required" forms))
    (when (> (count parsed) max-functions)
      (reject! "function count exceeds admission limit after closure/helper lowering" (count parsed)))
    (when-not (= (count parsed) (count signatures)) (reject! "duplicate function name" defs))
    (when-not (contains? signatures 'main) (reject! "main entrypoint is required" defs))
    (when-not (empty? (get signatures 'main)) (reject! "main must take zero arguments" 'main))
    (let [budget (volatile! 0)]
      (doseq [{:keys [params body]} parsed]
        (validate-expr body (set params) signatures 0 budget)))
    (check-lowering-budget! parsed)
    (let [function-effects (infer-effects parsed)
          _ (doseq [{:keys [name lazy-thunk?]} parsed
                    :when (and lazy-thunk? (seq (get function-effects name)))]
              (reject! "lazy sequence thunks must be effect-free because forcing is non-memoized" name))
          functions (mapv #(assoc % :effects (get function-effects (:name %))) parsed)]
      {:format :kotoba.hir/v2 :entry 'main :result :i64
       ;; Every function is exported by current backends, so admission covers
       ;; the union rather than only effects reachable from main.
       :effects (reduce set/union #{} (vals function-effects))
       :functions functions})))
