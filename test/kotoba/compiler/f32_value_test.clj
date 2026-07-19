(ns kotoba.compiler.f32-value-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.backend.wasm-typed :as typed]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]))

(def source
  (str "(ns pilot.f32 (:export [main from-bits bits rounded widen add divide unordered to-f32 rounded-i64 to-i64 truncating f32-s f32-lo f32-hi f64-s f64-lo f64-hi sin cos wide-sin wide-cos exp log])) "
       "(defn main [] 0) "
       "(defn from-bits [x :i64] :f32 (f32-from-bits x)) "
       "(defn bits [x :f32] :i64 (f32-to-bits x)) "
       "(defn rounded [x :f64] :f32 (f64-to-f32-rounded x)) "
       "(defn widen [x :f32] :f64 (f32-to-f64-exact x)) "
       "(defn add [x :f32 y :f32] :f32 (f32-add x y)) "
       "(defn divide [x :f32 y :f32] :f32 (f32-div x y)) "
       "(defn unordered [x :f32 y :f32] :bool (f32-unordered x y)) "
       "(defn to-f32 [x :i64] :f32 (i64-to-f32-checked x)) "
       "(defn rounded-i64 [x :i64] :f32 (i64-to-f32-rounded x)) "
       "(defn to-i64 [x :f32] :i64 (f32-to-i64-checked x)) "
       "(defn truncating [x :f32] :i64 (f32-to-i64-truncating x)) "
       "(defn f32-s [x :f32] :f32 (f32-sqrt x)) "
       "(defn f32-lo [x :f32 y :f32] :f32 (f32-min x y)) "
       "(defn f32-hi [x :f32 y :f32] :f32 (f32-max x y)) "
       "(defn f64-s [x :f64] :f64 (f64-sqrt x)) "
       "(defn f64-lo [x :f64 y :f64] :f64 (f64-min x y)) "
       "(defn f64-hi [x :f64 y :f64] :f64 (f64-max x y)) "
       "(defn sin [x :f64] :f64 (f64-sin-quarter-turn x)) "
       "(defn cos [x :f64] :f64 (f64-cos-quarter-turn x)) "
       "(defn wide-sin [x :f64] :f64 (f64-sin-bounded x)) "
       "(defn wide-cos [x :f64] :f64 (f64-cos-bounded x)) "
       "(defn exp [x :f64] :f64 (f64-exp-near-zero x)) "
       "(defn log [x :f64] :f64 (f64-log-near-one x))"))

(defn- node-run [javascript]
  (shell/sh "node" "--input-type=module" "-e" javascript))

(def assertions
  (str "const one=x['from-bits'](1065353216n),negz=x['from-bits'](-2147483648n),"
       "nan=x['from-bits'](2143289344n),tenth=x.rounded(0.1);"
       "if(one!==1||!Object.is(negz,-0)||x.bits(nan)!==2143289344n)process.exit(2);"
       "if(x.bits(tenth)!==1036831949n||x.widen(tenth)!==0.10000000149011612)process.exit(3);"
       "if(x.bits(x.add(one,tenth))!==1066192077n)process.exit(4);"
       "if(x.divide(one,x['from-bits'](0n))!==Infinity||!x.unordered(nan,one))process.exit(5);"
       "if(x['to-f32'](16777216n)!==16777216)process.exit(6);"
       "try{x['to-f32'](16777217n);process.exit(7)}catch(e){}"
       "if(x['rounded-i64'](16777217n)!==16777216)process.exit(8);"
       "if(x['to-i64'](one)!==1n||x.truncating(x.rounded(1.9))!==1n)process.exit(9);"
       "try{x['to-i64'](tenth);process.exit(10)}catch(e){}"
       "if(x['f32-s'](Math.fround(4))!==2||!Number.isNaN(x['f32-s'](Math.fround(-1))))process.exit(11);"
       "if(!Object.is(x['f32-lo'](0,-0),-0)||!Object.is(x['f32-hi'](0,-0),0))process.exit(12);"
       "if(!Number.isNaN(x['f32-lo'](NaN,0))||!Number.isNaN(x['f32-hi'](0,NaN)))process.exit(13);"
       "if(x['f64-s'](4)!==2||!Number.isNaN(x['f64-s'](-1)))process.exit(14);"
       "if(!Object.is(x['f64-lo'](0,-0),-0)||!Object.is(x['f64-hi'](0,-0),0))process.exit(15);"
       "if(!Number.isNaN(x['f64-lo'](NaN,0))||!Number.isNaN(x['f64-hi'](0,NaN)))process.exit(16);"
       "const q=Math.PI/4;if(x.sin(q)!==0.7071067811865475||x.cos(q)!==0.7071067811865476)process.exit(17);"
       "if(!Object.is(x.sin(-0),-0)||x.cos(-0)!==1)process.exit(18);"
       "for(let i=0;i<=64;i++){const v=-q+2*q*i/64;"
       "if(Math.abs(x.sin(v)-Math.sin(v))>4e-15||Math.abs(x.cos(v)-Math.cos(v))>4e-15)process.exit(19);}"
       "for(const v of [NaN,Infinity,-Infinity,q+Number.EPSILON]){for(const f of [x.sin,x.cos])"
       "{try{f(v);process.exit(20)}catch(e){}}}"
       "const limit=8192*Math.PI;for(let i=0;i<=64;i++){const v=-limit+2*limit*i/64;"
       "if(Math.abs(x['wide-sin'](v)-Math.sin(v))>5e-12||Math.abs(x['wide-cos'](v)-Math.cos(v))>5e-12)process.exit(21);}"
       "if(!Object.is(x['wide-sin'](-0),-0)||x['wide-cos'](-0)!==1)process.exit(22);"
       "for(const v of [NaN,Infinity,-Infinity,limit+Number.EPSILON*limit]){"
       "for(const f of [x['wide-sin'],x['wide-cos']]){try{f(v);process.exit(23)}catch(e){}}}"
       "for(let i=0;i<=64;i++){const v=-0.5+i/64;if(Math.abs(x.exp(v)-Math.exp(v))>4e-15)process.exit(24);}"
       "for(let i=0;i<=64;i++){const v=0.75+0.75*i/64;if(Math.abs(x.log(v)-Math.log(v))>4e-15)process.exit(25);}"
       "for(const [f,vals] of [[x.exp,[NaN,Infinity,-Infinity,0.5000000000000001]],"
       "[x.log,[NaN,Infinity,-Infinity,0,0.7499999999999999,1.5000000000000002]]]){"
       "for(const v of vals){try{f(v);process.exit(26)}catch(e){}}}"))

