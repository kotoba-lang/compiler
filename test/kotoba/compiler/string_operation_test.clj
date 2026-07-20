(ns kotoba.compiler.string-operation-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]))

(def source
  "(ns string.operation (:export [main concat replace]))
   (defn main [] :i64 0)
   (defn concat [left :string right :string] :string
     (string-concat left right))
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
    (is (= "a$$a$$" (ir/execute kir 'replace ["a.$a.$" "." "$"])))
    (is (thrown? clojure.lang.ExceptionInfo (ir/execute kir 'replace ["abc" "" "x"])))
    (is (zero? (:exit js-result)) (:err js-result))
    (is (zero? (:exit wasm-result)) (:err wasm-result))))
