(ns kotoba.compiler.f64-value-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.backend.wasm-typed :as typed]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.value :as value]))

(def source
  (str "(ns pilot.f64 (:export [bits from-bits negative-zero one nan-bits infinity])) "
       "(defn bits [value :f64] (f64-to-bits value)) "
       "(defn from-bits [value :i64] :f64 (f64-from-bits value)) "
       "(defn negative-zero [] :f64 -0.0) "
       "(defn one [] :f64 1.0) "
       "(defn nan-bits [] (f64-to-bits ##NaN)) "
       "(defn infinity [] :f64 ##Inf)"))

(defn- node-run [javascript]
  (shell/sh "node" "--input-type=module" "-e" javascript))

(deftest f64-reference-semantics-preserve-special-values-and-bits
  (let [hir (frontend/analyze source)
        kir (ir/lower hir)]
    (is (= :kotoba.hir/v3 (:format hir)))
    (is (= :kotoba.kir/v4 (:format kir)))
    (is (= 4609434218613702656 (ir/execute kir 'bits [1.5])))
    (is (= Long/MIN_VALUE (ir/execute kir 'bits [-0.0])))
    (is (= 1.0 (ir/execute kir 'one [])))
    (is (= 9221120237041090560 (ir/execute kir 'nan-bits [])))
    (is (Double/isInfinite ^double (ir/execute kir 'infinity [])))
    (is (Double/isNaN ^double (ir/execute kir 'from-bits [9221120237041090560])))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'bits [1])))))

(deftest f64-wasm-abi-and-reinterpretation-match-reference
  (let [artifact (compiler/compile-source source :wasm32-kotoba-v1)
        encoded (.encodeToString (java.util.Base64/getEncoder) (:bytes artifact))
        js (str "const bytes=Buffer.from('" encoded "','base64');"
                "WebAssembly.instantiate(bytes,{}).then(({instance})=>{const x=instance.exports;"
                "if(x.bits(1.5)!==4609434218613702656n)process.exit(2);"
                "if(x.bits(-0)!==-9223372036854775808n)process.exit(3);"
                "if(x['nan-bits']()!==9221120237041090560n)process.exit(4);"
                "if(!Object.is(x['negative-zero'](),-0))process.exit(5);"
                "if(x.one()!==1)process.exit(6);"
                "if(x.infinity()!==Infinity)process.exit(7);"
                "if(!Number.isNaN(x['from-bits'](9221120237041090560n)))process.exit(8);"
                "console.log('wasm-f64-ok')})")
        result (node-run js)]
    (is (= :kotoba.typed/mixed-f64-v2 (:value-abi artifact)))
    (is (= #{:ieee-754-f64} (:wasm-features artifact)))
    (is (zero? (:exit result)) (:err result))
    (is (= "wasm-f64-ok\n" (:out result)))))

(deftest f64-kotoba-script-matches-reference
  (let [artifact (compiler/compile-source source :js-kotoba-v1)
        encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source artifact) "UTF-8"))
        js (str "import('data:text/javascript;base64," encoded
                "').then(m=>{const x=m.instantiateKotoba({});"
                "if(x.bits(1.5)!==4609434218613702656n)process.exit(2);"
                "if(x.bits(-0)!==-9223372036854775808n)process.exit(3);"
                "if(x['nan-bits']()!==9221120237041090560n)process.exit(4);"
                "if(!Object.is(x['negative-zero'](),-0))process.exit(5);"
                "if(x.one()!==1||x.infinity()!==Infinity)process.exit(6);"
                "console.log('js-f64-ok')})")
        result (node-run js)]
    (is (zero? (:exit result)) (:err result))
    (is (= "js-f64-ok\n" (:out result)))
    (is (= :kotoba.value/typed-v1 (:value-profile artifact)))
    (is (= :kotoba.typed/mixed-f64-v2
           (get-in artifact [:compatibility :value-abi])))))

(deftest bounded-decimal-f64-has-reference-script-and-browser-wasm-parity
  (let [decimal-source
        "(ns decimal.f64 (:export [main parse]))
         (defn main [] :i64 0)
         (defn parse [input :string] [:option :f64] (decimal-f64-parse input))"
        js-artifact (compiler/compile-source decimal-source :js-kotoba-v1)
        wasm-artifact (compiler/compile-source decimal-source :wasm32-browser-kotoba-v1)
        kir (:kir wasm-artifact)
        js-encoded (.encodeToString (java.util.Base64/getEncoder)
                                    (.getBytes ^String (:source js-artifact) "UTF-8"))
        wasm-encoded (.encodeToString (java.util.Base64/getEncoder) (:bytes wasm-artifact))
        assertions
        (str "const valid=[['0',0],['-0',-0],['+1.5',1.5],['.5',.5],['1.',1],"
             "['1e-324',0],['5e-324',Number.MIN_VALUE],['1.7976931348623157e308',Number.MAX_VALUE]];"
             "for(const [s,want] of valid){const v=x.parse(s);if(!v[1]||!Object.is(v[2],want))process.exit(2)}"
             "for(const s of ['', ' ', 'NaN', 'Infinity', '0x10', '1_000', '1e0000', '1e309', '1'.repeat(65)])"
             "{if(x.parse(s)[1])process.exit(3)}")
        js-result (node-run
                   (str "import('data:text/javascript;base64," js-encoded
                        "').then(m=>{const x=m.instantiateKotoba({});" assertions "})"))
        wasm-result (node-run
                     (str "import('./runtime/browser-host.mjs').then(async m=>{"
                          "const h=await m.instantiateKotoba(Buffer.from('" wasm-encoded "','base64'));"
                          "const x=h.instance.exports;" assertions "})"))]
    (is (= [[:option :f64] true 1.5] (ir/execute kir 'parse ["+1.5"])))
    (is (= [[:option :f64] false] (ir/execute kir 'parse ["1e309"])))
    (is (= Long/MIN_VALUE
           (value/f64-to-i64-bits
            (nth (ir/execute kir 'parse ["-0"]) 2))))
    (is (zero? (:exit js-result)) (:err js-result))
    (is (zero? (:exit wasm-result)) (:err wasm-result))))

(deftest bounded-decimal-f64x3-is-fixed-width-atomic-and-cross-target
  (let [source "(ns decimal.f64x3 (:export [main parse]))
                (defn main [] :i64 0)
                (defn parse [input :string] [:option [:vector [:f64 :f64 :f64]]]
                  (decimal-f64x3-parse input))"
        js-artifact (compiler/compile-source source :js-kotoba-v1)
        wasm-artifact (compiler/compile-source source :wasm32-browser-kotoba-v1)
        kir (:kir wasm-artifact)
        js64 (.encodeToString (java.util.Base64/getEncoder)
                              (.getBytes ^String (:source js-artifact) "UTF-8"))
        wasm64 (.encodeToString (java.util.Base64/getEncoder) (:bytes wasm-artifact))
        assertions
        (str "const good=x.parse(' -0  1.5\\t5e-324 ');"
             "if(!good[1]||!Object.is(good[2][1],-0)||good[2][2]!==1.5||good[2][3]!==Number.MIN_VALUE)process.exit(2);"
             "for(const s of ['', '1 2', '1 2 3 4', '1,2,3', '1 NaN 3', '1 1e309 3', '1　2　3', ' '.repeat(195)])"
             "if(x.parse(s)[1])process.exit(3);")
        js-result (node-run
                   (str "import('data:text/javascript;base64," js64
                        "').then(m=>{const x=m.instantiateKotoba({});" assertions "})"))
        wasm-result (node-run
                     (str "import('./runtime/browser-host.mjs').then(async m=>{"
                          "const h=await m.instantiateKotoba(Buffer.from('" wasm64 "','base64'));"
                          "const x=h.instance.exports;" assertions "})"))]
    (is (= [[:option [:vector [:f64 :f64 :f64]]] true
            [[:vector [:f64 :f64 :f64]] -0.0 1.5 Double/MIN_VALUE]]
           (ir/execute kir 'parse [" -0  1.5\t5e-324 "])))
    (is (= [[:option [:vector [:f64 :f64 :f64]]] false]
           (ir/execute kir 'parse ["1 2"])))
    (is (zero? (:exit js-result)) (:err js-result))
    (is (zero? (:exit wasm-result)) (:err wasm-result))))

(deftest parsed-f64x3-payloads-compose-into-nominal-records
  (let [source "(ns decimal.pose (:export [main pose]))
                (defn main [] :i64 0)
                (defn pose [xyz-text :string rpy-text :string]
                  [:option [:record :geometry/pose
                            [[:xyz [:vector [:f64 :f64 :f64]]]
                             [:rpy [:vector [:f64 :f64 :f64]]]]]]
                  (match-option (decimal-f64x3-parse xyz-text)
                    [:option [:vector [:f64 :f64 :f64]]]
                    (none (option-none-of [:option [:record :geometry/pose
                                                    [[:xyz [:vector [:f64 :f64 :f64]]]
                                                     [:rpy [:vector [:f64 :f64 :f64]]]]]]))
                    (some xyz
                      (match-option (decimal-f64x3-parse rpy-text)
                        [:option [:vector [:f64 :f64 :f64]]]
                        (none (option-none-of [:option [:record :geometry/pose
                                                        [[:xyz [:vector [:f64 :f64 :f64]]]
                                                         [:rpy [:vector [:f64 :f64 :f64]]]]]]))
                        (some rpy
                          (option-some-of [:option [:record :geometry/pose
                                                   [[:xyz [:vector [:f64 :f64 :f64]]]
                                                    [:rpy [:vector [:f64 :f64 :f64]]]]]]
                            (record [:record :geometry/pose
                                     [[:xyz [:vector [:f64 :f64 :f64]]]
                                      [:rpy [:vector [:f64 :f64 :f64]]]]]
                              xyz rpy)))))))"
        artifact (compiler/compile-source source :wasm32-browser-kotoba-v1)
        encoded (.encodeToString (java.util.Base64/getEncoder) (:bytes artifact))
        result (node-run
                (str "import('./runtime/browser-host.mjs').then(async m=>{"
                     "const h=await m.instantiateKotoba(Buffer.from('" encoded "','base64'));"
                     "const v=h.instance.exports.pose('1 2 3','4 5 6');"
                     "if(!v[1]||JSON.stringify(v[2][1].slice(1))!=='[1,2,3]'||JSON.stringify(v[2][2].slice(1))!=='[4,5,6]')process.exit(2)})"
                     ".catch(e=>{console.error(e);process.exit(70)})"))]
    (is (zero? (:exit result)) (:err result))))

(deftest f64-native-targets-fail-closed
  (testing "f64 is not silently lowered through the i64 native ABI"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"floating-point values require"
                          (compiler/compile-source source :x86_64-kotoba-v1)))))

(deftest f64-comparison-used-only-as-control-flow-is-sealed-for-typed-wasm
  (let [conditional-source
        (str "(ns pilot.f64-condition (:export [main classify])) "
             "(defn main [] :i64 0) "
             "(defn classify [x :f64] :f64 "
             "  (if (f64-lt x 25.0) 0.0 (if (f64-lt x 60.0) 1.0 2.0)))")
        artifact (compiler/compile-source conditional-source :wasm32-browser-kotoba-v1)
        encoded (.encodeToString (java.util.Base64/getEncoder) (:bytes artifact))
        result (node-run
                (str "import('./runtime/browser-host.mjs').then(async m=>{"
                     "const h=await m.instantiateKotoba(Buffer.from('" encoded "','base64'));"
                     "const f=h.instance.exports.classify;"
                     "if(f(10)!==0||f(25)!==1||f(60)!==2)process.exit(2);"
                     "console.log('wasm-f64-condition-ok')})"))]
    (is (some #{:bool} (typed/descriptor-table (:kir artifact))))
    (is (zero? (:exit result)) (:err result))
    (is (= "wasm-f64-condition-ok\n" (:out result)))))

(def arithmetic-source
  (str "(ns pilot.f64-arithmetic (:export [main add divide neg absolute equal less unordered nan-bits payload-bits to-f64 rounded to-i64 truncating])) "
       "(defn main [] 0) "
       "(defn add [x :f64 y :f64] :f64 (f64-add x y)) "
       "(defn divide [x :f64 y :f64] :f64 (f64-div x y)) "
       "(defn neg [x :f64] :f64 (f64-neg x)) "
       "(defn absolute [x :f64] :f64 (f64-abs x)) "
       "(defn equal [x :f64 y :f64] :bool (f64-eq x y)) "
       "(defn less [x :f64 y :f64] :bool (f64-lt x y)) "
       "(defn unordered [x :f64 y :f64] :bool (f64-unordered x y)) "
       "(defn nan-bits [] (f64-to-bits (f64-div 0.0 0.0))) "
       "(defn payload-bits [] (f64-to-bits (f64-from-bits 9218868437227405313))) "
       "(defn to-f64 [x :i64] :f64 (i64-to-f64-checked x)) "
       "(defn rounded [x :i64] :f64 (i64-to-f64-rounded x)) "
       "(defn to-i64 [x :f64] :i64 (f64-to-i64-checked x)) "
       "(defn truncating [x :f64] :i64 (f64-to-i64-truncating x))"))

(deftest f64-arithmetic-special-value-matrix-matches-reference-js-and-wasm
  (let [kir (:kir (compiler/compile-source arithmetic-source :js-kotoba-v1))
        js-artifact (compiler/compile-source arithmetic-source :js-kotoba-v1)
        wasm-artifact (compiler/compile-source arithmetic-source :wasm32-browser-kotoba-v1)
        js-encoded (.encodeToString (java.util.Base64/getEncoder)
                                    (.getBytes ^String (:source js-artifact) "UTF-8"))
        wasm-encoded (.encodeToString (java.util.Base64/getEncoder) (:bytes wasm-artifact))
        assertions (str "const check=x=>{"
                        "if(x.add(0.1,0.2)!==0.30000000000000004)process.exit(2);"
                        "if(x.divide(1,0)!==Infinity||!Number.isNaN(x.divide(0,0)))process.exit(3);"
                        "if(!Object.is(x.neg(0),-0)||!Object.is(x.absolute(-0),0))process.exit(4);"
                        "if(!x.equal(0,-0)||x.equal(NaN,NaN)||!x.less(-1,0))process.exit(5);"
                        "if(!x.unordered(NaN,1)||x.unordered(1,2))process.exit(6);"
                        "if(x['nan-bits']()!==9221120237041090560n||"
                        "x['payload-bits']()!==9221120237041090560n)process.exit(7);"
                        "if(x['to-f64'](9007199254740992n)!==9007199254740992)process.exit(8);"
                        "try{x['to-f64'](9007199254740993n);process.exit(9)}catch(e){}"
                        "if(x.rounded(9007199254740993n)!==9007199254740992)process.exit(10);"
                        "if(x['to-i64'](-0)!==0n||x['to-i64'](42)!==42n)process.exit(11);"
                        "for(const v of [1.5,NaN,Infinity,9223372036854775808])"
                        "{try{x['to-i64'](v);process.exit(12)}catch(e){}}"
                        "if(x.truncating(1.9)!==1n||x.truncating(-1.9)!==-1n)process.exit(13);"
                        "for(const v of [NaN,Infinity,-Infinity,9223372036854775808])"
                        "{try{x.truncating(v);process.exit(14)}catch(e){}}};")
        js-result (node-run
                   (str "import('data:text/javascript;base64," js-encoded
                        "').then(m=>{" assertions "check(m.instantiateKotoba({}));})"))
        wasm-result (node-run
                     (str "import('./runtime/browser-host.mjs').then(async m=>{"
                          "const h=await m.instantiateKotoba(Buffer.from('" wasm-encoded "','base64'));"
                          assertions "check(h.instance.exports);})"))]
    (is (= 9221120237041090560 (ir/execute kir 'nan-bits [])))
    (is (= 9221120237041090560 (ir/execute kir 'payload-bits [])))
    (is (true? (ir/execute kir 'unordered [Double/NaN 1.0])))
    (is (false? (ir/execute kir 'equal [Double/NaN Double/NaN])))
    (is (= 9007199254740992.0 (ir/execute kir 'to-f64 [9007199254740992])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/execute kir 'to-f64 [9007199254740993])))
    (is (= 1 (ir/execute kir 'truncating [1.9])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/execute kir 'to-i64 [1.5])))
    (is (zero? (:exit js-result)) (:err js-result))
    (is (zero? (:exit wasm-result)) (:err wasm-result))))