(deftest f32-reference-js-and-wasm-share-the-sealed-profile
  (let [js-artifact (compiler/compile-source source :js-kotoba-v1)
        wasm-artifact (compiler/compile-source source :wasm32-browser-kotoba-v1)
        kir (:kir js-artifact)
        js-encoded (.encodeToString (java.util.Base64/getEncoder)
                                    (.getBytes ^String (:source js-artifact) "UTF-8"))
        wasm-encoded (.encodeToString (java.util.Base64/getEncoder) (:bytes wasm-artifact))
        js-result (node-run (str "import('data:text/javascript;base64," js-encoded
                                 "').then(m=>{const x=m.instantiateKotoba({});" assertions "})"))
        wasm-result (node-run
                     (str "import('./runtime/browser-host.mjs').then(async m=>{"
                          "const h=await m.instantiateKotoba(Buffer.from('" wasm-encoded "','base64'));"
                          "const x=h.instance.exports;" assertions "})"))]
    (is (= 1036831949 (ir/execute kir 'bits [(float 0.1)])))
    (is (= 2143289344 (ir/execute kir 'bits [Float/NaN])))
    (is (= 1066192077
           (ir/execute kir 'bits [(ir/execute kir 'add [(float 1.0) (float 0.1)])])))
    (is (Float/isInfinite ^float (ir/execute kir 'divide [(float 1.0) (float 0.0)])))
    (is (true? (ir/execute kir 'unordered [Float/NaN (float 1.0)])))
    (is (= (float 2.0) (ir/execute kir 'f32-s [(float 4.0)])))
    (is (Float/isNaN ^float (ir/execute kir 'f32-s [(float -1.0)])))
    (is (= Integer/MIN_VALUE
           (Float/floatToIntBits
            ^float (ir/execute kir 'f32-lo [(float 0.0) (Float/intBitsToFloat Integer/MIN_VALUE)]))))
    (is (Double/isNaN ^double (ir/execute kir 'f64-s [-1.0])))
    (is (= 4604544271217802188
           (Double/doubleToLongBits ^double (ir/execute kir 'sin [(/ Math/PI 4.0)]))))
    (is (= 4604544271217802189
           (Double/doubleToLongBits ^double (ir/execute kir 'cos [(/ Math/PI 4.0)]))))
    (is (= Long/MIN_VALUE
           (Double/doubleToLongBits ^double (ir/execute kir 'sin [-0.0]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/execute kir 'sin [(Math/nextUp (/ Math/PI 4.0))])))
    (is (< (Math/abs (- (ir/execute kir 'wide-sin [(* 4096.0 Math/PI)])
                        (Math/sin (* 4096.0 Math/PI))))
           5.0e-12))
    (is (= Long/MIN_VALUE
           (Double/doubleToLongBits ^double (ir/execute kir 'wide-sin [-0.0]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/execute kir 'wide-cos [(Math/nextUp (* 8192.0 Math/PI))])))
    (is (< (Math/abs (- (ir/execute kir 'exp [0.5]) (Math/exp 0.5))) 4.0e-15))
    (is (< (Math/abs (- (ir/execute kir 'log [1.5]) (Math/log 1.5))) 4.0e-15))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'exp [(Math/nextUp 0.5)])))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'log [(Math/nextDown 0.75)])))
    (is (= 16777216.0 (double (ir/execute kir 'rounded-i64 [16777217]))))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'to-f32 [16777217])))
    (is (= :kotoba.typed/mixed-f32-f64-v3 (:value-abi wasm-artifact)))
    (is (= #{:ieee-754-f32 :ieee-754-f64 :reference-types} (:wasm-features wasm-artifact)))
    (is (= 4 typed/abi-version))
    (is (some #{:f32} (typed/descriptor-table (:kir wasm-artifact))))
    (is (zero? (:exit js-result)) (:err js-result))
    (is (zero? (:exit wasm-result)) (:err wasm-result))))

(deftest f32-fails-closed-on-native-and-inside-structured-values
  (testing "native targets never reinterpret f32 through i64"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"floating-point values require"
                          (compiler/compile-source source :x86_64-kotoba-v1))))
  (testing "the initial profile remains scalar-only"
    (is (thrown? clojure.lang.ExceptionInfo
                 (compiler/compile-source
                  "(ns bad) (defn main [x [:option :f32]] :i64 0)"
                  :wasm32-kotoba-v1)))))
