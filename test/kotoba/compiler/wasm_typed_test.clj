(ns kotoba.compiler.wasm-typed-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.backend.wasm-typed :as typed]
            [kotoba.compiler.core :as compiler]))

(defn- node-probe [compiled javascript]
  (let [encoded (.encodeToString (java.util.Base64/getEncoder) ^bytes (:bytes compiled))
        probe (str "import('./runtime/browser-host.mjs').then(async m=>{"
                   "const h=await m.instantiateKotoba(Buffer.from(process.argv[1],'base64'));"
                   javascript "}).catch(e=>{console.error(e);process.exit(70)})")]
    (shell/sh "node" "--input-type=module" "-e" probe encoded)))

(def option-source
  "(defn main [] (match-option (option-some-of [:option :i64] 7)
                                [:option :i64]
                                (none 0)
                                (some value (+ value 1))))")

(deftest typed-metadata-is-versioned-deterministic-and-bounded
  (let [kir (:kir (compiler/compile-source option-source :js-kotoba-v1))
        table (typed/descriptor-table kir)
        bytes (typed/metadata-bytes kir)]
    (is (= typed/abi-version (first bytes)))
    (is (= bytes (typed/metadata-bytes kir)))
    (is (= (count table) (count (typed/descriptor-indices kir))))
    (is (empty? (typed/literal-table kir)))
    (is (some #{[:option :i64]} table))
    (is (every? #(<= 0 % 255) bytes))))

(deftest typed-custom-section-is-emitted-only-for-kir-v4
  (let [i64-kir (:kir (compiler/compile-source "(defn main [] 7)" :wasm32-kotoba-v1))
        typed-kir (assoc i64-kir :format :kotoba.kir/v4)
        typed-bytes (vec (map #(bit-and (int %) 0xff)
                              (wasm/emit typed-kir :wasm32-kotoba-v1)))
        i64-bytes (vec (map #(bit-and (int %) 0xff)
                            (wasm/emit i64-kir :wasm32-kotoba-v1)))
        marker (mapv int (.getBytes typed/custom-section-name "UTF-8"))]
    (testing "custom section identity is present in typed modules"
      (is (some #(= marker %) (partition (count marker) 1 typed-bytes))))
    (testing "legacy i64 modules do not acquire a typed ABI claim"
      (is (not-any? #(= marker %) (partition (count marker) 1 i64-bytes))))))

(deftest externref-boundaries-reject-forgery-and-cross-schema-substitution
  (let [source "(ns typed.boundary (:export [main make-i64 make-string read-i64]))
                (defn main [] 0)
                (defn make-i64 [] [:option :i64] (option-some-of [:option :i64] 7))
                (defn make-string [] [:option :string] (option-some-of [:option :string] \"bad\"))
                (defn read-i64 [value [:option :i64]] :i64
                  (match-option value [:option :i64] (none 0) (some item item)))"
        compiled (compiler/compile-source source :wasm32-kotoba-v1)
        probe (node-probe
               compiled
               (str "const x=h.instance.exports;"
                    "if(x['read-i64'](x['make-i64']())!==7n)process.exit(2);"
                    "for(const forged of ["
                    "Object.freeze([Object.freeze(['option','i64']),true,7n]),"
                    "x['make-string']()]){let rejected=false;try{x['read-i64'](forged)}catch(e){rejected=true}"
                    "if(!rejected)process.exit(3)}"))]
    (is (= :kotoba.value/typed-v1 (:value-profile compiled)))
    (is (= :kotoba.typed/externref-v1 (:value-abi compiled)))
    (is (= #{:reference-types} (:wasm-features compiled)))
    (is (zero? (:exit probe)) (:err probe))))

(deftest unqualified-typed-wasm-operations-fail-during-lowering
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"typed Wasm operation is not qualified"
       (compiler/compile-source
        "(defn main [] [:vector [:i64]]
           (hetero-vector-assoc [:vector [:i64]]
             (hetero-vector [:vector [:i64]] 1) 0 2))"
        :wasm32-kotoba-v1))))
