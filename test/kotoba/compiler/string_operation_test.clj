(ns kotoba.compiler.string-operation-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]))

(def source
  "(ns string.operation (:export [main concat substring replace]))
   (defn main [] :i64 0)
   (defn concat [left :string right :string] :string
     (string-concat left right))
   (defn substring [value :string start :i64 end :i64] :string
     (string-substring value start end))
   (defn replace [value :string needle :string replacement :string] :string
     (string-replace-all value needle replacement))")

(defn- encoded [bytes]
  (.encodeToString (java.util.Base64/getEncoder) bytes))

(deftest bounded-literal-string-replacement-has-cross-target-conformance
  (let [javascript (compiler/compile-source source :js-kotoba-v1)
        wasm (compiler/compile-source source :wasm32-browser-kotoba-v1)
        kir (:kir wasm)
        js64 (encoded (.getBytes ^String (:source javascript) "UTF-8"))
        wasm64 (encoded (:bytes wasm))
        checks (str "if(x.concat('a','b')!=='ab')process.exit(2);"
                    "if(x.substring('a😀語z',1n,8n)!=='😀語')process.exit(6);"
                    "try{x.substring('a😀',2n,5n);process.exit(7)}catch(e){};"
                    "if(x.replace('a.$a.$','.','$')!=='a$$a$$')process.exit(3);"
                    "try{x.replace('abc','','x');process.exit(4)}catch(e){};"
                    "try{x.replace('x'.repeat(40000),'x','xx');process.exit(5)}catch(e){}")
        js-result (shell/sh "node" "--input-type=module" "-e"
                            (str "import('data:text/javascript;base64," js64
                                 "').then(m=>{const x=m.instantiateKotoba({});" checks "})"))
        wasm-result (shell/sh "node" "--input-type=module" "-e"
                              (str "import('./runtime/browser-host.mjs').then(async m=>{"
                                   "const h=await m.instantiateKotoba(Buffer.from(process.argv[1],'base64'));"
                                   "const x=h.instance.exports;" checks "})") wasm64)]
    (is (= "ab" (ir/execute kir 'concat ["a" "b"])))
    (is (= "😀語" (ir/execute kir 'substring ["a😀語z" 1 8])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/execute kir 'substring ["a😀" 2 5])))
    (is (= "a$$a$$" (ir/execute kir 'replace ["a.$a.$" "." "$"])))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'replace ["abc" "" "x"])))
    (is (zero? (:exit js-result)) (:err js-result))
    (is (zero? (:exit wasm-result)) (:err wasm-result))))

;; ADR 0050: mineralplant.governor (cloud-itonami-isco-8114) needs a
;; case-folded substring search over free text for its defense-in-depth
;; scope-exclusion check -- these two primitives compose to provide that:
;; `(string-contains? (string-fold-case haystack) (string-fold-case needle))`.
(def search-source
  "(ns string.search (:export [main contains fold contains-fold]))
   (defn main [] :i64 0)
   (defn contains [haystack :string needle :string] :i64
     (string-contains? haystack needle))
   (defn fold [value :string] :string
     (string-fold-case value))
   (defn contains-fold [haystack :string needle :string] :i64
     (string-contains? (string-fold-case haystack) (string-fold-case needle)))")

(deftest bounded-string-search-and-case-fold-have-cross-target-conformance
  (let [javascript (compiler/compile-source search-source :js-kotoba-v1)
        wasm (compiler/compile-source search-source :wasm32-browser-kotoba-v1)
        kir (:kir wasm)
        js64 (encoded (.getBytes ^String (:source javascript) "UTF-8"))
        wasm64 (encoded (:bytes wasm))
        checks (str "if(x.contains('final decision made','decision')!==1n)process.exit(2);"
                    "if(x.contains('final decision made','banana')!==0n)process.exit(3);"
                    "try{x.contains('abc','');process.exit(4)}catch(e){};"
                    "try{x.contains('','');process.exit(5)}catch(e){};"
                    "if(x.contains('x'.repeat(40000),'y'.repeat(30000))!==0n)process.exit(6);"
                    "if(x.fold('FINAL DECISION')!=='final decision')process.exit(7);"
                    "if(x.fold('CAFÉ')!=='café')process.exit(8);"
                    "if(x.contains('This Is The FINAL Decision','final decision')!==0n)process.exit(9);"
                    "if(x['contains-fold']('This Is The FINAL Decision','final decision')!==1n)process.exit(10);"
                    "if(x['contains-fold']('CAFÉ menu','café')!==1n)process.exit(11)")
        js-result (shell/sh "node" "--input-type=module" "-e"
                            (str "import('data:text/javascript;base64," js64
                                 "').then(m=>{const x=m.instantiateKotoba({});" checks "})"))
        wasm-result (shell/sh "node" "--input-type=module" "-e"
                              (str "import('./runtime/browser-host.mjs').then(async m=>{"
                                   "const h=await m.instantiateKotoba(Buffer.from(process.argv[1],'base64'));"
                                   "const x=h.instance.exports;" checks "})") wasm64)]
    (is (= 1 (ir/execute kir 'contains ["final decision made" "decision"])))
    (is (= 0 (ir/execute kir 'contains ["final decision made" "banana"])))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'contains ["abc" ""])))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'contains ["" ""])))
    (is (= "final decision" (ir/execute kir 'fold ["FINAL DECISION"])))
    (is (= "café" (ir/execute kir 'fold ["CAFÉ"])))
    (is (= 0 (ir/execute kir 'contains ["This Is The FINAL Decision" "final decision"])))
    (is (= 1 (ir/execute kir 'contains-fold
                         ["This Is The FINAL Decision" "final decision"])))
    (is (= 1 (ir/execute kir 'contains-fold ["CAFÉ menu" "café"])))
    (is (zero? (:exit js-result)) (:err js-result))
    (is (zero? (:exit wasm-result)) (:err wasm-result))))
