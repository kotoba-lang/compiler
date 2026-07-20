(ns kotoba.compiler.compact-graph-value-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.backend.wasm-typed :as typed]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.value :as value]))

(def source
  "(ns compact.graph (:export [main index-size present missing joined-size cycle external external-ds-count]))
   (defn index [] :string-index
     (string-index-assoc (string-index-assoc (string-index-new) \"joint-b\" 2)
                         \"joint-a\" 1))
   (defn index-size [] :i64 (string-index-count (index)))
   (defn present [] :i64
     (option-value-of [:option :i64] (string-index-get (index) \"joint-b\") 99))
   (defn missing [] :i64
     (option-value-of [:option :i64] (string-index-get (index) \"missing\") 99))
   (defn external [value :string-index] :i64
     (option-value-of [:option :i64] (string-index-get value \"external\") 99))
   (defn joined [] :disjoint-set-i64
     (option-value-of [:option :disjoint-set-i64]
       (disjoint-set-i64-union (disjoint-set-i64-new 3) 0 2)
       (disjoint-set-i64-new 0)))
   (defn joined-size [] :i64 (disjoint-set-i64-count (joined)))
   (defn external-ds-count [value :disjoint-set-i64] :i64 (disjoint-set-i64-count value))
   (defn cycle [] :i64
     (if (option-some?-of [:option :disjoint-set-i64]
           (disjoint-set-i64-union (joined) 2 0)) 1 0))
   (defn main [] :i64 (+ (index-size) (+ (present) (+ (missing) (joined-size)))))")

(defn- node-probe [compiled javascript]
  (let [encoded (.encodeToString (java.util.Base64/getEncoder) ^bytes (:bytes compiled))]
    (shell/sh "node" "--input-type=module" "-e"
              (str "import('./runtime/browser-host.mjs').then(async m=>{"
                   "const h=await m.instantiateKotoba(Buffer.from(process.argv[1],'base64'));"
                   javascript "}).catch(e=>{console.error(e);process.exit(70)})") encoded)))

(defn- script-probe [compiled javascript]
  (let [encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))]
    (shell/sh "node" "--input-type=module" "-e"
              (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const x=m.instantiateKotoba({});" javascript
                   "}).catch(e=>{console.error(e);process.exit(70)})"))))

(deftest compact-graph-values-have-reference-and-real-wasm-parity
  (let [compiled (compiler/compile-source source :wasm32-browser-kotoba-v1)
        script (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)
        probe (node-probe compiled
                          (str "const x=h.instance.exports;"
                               "if(x.main()!==106n||x['index-size']()!==2n||x.present()!==2n||"
                               "x.missing()!==99n||x['joined-size']()!==3n||x.cycle()!==0n)process.exit(2);"
                               "const i=h.typedValues.stringIndex([['external',7n]]),d=h.typedValues.disjointSetI64(5n);"
                               "if(x.external(i)!==7n||x['external-ds-count'](d)!==5n)process.exit(3);"
                               "for(const forged of [Object.freeze([i[0],i[1]]),Object.freeze([d[0],d[1],d[2]])]){"
                               "let rejected=false;try{forged.length===2?x.external(forged):x['external-ds-count'](forged)}catch(e){rejected=true}"
                               "if(!rejected)process.exit(4);}"))
        js-probe (script-probe script
                               (str "if(x.main()!==106n||x['index-size']()!==2n||x.present()!==2n||"
                                    "x.missing()!==99n||x['joined-size']()!==3n||x.cycle()!==0n)process.exit(2);"))]
    (is (= 106 (ir/execute kir 'main [])))
    (is (= 0 (ir/execute kir 'cycle [])))
    (is (some #{:string-index} (typed/descriptor-table kir)))
    (is (some #{:disjoint-set-i64} (typed/descriptor-table kir)))
    (is (= typed/compact-graph-abi-version (first (typed/metadata-bytes kir))))
    (is (zero? (:exit js-probe)) (:err js-probe))
    (is (zero? (:exit probe)) (:err probe))))

(deftest compact-values-enforce-canonical-bounds
  (testing "string indexes reject duplicates, non-canonical order, and aggregate overflow"
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-string-index! [["a" 1] ["a" 2]])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-string-index! [["b" 1] ["a" 2]])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-string-index!
                  (mapv (fn [index] [(str (apply str (repeat 512 "x")) index) index])
                        (range 128))))))
  (testing "disjoint sets reject out-of-range parents and parent cycles"
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-disjoint-set-i64! [[1 0] [0 0]])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-disjoint-set-i64! [[0 2] [0 0]])))))

(deftest compact-constructors-reject-runtime-overflow-and-invalid-indexes
  (let [hir (:hir (compiler/check-source
                   "(ns compact.bad (:export [oversize bad-index]))
                    (defn oversize [] :disjoint-set-i64 (disjoint-set-i64-new 129))
                    (defn bad-index [] [:option :disjoint-set-i64]
                      (disjoint-set-i64-union (disjoint-set-i64-new 2) 0 2))"))
        kir (ir/lower hir)]
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'oversize [])))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'bad-index [])))))
