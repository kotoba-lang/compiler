(ns kotoba.compiler.f64-value-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]))

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

(deftest f64-native-targets-fail-closed
  (testing "f64 is not silently lowered through the i64 native ABI"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"f64 values require"
                          (compiler/compile-source source :x86_64-kotoba-v1)))))
