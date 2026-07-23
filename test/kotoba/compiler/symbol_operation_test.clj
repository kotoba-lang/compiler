(ns kotoba.compiler.symbol-operation-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]))

(def source
  "(ns symbol.operation (:export [main make]))
   (defn main [] :i64 0)
   (defn make [value :string] :symbol (symbol value))")

(defn- encoded [bytes]
  (.encodeToString (java.util.Base64/getEncoder) bytes))

(deftest dynamic-symbol-construction-has-cross-target-conformance
  (let [javascript (compiler/compile-source source :js-kotoba-v1)
        wasm (compiler/compile-source source :wasm32-browser-kotoba-v1)
        kir (:kir wasm)
        js64 (encoded (.getBytes ^String (:source javascript) "UTF-8"))
        wasm64 (encoded (:bytes wasm))
        js-result
        (shell/sh "node" "--input-type=module" "-e"
                  (str "import('data:text/javascript;base64," js64
                       "').then(m=>{const x=m.instantiateKotoba({});"
                       "if(String(x.make('alpha/beta'))!=='alpha/beta')process.exit(2);"
                       "try{x.make('bad value');process.exit(3)}catch(e){}})"))
        wasm-result
        (shell/sh "node" "--input-type=module" "-e"
                  (str "import('./runtime/browser-host.mjs').then(async m=>{"
                       "const h=await m.instantiateKotoba(Buffer.from(process.argv[1],'base64'));"
                       "const x=h.instance.exports;"
                       "if(x.make('alpha/beta')!=='alpha/beta')process.exit(2);"
                       "try{x.make('bad value');process.exit(3)}catch(e){}})")
                  wasm64)]
    (is (= 'alpha/beta (ir/execute kir 'make ["alpha/beta"])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ir/execute kir 'make ["bad value"])))
    (is (zero? (:exit js-result)) (:err js-result))
    (is (zero? (:exit wasm-result)) (:err wasm-result))))

(deftest symbol-is-a-distinct-value-kind
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"same value type"
       (compiler/compile-source
        "(defn main [] :i64 0)
         (defn mismatch [value :string] :string
           (if 1 value (symbol value)))"
        :wasm32-browser-kotoba-v1))))
