(ns kotoba.compiler.frontend-extensions-test
  "Tests for ADR-2607150000's language extensions: and/or/when (ported from
  kotoba-lang/kotoba's already-proven runtime.clj desugar-and/desugar-or),
  and keyword/map literals + get/assoc (new, desugared entirely to the
  existing heap-pair/list primitives -- no backend/codegen change)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.target :as target]))

(defn- oracle [source]
  (let [kir (ir/lower (:hir (compiler/check-source source)))]
    (ir/execute kir 'main [])))

(defn- rejection-message [source]
  (try (compiler/check-source source) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(defn- unsupported-typed-targets []
  (remove #(contains? #{:js-kotoba-v1 :wasm32-kotoba-v1} (target/backend %))
          compiler/supported-targets))

(deftest floating-point-policy-is-versioned-and-artifact-sealed
  (let [source "(defn main [] 1)"
        results (mapv #(compiler/compile-source source %) compiler/supported-targets)
        web (compiler/compile-source source :js-kotoba-v1)]
    (is (= :kotoba.floating-point/ieee-754-f64-arithmetic-v1 compiler/floating-point-policy))
    (is (every? #(= compiler/floating-point-policy (:floating-point-policy %)) results))
    (is (= compiler/floating-point-policy
           (get-in web [:manifest :kotoba.artifact/floating-point-policy])))
    (is (str/includes? (:source web) "floatingPointPolicy:'ieee-754-f64-arithmetic-v1'")))
  (is (nil? (rejection-message "(defn main [] :f64 1.5)")))
  (is (nil? (rejection-message
             "(ns float.identity (:export [identity])) (defn identity [x :f64] :f64 x)")))
  (is (some? (rejection-message "(defn main [] NaN)")))
  (is (some? (rejection-message "(defn main [] Infinity)"))))

;; ───────────────────────── and/or/when ─────────────────────────

(deftest and-short-circuits-and-evaluates-each-arg-once
  (is (= 1 (oracle "(defn main [] (and))")))
  (is (= 5 (oracle "(defn main [] (and 5))")))
  (is (= 0 (oracle "(defn main [] (and 1 0 99))")))
  (is (= 7 (oracle "(defn main [] (and 1 2 7))")))
  (is (= 0 (oracle "(defn main [] (and 0 (quot 1 0)))")) ; would trap if 2nd arg evaluated
      "false first arg must short-circuit past a trapping second arg"))

(deftest or-short-circuits-and-evaluates-each-arg-once
  (is (= 0 (oracle "(defn main [] (or))")))
  (is (= 5 (oracle "(defn main [] (or 5))")))
  (is (= 3 (oracle "(defn main [] (or 3 0))")))
  (is (= 4 (oracle "(defn main [] (or 0 4))")))
  (is (= 9 (oracle "(defn main [] (or 9 (quot 1 0)))")) ; would trap if 2nd arg evaluated
      "true first arg must short-circuit past a trapping second arg"))

(deftest when-is-if-with-implicit-else-zero
  (is (= 42 (oracle "(defn main [] (when 1 42))")))
  (is (= 0 (oracle "(defn main [] (when 0 42))")))
  ;; ADR-2607180900 L2: multi-body when admitted via do desugar
  (is (= 3 (oracle "(defn main [] (when 1 2 3))")))
  (is (= 0 (oracle "(defn main [] (when 0 2 3))"))))

(deftest do-sequences-and-returns-last
  (is (= 5 (oracle "(defn main [] (do 1 2 5))")))
  ;; empty do rejected by main frontend (requires at least one expression)
  (is (= 9 (oracle "(defn main [] (do 9))"))))

(deftest and-or-when-are-reserved-function-names
  (is (some? (rejection-message "(defn and [] 1) (defn main [] 0)")))
  (is (some? (rejection-message "(defn or [] 1) (defn main [] 0)")))
  (is (some? (rejection-message "(defn when [] 1) (defn main [] 0)"))))

(deftest booleans-and-option-i64-have-owned-source-and-reference-semantics
  (let [source "(ns pilot.option (:export [negate present? missing? unwrap same?]))
                (defn negate [value :bool] :bool (bool-not value))
                (defn present? [value :option-i64] :bool (some? value))
                (defn missing? [value :option-i64] :bool (nil? value))
                (defn unwrap [value :option-i64] (option-value value 9))
                (defn same? [left :option-i64 right :option-i64] (= left right))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({});"
                   "if(x.negate(true)!==false||x['present?']([true,7n])!==true||x['missing?']([false])!==true)process.exit(2);"
                   "if(x.unwrap([true,7n])!==7n||x.unwrap([false])!==9n)process.exit(3);"
                   "if(x['same?']([false],[false])!==1n||x['same?']([true,7n],[true,8n])!==0n)process.exit(4)})")
        result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (= false (ir/execute kir 'negate [true])))
    (is (= true (ir/execute kir 'present? [[true 7]])))
    (is (= true (ir/execute kir 'missing? [[false]])))
    (is (= 9 (ir/execute kir 'unwrap [[false]])))
    (is (= 1 (ir/execute kir 'same? [[true 7] [true 7]])))
    (is (= :kotoba.value/typed-v1 (:value-profile compiled)))
    (is (= 2 (get-in compiled [:manifest :kotoba.artifact/limits :option-i64-slots])))
    (is (zero? (:exit result)) (:err result))))

(deftest nil-lowers-only-to-tagged-option-none-and-invalid-mixes-fail-closed
  (let [checked (compiler/check-source
                 "(ns pilot.none (:export [missing])) (defn missing [] :option-i64 nil)")]
    (is (= '(option-none) (get-in checked [:hir :functions 0 :body]))))
  (is (some? (rejection-message "(defn bad [] :bool 0)")))
  (is (some? (rejection-message "(defn bad [] :option-i64 false)")))
  (is (some? (rejection-message "(defn bad [x :option-i64] (= x 0))")))
  (is (some? (rejection-message "(defn bad [] (option-some true))"))))

(deftest bounded-vector-i64-has-owned-reference-and-web-semantics
  (let [source "(ns pilot.vector (:export [count-items lookup update append same?]))
                (defn count-items [value :vector-i64] (vector-count value))
                (defn lookup [value :vector-i64 index :i64] (vector-get value index 99))
                (defn update [value :vector-i64 index :i64 item :i64] :vector-i64
                  (vector-assoc value index item))
                (defn append [value :vector-i64 item :i64] :vector-i64
                  (vector-conj value item))
                (defn same? [left :vector-i64 right :vector-i64] (= left right))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({});const before=[1n,2n];"
                   "const after=x.update(before,0n,7n);const appended=x.append(after,8n);"
                   "if(x['count-items'](before)!==2n||x.lookup(before,-1n)!==99n||x.lookup(before,999n)!==99n)process.exit(2);"
                   "if(before[0]!==1n||after[0]!==7n||appended.length!==3||!Object.isFrozen(appended))process.exit(3)})")
        result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (= 3 (ir/execute kir 'count-items [[1 2 3]])))
    (is (= 99 (ir/execute kir 'lookup [[1 2] -1])))
    (is (= [7 2] (ir/execute kir 'update [[1 2] 0 7])))
    (is (= [1 2 3] (ir/execute kir 'append [[1 2] 3])))
    (is (= 1 (ir/execute kir 'same? [[1 2] [1 2]])))
    (is (= 16384 (get-in compiled [:manifest :kotoba.artifact/limits :vector-i64-items])))
    (is (zero? (:exit result)) (:err result))
    (doseq [target-name (unsupported-typed-targets)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"require the kotoba-script web target"
                            (compiler/compile-source source target-name))))))

(deftest vector-i64-constructor-is-explicit-and-bounded
  (let [checked (compiler/check-source
                 "(ns pilot.vector-new (:export [items]))
                  (defn items [] :vector-i64 (vector-i64 1 2 3))")]
    (is (= '(vector-new 1 2 3) (get-in checked [:hir :functions 0 :body]))))
  (is (some? (rejection-message
              (str "(defn main [] :vector-i64 (vector-i64 "
                   (clojure.string/join " " (range 129)) "))"))))
  (is (some? (rejection-message "(defn bad [] :vector-i64 (vector-i64 1 true))"))))

;; ───────────────────────── keyword + map + get + assoc ─────────────────────────

(deftest keyword-literals-preserve-canonical-identity-without-i64-hashing
  (let [source "(ns pilot.keyword (:export [identity same?]))
                (defn identity [value :keyword] :keyword value)
                (defn same? [left :keyword right :keyword] (= left right))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        js-source (:source compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String js-source "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({});"
                   "if(x.identity(':安全/確認')!==':安全/確認')process.exit(2);"
                   "if(x['same?'](':a',':a')!==1n||x['same?'](':a',':b')!==0n)process.exit(3)})")
        result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (= :keyword (get-in compiled [:kir :functions 0 :result])))
    (is (= [:keyword] (get-in compiled [:kir :functions 0 :param-types])))
    (is (= :安全/確認 (ir/execute (:kir compiled) 'identity [:安全/確認])))
    (is (= 1 (ir/execute (:kir compiled) 'same? [:a :a])))
    (is (= 0 (ir/execute (:kir compiled) 'same? [:a :b])))
    (is (zero? (:exit result)) (:err result))
    (is (not (re-find #"fnv|1099511628211|3750763034362895579" js-source)))
    (doseq [target (unsupported-typed-targets)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"require the kotoba-script web target"
                            (compiler/compile-source source target))))))

(deftest keyword-maps-use-owned-typed-operations
  (let [kir (:kir (compiler/compile-source "(defn main [] (get {:a 1} :a))"
                                           :js-kotoba-v1))]
    (is (= '(map-get (map-new :a 1) :a 0)
           (get-in kir [:functions 0 :body])))
    (is (= 1 (ir/execute kir 'main [])))))

(deftest result-i64-has-owned-source-reference-and-web-semantics
  (let [source "(ns result.pilot (:export [ok? value error same?]))
                (defn ok? [r :result-i64] :bool (result-ok? r))
                (defn value [r :result-i64] (result-value r 99))
                (defn error [r :result-i64] (result-error r 98))
                (defn same? [a :result-i64 b :result-i64] (= a b))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)]
    (is (true? (ir/execute kir 'ok? [[true 7]])))
    (is (false? (ir/execute kir 'ok? [[false 12]])))
    (is (= 7 (ir/execute kir 'value [[true 7]])))
    (is (= 99 (ir/execute kir 'value [[false 12]])))
    (is (= 12 (ir/execute kir 'error [[false 12]])))
    (is (= 98 (ir/execute kir 'error [[true 7]])))
    (is (= 1 (ir/execute kir 'same? [[false 12] [false 12]])))
    (is (= 2 (get-in compiled [:manifest :kotoba.artifact/limits :result-i64-slots])))
    (is (str/includes? (:source compiled) "resultProfile:'tagged-i64-i64-v1'")))
  (is (some? (rejection-message "(defn bad [] :result-i64 1)")))
  (is (some? (rejection-message "(defn bad [] (result-ok true))")))
  (doseq [target (unsupported-typed-targets)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require the kotoba-script web target"
                          (compiler/compile-source "(defn main [] :result-i64 (result-ok 1))" target)))))

(deftest parametric-result-types-preserve-nested-payloads-and-budgets
  (let [type [:result :string [:result :i64 :bool]]
        source "(ns result.generic (:export [make inspect]))
                (defn make [] [:result :string [:result :i64 :bool]]
                  (result-err-of [:result :string [:result :i64 :bool]]
                    (result-ok-of [:result :i64 :bool] 7)))
                (defn inspect [r [:result :string [:result :i64 :bool]]] :bool
                  (result-ok?-of [:result :i64 :bool]
                    (result-error-of [:result :string [:result :i64 :bool]] r
                      (result-err-of [:result :i64 :bool] false))))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)]
    (is (= [false [true 7]] (ir/execute kir 'make [])))
    (is (true? (ir/execute kir 'inspect [[false [true 7]]])))
    (is (= type (get-in kir [:functions 0 :result])))
    (is (= 8 (get-in compiled [:manifest :kotoba.artifact/limits :parametric-adt-depth])))
    (is (= 64 (get-in compiled [:manifest :kotoba.artifact/limits :parametric-adt-nodes])))
    (is (str/includes? (:source compiled) "parametricAdtLimits:Object.freeze({depth:8,nodes:64,variantCases:32})"))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/execute kir 'inspect [[false [true "bad"]]]))))
  (let [too-deep (nth (iterate (fn [t] [:result :i64 t]) :bool) 9)
        source (str "(defn bad [x " (pr-str too-deep) "] :bool true)")]
    (is (some? (rejection-message source))))
  (doseq [target (unsupported-typed-targets)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require the kotoba-script web target"
                          (compiler/compile-source
                           "(defn main [] [:result :i64 :bool] (result-ok-of [:result :i64 :bool] 1))"
                           target)))))

(deftest match-result-is-exhaustive-scoped-typed-and-lazy
  (let [type [:result :string :i64]
        source "(ns result.match (:export [ok-len err-code]))
                (defn ok-len [r [:result :string :i64]]
                  (match-result r [:result :string :i64]
                    (ok text (string-byte-length text))
                    (err code (quot 1 0))))
                (defn err-code [r [:result :string :i64]]
                  (match-result r [:result :string :i64]
                    (ok text (quot 1 0))
                    (err code code)))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({});"
                   "if(x['ok-len']([true,'安全'])!==6n||x['err-code']([false,17n])!==17n)process.exit(2)})")
        node-result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (= 6 (ir/execute kir 'ok-len [[true "安全"]])))
    (is (= 17 (ir/execute kir 'err-code [[false 17]])))
    (is (zero? (:exit node-result)) (:err node-result))
    (is (str/includes? (:source compiled) "const parametricResultMatch="))
    (is (= type (get-in kir [:functions 0 :param-types 0]))))
  (doseq [source ["(defn bad [r [:result :i64 :bool]] (match-result r [:result :i64 :bool] (ok x x)))"
                  "(defn bad [r [:result :i64 :bool]] (match-result r [:result :i64 :bool] (err e 1) (ok x x)))"
                  "(defn bad [r [:result :i64 :bool]] (match-result r [:result :i64 :bool] (ok x x) (err e false)))"]]
    (is (some? (rejection-message source)))))

(deftest closed-variants-have-canonical-identity-and-exhaustive-matching
  (let [type [:variant :demo/status [[:ready :i64] [:failed :string]]]
        source "(ns variant.demo (:export [ready describe]))
                (defn ready [] [:variant :demo/status [[:ready :i64] [:failed :string]]]
                  (variant-new [:variant :demo/status [[:ready :i64] [:failed :string]]] :ready 7))
                (defn describe [v [:variant :demo/status [[:ready :i64] [:failed :string]]]]
                  (match-variant v [:variant :demo/status [[:ready :i64] [:failed :string]]]
                    (:ready n (+ n 1))
                    (:failed message (string-byte-length message))))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({});"
                   "const t=Object.freeze(['variant',':demo/status',Object.freeze([Object.freeze([':ready','i64']),Object.freeze([':failed','string'])])]);"
                   "if(x.describe([t,':ready',9n])!==10n||x.describe([t,':failed','安全'])!==6n)process.exit(2)})")
        node-result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (= [type :ready 7] (ir/execute kir 'ready [])))
    (is (= 10 (ir/execute kir 'describe [[type :ready 9]])))
    (is (= 6 (ir/execute kir 'describe [[type :failed "安全"]])))
    (is (zero? (:exit node-result)) (:err node-result))
    (is (= 32 (get-in compiled [:manifest :kotoba.artifact/limits :variant-cases])))
    (is (str/includes? (:source compiled) "variantCases:32"))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/execute kir 'describe [[[:variant :other/status [[:ready :i64]]] :ready 9]]))))
  (doseq [source ["(defn bad [v [:variant :demo/x [[:a :i64] [:b :i64]]]] (match-variant v [:variant :demo/x [[:a :i64] [:b :i64]]] (:a x x)))"
                  "(defn bad [v [:variant :demo/x [[:a :i64] [:b :i64]]]] (match-variant v [:variant :demo/x [[:a :i64] [:b :i64]]] (:b x x) (:a y y)))"
                  "(defn bad [] [:variant :demo/x [[:a :i64]]] (variant-new [:variant :demo/x [[:a :i64]]] :unknown 1))"]]
    (is (some? (rejection-message source)))))

(deftest generic-options-have-canonical-none-identity-and-lazy-exhaustive-matching
  (let [type [:option :string]
        source "(ns option.generic (:export [some-text none-text describe force]))
                (defn- explode [] :string (explode))
                (defn some-text [] [:option :string]
                  (option-some-of [:option :string] \"安全\"))
                (defn none-text [] [:option :string]
                  (option-none-of [:option :string]))
                (defn describe [value [:option :string]]
                  (match-option value [:option :string]
                    (none 7)
                    (some text (string-byte-length text))))
                (defn force [value [:option :string]] :string
                  (option-value-of [:option :string] value (explode)))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({}),t=Object.freeze(['option','string']);"
                   "const s=x['some-text'](),n=x['none-text']();"
                   "if(s[0][0]!=='option'||s[1]!==true||s[2]!=='安全'||n[1]!==false)process.exit(2);"
                   "if(x.describe([t,true,'安全'])!==6n||x.describe([t,false])!==7n||x.force([t,true,'安全'])!=='安全')process.exit(3);"
                   "try{x.describe([Object.freeze(['option','i64']),false]);process.exit(4)}"
                   "catch(e){if(e.message!=='invalid-generic-option')process.exit(5)}})")
        node-result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (= [type true "安全"] (ir/execute kir 'some-text [])))
    (is (= [type false] (ir/execute kir 'none-text [])))
    (is (= 6 (ir/execute kir 'describe [[type true "安全"]])))
    (is (= 7 (ir/execute kir 'describe [[type false]])))
    (is (= "安全" (ir/execute kir 'force [[type true "安全"]])))
    (is (= 3 (get-in compiled [:manifest :kotoba.artifact/limits
                               :generic-option-max-slots])))
    (is (zero? (:exit node-result)) (:err node-result))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/execute kir 'describe [[[:option :i64] false]]))))
  (doseq [source
          ["(defn bad [] [:option :string] (option-some-of [:option :string] 7))"
           "(defn bad [] [:option :string] (option-none-of [:option :i64]))"
           "(defn bad [v [:option :string]] (match-option v [:option :string] (none 0)))"
           "(defn bad [v [:option :string]] (match-option v [:option :string] (some x 1) (none 0)))"
           "(defn bad [v [:option :string]] (match-option v [:option :string] (none 0) (some x x)))"
           "(defn bad [v [:option :string]] (option-some?-of [:option :i64] v))"]]
    (is (some? (rejection-message source))))
  (doseq [target-name (unsupported-typed-targets)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"require the kotoba-script web target"
                          (compiler/compile-source
                           "(defn main [] [:option :string] (option-none-of [:option :string]))"
                           target-name)))))

(deftest heterogeneous-vectors-are-exact-statically-indexed-and-persistent
  (let [type [:vector [:i64 :string :bool]]
        source "(ns vector.heterogeneous (:export [make name rename count-items same?]))
                (defn make [] [:vector [:i64 :string :bool]]
                  (hetero-vector [:vector [:i64 :string :bool]] 7 \"安全\" true))
                (defn name [value [:vector [:i64 :string :bool]]] :string
                  (hetero-vector-at [:vector [:i64 :string :bool]] value 1))
                (defn rename [value [:vector [:i64 :string :bool]]]
                  [:vector [:i64 :string :bool]]
                  (hetero-vector-assoc [:vector [:i64 :string :bool]] value 1 \"確認\"))
                (defn count-items [value [:vector [:i64 :string :bool]]]
                  (hetero-vector-count [:vector [:i64 :string :bool]] value))
                (defn same? [left [:vector [:i64 :string :bool]]
                             right [:vector [:i64 :string :bool]]]
                  (hetero-vector-equal [:vector [:i64 :string :bool]] left right))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({});"
                   "const t=Object.freeze(['vector',Object.freeze(['i64','string','bool'])]);"
                   "const before=x.make(),after=x.rename(before);"
                   "if(x.name(before)!=='安全'||x['count-items'](before)!==3n||after[2]!=='確認'||before[2]!=='安全')process.exit(2);"
                   "if(x['same?'](before,[t,7n,'安全',true])!==1n||x['same?'](before,after)!==0n||!Object.isFrozen(after))process.exit(3);"
                   "try{x.name([Object.freeze(['vector',Object.freeze(['string','string','bool'])]),7n,'安全',true]);process.exit(4)}"
                   "catch(e){if(e.message!=='invalid-heterogeneous-vector')process.exit(5)}})")
        node-result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (= [type 7 "安全" true] (ir/execute kir 'make [])))
    (is (= "安全" (ir/execute kir 'name [[type 7 "安全" true]])))
    (is (= [type 7 "確認" true]
           (ir/execute kir 'rename [[type 7 "安全" true]])))
    (is (= 3 (ir/execute kir 'count-items [[type 7 "安全" true]])))
    (is (= 1 (ir/execute kir 'same? [[type 7 "安全" true]
                                      [type 7 "安全" true]])))
    (is (= 32 (get-in compiled [:manifest :kotoba.artifact/limits
                                :heterogeneous-vector-items])))
    (is (zero? (:exit node-result)) (:err node-result))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/execute kir 'name [[[ :vector [:string :string :bool]]
                                         7 "安全" true]]))))
  (doseq [source
          ["(defn bad [] [:vector [:i64 :string]] (hetero-vector [:vector [:i64 :string]] 1))"
           "(defn bad [] [:vector [:i64 :string]] (hetero-vector [:vector [:i64 :string]] \"x\" \"y\"))"
           "(defn bad [v [:vector [:i64 :string]] i :i64] :string (hetero-vector-at [:vector [:i64 :string]] v i))"
           "(defn bad [v [:vector [:i64 :string]]] :string (hetero-vector-at [:vector [:i64 :string]] v 2))"
           "(defn bad [v [:vector [:i64 :string]]] [:vector [:i64 :string]] (hetero-vector-assoc [:vector [:i64 :string]] v 0 \"wrong\"))"]]
    (is (some? (rejection-message source))))
  (doseq [target-name (unsupported-typed-targets)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"require the kotoba-script web target"
                          (compiler/compile-source
                           "(defn main [] [:vector [:i64 :string]] (hetero-vector [:vector [:i64 :string]] 1 \"x\"))"
                           target-name)))))

(deftest typed-sets-are-unique-canonically-ordered-and-persistent
  (let [type [:set :i64]
        source "(ns set.typed (:export [make duplicate contains? add remove count-items same?]))
                (defn make [] [:set :i64] (typed-set [:set :i64] 3 1 2))
                (defn duplicate [] [:set :i64] (typed-set [:set :i64] 1 1))
                (defn contains? [value [:set :i64] item :i64] :bool
                  (typed-set-contains [:set :i64] value item))
                (defn add [value [:set :i64] item :i64] [:set :i64]
                  (typed-set-conj [:set :i64] value item))
                (defn remove [value [:set :i64] item :i64] [:set :i64]
                  (typed-set-disj [:set :i64] value item))
                (defn count-items [value [:set :i64]]
                  (typed-set-count [:set :i64] value))
                (defn same? [left [:set :i64] right [:set :i64]]
                  (typed-set-equal [:set :i64] left right))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({}),t=Object.freeze(['set','i64']);"
                   "const a=x.make(),b=x.add(a,4n),c=x.remove(b,2n);"
                   "if(a[1].join(',')!=='1,2,3'||!x['contains?'](a,2n)||x['contains?'](a,9n)||x['count-items'](a)!==3n)process.exit(2);"
                   "if(a[1].length!==3||b[1].join(',')!=='1,2,3,4'||c[1].join(',')!=='1,3,4'||!Object.isFrozen(c[1]))process.exit(3);"
                   "if(x['same?'](a,[t,[3n,2n,1n]])!==1n||x['same?'](a,b)!==0n)process.exit(4);"
                   "try{x.duplicate();process.exit(5)}catch(e){if(e.message!=='duplicate-set-item')process.exit(6)}"
                   "try{x['contains?']([Object.freeze(['set','string']),['1']],1n);process.exit(7)}"
                   "catch(e){if(e.message!=='invalid-typed-set')process.exit(8)}})")
        node-result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (= [type [1 2 3]] (ir/execute kir 'make [])))
    (is (true? (ir/execute kir 'contains? [[type [3 1 2]] 2])))
    (is (false? (ir/execute kir 'contains? [[type [3 1 2]] 9])))
    (is (= [type [1 2 3 4]] (ir/execute kir 'add [[type [3 1 2]] 4])))
    (is (= [type [1 3]] (ir/execute kir 'remove [[type [3 1 2]] 2])))
    (is (= 1 (ir/execute kir 'same? [[type [3 1 2]] [type [1 2 3]]])))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'duplicate [])))
    (is (= 32 (get-in compiled [:manifest :kotoba.artifact/limits :typed-set-items])))
    (is (zero? (:exit node-result)) (:err node-result)))
  (doseq [source
          ["(defn bad [] [:set :i64] (typed-set [:set :i64] \"wrong\"))"
           "(defn bad [v [:set :i64]] :bool (typed-set-contains [:set :i64] v \"wrong\"))"
           "(defn bad [] [:set :i64] (typed-set [:set :i64] 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32))"]]
    (is (some? (rejection-message source))))
  (doseq [target-name (unsupported-typed-targets)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"require the kotoba-script web target"
                          (compiler/compile-source
                           "(defn main [] [:set :i64] (typed-set [:set :i64] 1 2))"
                           target-name)))))

(deftest bounded-records-own-nominal-schema-and-persistent-fields
  (let [type [:record :demo/person [[:name :string] [:age :i64]
                                     [:nickname [:option :string]]]]
        option-type [:option :string]
        source "(ns record.typed (:export [make name-of birthday same?]))
                (defn make [] [:record :demo/person [[:name :string] [:age :i64] [:nickname [:option :string]]]]
                  (record [:record :demo/person [[:name :string] [:age :i64] [:nickname [:option :string]]]]
                          \"Kotoba\" 7 (option-none-of [:option :string])))
                (defn name-of [value [:record :demo/person [[:name :string] [:age :i64] [:nickname [:option :string]]]]] :string
                  (record-get [:record :demo/person [[:name :string] [:age :i64] [:nickname [:option :string]]]] value :name))
                (defn birthday [value [:record :demo/person [[:name :string] [:age :i64] [:nickname [:option :string]]]] age :i64]
                  [:record :demo/person [[:name :string] [:age :i64] [:nickname [:option :string]]]]
                  (record-assoc [:record :demo/person [[:name :string] [:age :i64] [:nickname [:option :string]]]] value :age age))
                (defn same? [left [:record :demo/person [[:name :string] [:age :i64] [:nickname [:option :string]]]]
                             right [:record :demo/person [[:name :string] [:age :i64] [:nickname [:option :string]]]]]
                  (record-equal [:record :demo/person [[:name :string] [:age :i64] [:nickname [:option :string]]]] left right))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({}),v=x.make(),u=x.birthday(v,8n);"
                   "if(x['name-of'](v)!=='Kotoba'||v[2]!==7n||u[2]!==8n||v[2]!==7n)process.exit(2);"
                   "if(!Object.isFrozen(v)||x['same?'](v,u)!==0n||x['same?'](v,v)!==1n)process.exit(3);"
                   "const ot=Object.freeze(['record',':demo/account',v[0][2]]);"
                   "try{x['name-of']([ot,'Kotoba',7n,[Object.freeze(['option','string']),false]]);process.exit(4)}"
                   "catch(e){if(e.message!=='invalid-record')process.exit(5)}})")
        node-result (shell/sh "node" "--input-type=module" "-e" probe)
        value [type "Kotoba" 7 [option-type false]]]
    (is (= value (ir/execute kir 'make [])))
    (is (= "Kotoba" (ir/execute kir 'name-of [value])))
    (is (= [type "Kotoba" 8 [option-type false]]
           (ir/execute kir 'birthday [value 8])))
    (is (= 1 (ir/execute kir 'same? [value value])))
    (is (= 32 (get-in compiled [:manifest :kotoba.artifact/limits :record-fields])))
    (is (zero? (:exit node-result)) (:err node-result)))
  (doseq [source
          ["(defn bad [] [:record :demo/person [[:name :string]]] (record [:record :demo/person [[:name :string]]] 7))"
           "(defn bad [v [:record :demo/person [[:name :string]]]] :string (record-get [:record :demo/person [[:name :string]]] v :missing))"
           "(defn bad [] [:record :demo/bad [[:x :i64] [:x :string]]] 0)"]]
    (is (some? (rejection-message source))))
  (doseq [target-name (unsupported-typed-targets)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"require the kotoba-script web target"
                          (compiler/compile-source
                           "(defn main [] [:record :demo/one [[:x :i64]]] (record [:record :demo/one [[:x :i64]]] 1))"
                           target-name)))))

(deftest bounded-map-host-abi-runs-through-compiler-and-kotoba-script
  (let [source "(ns pilot.map (:export [lookup update]))
                (defn lookup [value :map] (get value :a 0))
                (defn update [value :map] :map (assoc value :b 2 :a 3))"
        compiled (compiler/compile-source source :js-kotoba-v1)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({});"
                   "const before=[[':a',1n]];const after=x.update(before);"
                   "if(x.lookup(before)!==1n||x.lookup(after)!==3n)process.exit(2);"
                   "if(before[0][1]!==1n||after.length!==2)process.exit(3)})")
        result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (= :kotoba.value/typed-v1 (:value-profile compiled)))
    (is (= 128 (get-in compiled [:manifest :kotoba.artifact/limits :map-entries])))
    (is (zero? (:exit result)) (:err result))))

(deftest map-literal-get-round-trips
  (is (= 1 (oracle "(defn main [] (get {:a 1} :a))")))
  (is (= 2 (oracle "(defn main [] (get {:a 1 :b 2} :b))")))
  (is (= 0 (oracle "(defn main [] (get {:a 1} :missing))"))
      "2-arg get defaults to 0 on a miss")
  (is (= 99 (oracle "(defn main [] (get {:a 1} :missing 99))"))
      "3-arg get uses the explicit default on a miss")
  (is (= 0 (oracle "(defn main [] (get {} :a))")) "get on an empty map is a miss"))

(deftest map-literal-values-can-be-arbitrary-expressions
  (is (= 7 (oracle "(defn main [] (get {:a (+ 3 4)} :a))"))))

(deftest get-on-a-map-passed-through-a-function-parameter-walks-at-runtime
  (is (= 5 (oracle "(defn extract [m :map] (get m :a))
                     (defn main [] (extract {:a 5}))"))
      "get must work on a map value whose shape isn't known at the call site,
       not just on a literal map inlined at the get form itself"))

(deftest assoc-adds-and-shadows
  (is (= 7 (oracle "(defn main [] (get (assoc {:a 1} :c 7) :c))")))
  (is (= 5 (oracle "(defn main [] (get (assoc {:a 1} :a 5) :a))"))
      "assoc on an existing key shadows the old value (get returns the newest)")
  (is (= 1 (oracle "(defn main [] (get (assoc {} :a 1) :a))"))
      "assoc onto an empty map")
  (is (= 9 (oracle "(defn main [] (get (assoc {:a 1} :b 2 :c 9) :c))"))
      "variadic assoc with multiple key/value pairs in one call"))

(deftest cond-thread-applies-bounded-persistent-map-updates-in-order
  (is (= 7 (oracle "(defn main []
                      (get (cond-> {:a 1} true (assoc :b 2) false (assoc :b 9)
                                           true (assoc :c 7)) :c))")))
  (is (= 2 (oracle "(defn main []
                      (get (cond-> {:a 1} true (assoc :b 2) false (assoc :b 9)) :b))")))
  (is (= "cond-> update must be a non-empty call form"
         (rejection-message "(defn main [] (cond-> {:a 1} true :not-a-call))"))))

(deftest assoc-replaces-without-mutating-or-duplicating
  (is (= 5 (oracle "(defn main [] (get (assoc (assoc (assoc {:a 1 :b 2} :a 3) :a 4) :a 5) :a))")))
  (is (= 2 (oracle "(defn main [] (get (assoc (assoc {:a 1 :b 2} :a 3) :a 4) :b))"))))

(deftest map-entry-count-is-admission-bounded
  (with-redefs [frontend/max-list-items 1]
    (is (some? (rejection-message "(defn main [] (get {:a 1 :b 2} :a))")))))

(deftest typed-map-does-not-inject-legacy-pair-walk-helpers
  (let [{:keys [functions]} (:hir (compiler/check-source "(defn main [] (get {:a 1} :a))"))]
    (is (not (contains? (set (map :name functions)) '__kotoba_map_get)))
    (is (not (contains? (set (map :name functions)) '__kotoba_map_without)))))

(deftest legacy-helper-names-no-longer-affect-map-lowering
  (is (= 2 (oracle "(defn __kotoba_map_without [m k] 0)
                     (defn main [] (get (assoc {:a 1} :a 2) :a))"))))

(deftest get-and-assoc-are-reserved-function-names
  (is (some? (rejection-message "(defn get [] 1) (defn main [] 0)")))
  (is (some? (rejection-message "(defn assoc [] 1) (defn main [] 0)"))))

(deftest map-get-helper-not-injected-unless-get-is-used
  (let [{:keys [functions]} (:hir (compiler/check-source "(defn main [] (+ 1 2))"))]
    (is (not (contains? (set (map :name functions)) '__kotoba_map_get)))))

(deftest map-helpers-are-never-injected
  (let [{:keys [functions]} (:hir (compiler/check-source "(defn main [] (get {:a 1} :a))"))]
    (is (not (contains? (set (map :name functions)) '__kotoba_map_get)))
    (is (not (contains? (set (map :name functions)) '__kotoba_map_without))
        "but not the assoc-only helper, since this source never calls assoc")))

(deftest map-literal-entry-count-cannot-exceed-max-list-items
  ;; A literal map can never grow past max-list-items (128, shared with
  ;; `list`) -- so a get-miss walk over the LARGEST admissible map literal
  ;; costs at most 129 fuel units (128 steps + 1 for main's own call),
  ;; comfortably under this compiler's fixed 512-fuel budget. Verifies
  ;; that bound actually holds, rather than just asserting it in prose.
  (let [source (str "(defn main [] (get {"
                     (clojure.string/join " " (map #(str ":k" % " " %) (range 128)))
                     "} :missing))")]
    (is (= 0 (oracle source)) "a full-width admissible map miss completes within fuel")))

(deftest closed-top-level-constants-are-lexically-inlined
  (let [source "(ns pilot.constants \"inert data\")
                (def factor \"bounded integer\" 21)
                (def config {:multiplier 2})
                (defn apply-factor [factor] factor)
                (defn main []
                  (+ (apply-factor 1)
                     (* factor (get config :multiplier))))"]
    (is (= 43 (oracle source)))
    (is (= 43 (ir/execute (:kir (compiler/compile-source source :js-kotoba-v1))
                          'main [])))))

(deftest string-constant-is-a-value-not-an-implicit-docstring
  (is (= "@context"
         (oracle "(def context-key \"@context\") (defn main [] :string context-key)")))
  (is (= "@context"
         (oracle "(def context-key \"JSON-LD key\" \"@context\")
                  (defn main [] :string context-key)"))))

(deftest literal-keyword-constructor-is-closed-at-compile-time
  (is (= (keyword "@context")
         (oracle "(def context-key (keyword \"@context\"))
                  (defn main [] :keyword context-key)")))
  (is (= "constant value must be closed bounded integer/string/keyword/boolean/nil/vector/map data"
         (rejection-message "(def context-key (keyword (host-value)))
                             (defn main [] context-key)"))))

(deftest top-level-constants-never-execute-code-or-shadow-locals
  (is (= "constant value must be closed bounded integer/string/keyword/boolean/nil/vector/map data"
         (rejection-message "(def danger (+ 1 2)) (defn main [] danger)")))
  (is (= "duplicate constant name"
         (rejection-message "(def x 1) (def x 2) (defn main [] x)")))
  (is (= "constant and function names must be disjoint"
         (rejection-message "(def x 1) (defn x [] 2) (defn main [] x)")))
  (is (= 7 (oracle "(def x 99) (defn identity-x [x] x) (defn main [] (identity-x 7))")))
  (is (= 8 (oracle "(def x 99) (defn main [] (let [x 8] x))"))))

(deftest explicit-module-exports-hide-private-functions-across-backends
  (let [source "(ns pilot.module (:export [main]))
                (defn- hidden [x] (+ x 1))
                (defn main [] (hidden 41))"
        wasm (compiler/compile-source source :wasm32-kotoba-v1)
        js (compiler/compile-source source :js-kotoba-v1)
        cljs (compiler/compile-source source :cljs-kotoba-v1)
        native (compiler/compile-source source :x86_64-kotoba-v1)
        wasm-text (String. ^bytes (:bytes wasm) "ISO-8859-1")]
    (is (= ['main] (get-in wasm [:hir :exports])))
    (is (= ['main] (get-in wasm [:kir :exports])))
    (is (= 42 (get-in wasm [:kir :oracle-value])))
    (is (re-find #"main" wasm-text))
    (is (not (re-find #"hidden" wasm-text)))
    (is (= #{'main} (set (keys (get-in native [:artifact :exports])))))
    (is (re-find #"\(defn- hidden" (:source cljs)))
    (is (not (re-find #"'hidden':k\$hidden" (:source js))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not exported"
                          (ir/execute (:kir wasm) 'hidden [1])))))

(deftest module-export-declarations-fail-closed
  (is (= "namespace exports must name declared public functions"
         (rejection-message "(ns bad (:export [missing])) (defn main [] 0)")))
  (is (= "namespace exports must name declared public functions"
         (rejection-message "(ns bad (:export [main hidden])) (defn- hidden [] 1) (defn main [] 0)")))
  (is (= "namespace exports must be unique bounded function names"
         (rejection-message "(ns bad (:export [main main])) (defn main [] 0)")))
  (is (= "main entrypoint must be exported"
         (rejection-message "(ns bad (:export [helper])) (defn helper [] 1) (defn main [] 0)")))
  (is (= "only a bounded :export vector is admitted in namespace clauses"
         (rejection-message "(ns bad (:require [ambient])) (defn main [] 0)"))))

(deftest entryless-library-compiles-and-runs-through-kotoba-script
  (let [source "(ns pilot.library (:export [add1])) (defn add1 [x] (+ x 1))"
        checked (compiler/check-source source)
        compiled (compiler/compile-source source :js-kotoba-v1)
        js-source (:source compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String js-source "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({});"
                   "if(m.kotobaArtifact.entry!==null||x.add1(41n)!==42n)process.exit(2)})")
        result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (nil? (get-in checked [:hir :entry])))
    (is (= ['add1] (get-in compiled [:kir :exports])))
    (is (nil? (get-in compiled [:kir :oracle-value])))
    (is (zero? (:exit result)) (:err result))
    (doseq [target (unsupported-typed-targets)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require the kotoba-script web target"
                            (compiler/compile-source source target)))))
  (is (= "entryless library requires an explicit non-empty namespace export list"
         (rejection-message "(defn add1 [x] (+ x 1))")))
  (is (= "entryless library requires at least one exported function"
         (rejection-message "(ns empty.library (:export [])) (defn- hidden [] 0)"))))

(deftest typed-bounded-strings-remain-strings-through-checked-kir-and-web
  (let [source (str "(ns pilot.text (:export [greet byte-length same?])) "
                    "(defn greet [name :string] :string (string-concat \"こんにちは、\" name)) "
                    "(defn byte-length [value :string] (string-byte-length value)) "
                    "(defn same? [left :string right :string] (string=? left right))")
        checked (compiler/check-source source)
        compiled (compiler/compile-source source :js-kotoba-v1)
        js-source (:source compiled)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String js-source "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({});"
                   "if(x.greet('言葉')!=='こんにちは、言葉')process.exit(2);"
                   "if(x['byte-length']('言葉')!==6n)process.exit(3);"
                   "if(x['same?']('言葉','言葉')!==1n||x['same?']('言葉','ことば')!==0n)process.exit(4)})")
        result (shell/sh "node" "--input-type=module" "-e" probe)]
    (is (= :kotoba.hir/v3 (get-in checked [:hir :format])))
    (is (= :kotoba.kir/v4 (get-in compiled [:kir :format])))
    (is (= :kotoba.value/typed-v1 (:value-profile compiled)))
    (is (= 65536 (get-in compiled [:manifest :kotoba.artifact/limits
                                   :string-value-bytes])))
    (is (re-find #"valueProfile:'typed-v1'" js-source))
    (is (= [:string] (get-in compiled [:kir :functions 0 :param-types])))
    (is (= :string (get-in compiled [:kir :functions 0 :result])))
    (is (= "こんにちは、言葉" (ir/execute (:kir compiled) 'greet ["言葉"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"string exceeds UTF-8 byte limit"
                          (ir/execute (:kir compiled) 'greet [(apply str (repeat 65537 "x"))])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"string exceeds UTF-8 byte limit"
                          (ir/execute (:kir compiled) 'greet [(apply str (repeat 65530 "x"))])))
    (is (zero? (:exit result)) (:err result))
    (doseq [target (unsupported-typed-targets)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require the kotoba-script web target"
                            (compiler/compile-source source target)))))
  (is (= "expression type mismatch: expected string, got i64"
         (rejection-message "(ns bad (:export [f])) (defn f [] :string 1)")))
  (is (= "expression type mismatch: expected i64, got string"
         (rejection-message "(defn main [] (+ \"not-an-integer\" 1))")))
  (is (= "typed parameters require alternating name/type pairs"
         (rejection-message "(ns bad (:export [f])) (defn f [x :string y] :string x)")))
  (is (= "string exceeds UTF-8 byte limit"
         (rejection-message
          (str "(ns bad (:export [f])) (defn f [] :string \""
               (apply str (repeat 4097 "x")) "\")"))))
  (let [literal (apply str (repeat 4096 "x"))
        definitions (apply str
                           (map (fn [index]
                                  (str "(defn f" index " [] :string \"" literal "\")"))
                                (range 17)))]
    (is (= "module string literals exceed UTF-8 byte limit"
           (rejection-message (str "(ns too.large (:export [f0]))" definitions))))))

(deftest map-get-recursion-shares-the-existing-fuel-budget
  ;; get/assoc introduce no new resource limit -- they are subject to the
  ;; SAME fixed fuel budget (ir.clj/backend/wasm.clj's 512-instruction-call
  ;; global counter) every other recursive .kotoba program already is.
  ;; Demonstrated here via a helper that repeatedly assocs while recursing
  ;; past the budget -- the existing mechanism traps it, unmodified.
  (let [source "(defn build [m :map n :i64] :map (if (= n 0) m (build (assoc m :dummy n) (- n 1))))
                (defn main [] (get (build {} 600) :missing))"]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fuel"
                          (ir/execute (:kir (compiler/compile-source source :js-kotoba-v1)) 'main [])))))

;; ───────────────────────── cross-backend consistency ─────────────────────────

(deftest map-get-is-owned-by-the-web-typed-value-profile
  (let [source "(defn main [] (get {:a 1 :b 2} :b))"
        compiled (compiler/compile-source source :js-kotoba-v1)]
    (is (= 2 (ir/execute (:kir compiled) 'main [])))
    (doseq [target (unsupported-typed-targets)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"require the kotoba-script web target"
                            (compiler/compile-source source target))))))

(deftest map-assoc-reference-and-kotoba-script-agree
  (is (= 2 (oracle "(defn main [] (get (assoc {:a 1} :b 2) :b))"))))

(deftest canonical-typed-map-reference-semantics-are-bounded-and-sentinel-free
  (let [type "[:map :keyword :i64]"
        source (str
                "(defn main [] :i64 "
                "(let [m (typed-map-new " type " :b 2 :a 1) "
                "      m2 (typed-map-assoc " type " m :b 7) "
                "      m3 (typed-map-dissoc " type " m2 :a)] "
                "  (if (typed-map-contains " type " m3 :b) "
                "    (if (= 1 (typed-map-count " type " m3)) "
                "      (option-value-of [:option :i64] "
                "        (typed-map-get " type " m3 :b) 0) 0) 0)))")]
    (is (= 7 (oracle source)))
    (let [compiled (compiler/compile-source source :js-kotoba-v1)
          encoded (.encodeToString (java.util.Base64/getEncoder)
                                   (.getBytes ^String (:source compiled) "UTF-8"))
          probe (shell/sh "node" "--input-type=module" "-e"
                          (str "import('data:text/javascript;base64," encoded
                               "').then(m=>{if(m.instantiateKotoba({}).main()!==7n)process.exit(2)})"))]
      (is (= 31 (get-in compiled [:manifest :kotoba.artifact/limits :typed-map-entries])))
      (is (zero? (:exit probe)) (:err probe)))
    (is (= [[:option :i64] false]
           (ir/execute (ir/lower (frontend/analyze
                                  (str "(defn main [] [:option :i64] "
                                       "(typed-map-get " type
                                       " (typed-map-new " type ") :missing))")))
                       'main [])))))

(deftest canonical-typed-map-rejects-type-confusion-and-overflow
  (doseq [source
          ["(defn main [] [:map :keyword :i64] (typed-map-new [:map :keyword :i64] :a \"bad\"))"
           "(defn main [] [:option :i64] (typed-map-get [:map :keyword :i64] (typed-map-new [:map :keyword :i64]) \"bad\"))"]]
    (is (some? (rejection-message source))))
  (let [entries (str/join " " (mapcat (fn [index]
                                         [(str ":k" index) (str index)])
                                       (range 32)))
        source (str "(defn main [] [:map :keyword :i64] "
                    "(typed-map-new [:map :keyword :i64] " entries "))")]
    (is (re-find #"entry limit" (rejection-message source)))))

(deftest explicit-type-aliases-expand-before-hir-and-cannot-be-forged
  (let [source "(def person-type [:record :demo/person [[:age :i64]]])
                (defn age [person [:alias person-type]] :i64
                  (record-get person-type person :age))
                (defn main [] :i64
                  (age (record person-type 42)))"
        hir (frontend/analyze source)
        age (some #(when (= 'age (:name %)) %) (:functions hir))]
    (is (= 42 (oracle source)))
    (is (= [[:record :demo/person [[:age :i64]]]] (:param-types age))))
  (is (= 42
         (oracle "(def age-type :i64)
                  (def person-type [:record :demo/person [[:age [:alias age-type]]]])
                  (defn main [] [:alias age-type]
                    (record-get person-type (record person-type 42) :age))")))
  (doseq [source ["(defn main [] [:alias missing] 0)"
                  "(def not-a-type 7) (defn main [] [:alias not-a-type] 0)"
                  "(def a [:alias b]) (def b [:alias a]) (defn main [] 0)"]]
    (is (some? (rejection-message source)))))
