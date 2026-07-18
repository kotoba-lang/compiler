(ns kotoba.compiler.typed-value-conformance-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]))

(def corpus
  (-> "kotoba/compiler/typed-value-conformance.edn" io/resource slurp edn/read-string))

(defn- web-result [compiled]
  (let [encoded (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes ^String (:source compiled) "UTF-8"))
        probe (str "import('data:text/javascript;base64," encoded
                   "').then(m=>{const value=m.instantiateKotoba({}).main();"
                   "if(typeof value!=='bigint')process.exit(2);console.log(value.toString())})")]
    (shell/sh "node" "--input-type=module" "-e" probe)))

(defn- wasm-result [compiled]
  (let [encoded (.encodeToString (java.util.Base64/getEncoder) ^bytes (:bytes compiled))
        probe (str "import('./runtime/browser-host.mjs').then(async m=>{"
                   "const h=await m.instantiateKotoba(Buffer.from(process.argv[1],'base64'));"
                   "const value=h.instance.exports.main();"
                   "if(typeof value!=='bigint')process.exit(2);console.log(value.toString())})")]
    (shell/sh "node" "--input-type=module" "-e" probe encoded)))

(deftest shared-positive-corpus-agrees-on-reference-and-web
  (is (= 1 (:kotoba.typed-value-conformance/version corpus)))
  (is (= :kotoba.typed-value/canonical-v1 (:abi corpus)))
  (doseq [{:keys [id source expect]} (:positive corpus)]
    (testing (name id)
      (let [compiled (compiler/compile-source source :js-kotoba-v1)
            wasm-compiled (compiler/compile-source source :wasm32-kotoba-v1)
            reference (ir/execute (:kir compiled) 'main [])
            web (web-result compiled)
            wasm (wasm-result wasm-compiled)]
        (is (= expect reference))
        (is (zero? (:exit web)) (:err web))
        (is (= (str expect "\n") (:out web)))
        (is (zero? (:exit wasm)) (:err wasm))
        (is (= (str expect "\n") (:out wasm)))))))

(deftest shared-negative-corpus-fails-closed
  (doseq [{:keys [id source phase]} (:negative corpus)]
    (testing (name id)
      (is (contains? #{nil :runtime} phase))
      (if phase
        (let [compiled (compiler/compile-source source :js-kotoba-v1)]
          (is (thrown? clojure.lang.ExceptionInfo
                       (ir/execute (:kir compiled) 'main [])))
          (is (not (zero? (:exit (web-result compiled)))))
          (is (not (zero? (:exit (wasm-result
                                  (compiler/compile-source source :wasm32-kotoba-v1)))))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (compiler/check-source source)))))))
