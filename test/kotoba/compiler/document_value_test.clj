(ns kotoba.compiler.document-value-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.backend.wasm-typed :as typed]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.value :as value]))

(def source
  "(ns document.value (:export [main type-name updated-count external-count]))
   (defn doc [] :document
     (document-map (keyword-from-string \"@context\") (document-string \"http://www.w3.org/ns/anno.jsonld\")
                   :type (document-string \"Annotation\")
                   :target (document-string \"urn:test\")
                   :enabled (document-bool true)))
   (defn type-name [] :string
     (option-value-of [:option :string]
       (document-string-value
         (option-value-of [:option :document]
           (document-get (doc) :type) (document-null)))
       \"\"))
   (defn updated-count [] :i64
     (document-count
       (document-dissoc
         (document-merge (doc)
           (document-map :creator (document-string \"alice\")))
         :enabled)))
   (defn external-count [value :document] :i64 (document-count value))
   (defn main [] :i64 (+ (updated-count) (document-count (doc))))")

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

(deftest documents-have-reference-script-and-real-wasm-parity
  (let [compiled (compiler/compile-source source :wasm32-browser-kotoba-v1)
        script (compiler/compile-source source :js-kotoba-v1)
        kir (:kir compiled)
        wasm-probe
        (node-probe compiled
                    (str "const x=h.instance.exports;"
                         "if(x.main()!==8n||x['type-name']()!=='Annotation'||x['updated-count']()!==4n)process.exit(2);"
                         "const d=h.typedValues.document(['map',[[':type',['string','External']]]]);"
                         "if(x['external-count'](d)!==1n)process.exit(3);"
                         "for(const bad of [Object.freeze(['map',Object.freeze([])]),"
                         "['map',[[':b',['null']],[':a',['null']]]],['f64',Infinity],{type:'Annotation'}]){"
                         "let rejected=false;try{x['external-count'](bad)}catch(e){rejected=true}"
                         "if(!rejected)process.exit(4);}"
                         "const cycle=['vector',[]];cycle[1].push(cycle);let rejected=false;"
                         "try{h.typedValues.document(cycle)}catch(e){rejected=true}if(!rejected)process.exit(5);"))
        js-probe (script-probe script
                               "if(x.main()!==8n||x['type-name']()!=='Annotation'||x['updated-count']()!==4n)process.exit(2);")]
    (is (= 8 (ir/execute kir 'main [])))
    (is (= "Annotation" (ir/execute kir 'type-name [])))
    (is (some #{:document} (typed/descriptor-table kir)))
    (is (= typed/document-abi-version (first (typed/metadata-bytes kir))))
    (is (zero? (:exit js-probe)) (:err js-probe))
    (is (zero? (:exit wasm-probe)) (:err wasm-probe))))

(deftest document-boundaries-reject-noncanonical-or-unbounded-values
  (testing "canonical map order, duplicate keys, finite floats, and cycles"
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-document! ["map" [[:b ["null"]] [:a ["null"]]]])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-document! ["map" [[:a ["null"]] [:a ["null"]]]])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-document! ["f64" Double/POSITIVE_INFINITY])))
    (let [cycle (java.util.ArrayList.)]
      ;; Host-object graphs are outside the Clojure reference representation.
      (is (thrown? clojure.lang.ExceptionInfo (value/bounded-document! cycle)))))
  (testing "container, node, and depth budgets"
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-document! ["vector" (vec (repeat 33 ["null"]))])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (value/bounded-document!
                  (reduce (fn [item _] ["vector" [item]]) ["null"] (range 9)))))))

(def vector-source
  "(ns document.vector (:export [main first-item changed tail missing bad-assoc bad-drop]))
   (defn main [] :i64 (first-item))
   (defn items [] :document (document-vector (document-i64 1) (document-i64 2)))
   (defn first-item [] :i64
     (option-value-of [:option :i64]
       (document-i64-value
         (option-value-of [:option :document]
           (document-vector-at (items) 0) (document-null))) -1))
   (defn changed [] :document
     (document-vector-conj
       (document-vector-assoc (items) 1 (document-i64 7))
       (document-i64 9)))
   (defn tail [] :document (document-vector-drop (changed) 1))
   (defn missing [] :bool
     (option-some?-of [:option :document] (document-vector-at (items) 9)))
   (defn bad-assoc [] :document (document-vector-assoc (items) -1 (document-null)))
   (defn bad-drop [] :document (document-vector-drop (items) 3))")

(deftest document-vector-operations-have-reference-script-and-real-wasm-parity
  (let [wasm (compiler/compile-source vector-source :wasm32-browser-kotoba-v1)
        script (compiler/compile-source vector-source :js-kotoba-v1)
        kir (:kir wasm)
        observe (fn [execute]
                  (is (= 1 (execute 'first-item)))
                  (is (= ["vector" [["i64" 7] ["i64" 9]]]
                         (execute 'tail)))
                  (is (false? (execute 'missing))))
        js-probe (script-probe
                  script
                  (str "if(x['first-item']()!==1n||x.missing()!==false)process.exit(2);"
                       "const t=x.tail();if(t[1].length!==2||t[1][0][1]!==7n||t[1][1][1]!==9n)process.exit(3);"
                       "for(const name of ['bad-assoc','bad-drop']){let rejected=false;try{x[name]()}catch(e){rejected=true}if(!rejected)process.exit(4)}"))
        wasm-probe (node-probe
                    wasm
                    (str "const x=h.instance.exports;if(x['first-item']()!==1n||x.missing()!==false)process.exit(2);"
                         "const t=x.tail();if(t[1].length!==2||t[1][0][1]!==7n||t[1][1][1]!==9n)process.exit(3);"
                         "for(const name of ['bad-assoc','bad-drop']){let rejected=false;try{x[name]()}catch(e){rejected=true}if(!rejected)process.exit(4)}"))]
    (observe #(ir/execute kir % []))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'bad-assoc [])))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'bad-drop [])))
    (is (zero? (:exit js-probe)) (:err js-probe))
    (is (zero? (:exit wasm-probe)) (:err wasm-probe))))
