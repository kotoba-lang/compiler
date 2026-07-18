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

(deftest shared-positive-corpus-agrees-on-reference-and-web
  (is (= 1 (:kotoba.typed-value-conformance/version corpus)))
  (is (= :kotoba.typed-value/canonical-v1 (:abi corpus)))
  (doseq [{:keys [id source expect]} (:positive corpus)]
    (testing (name id)
      (let [compiled (compiler/compile-source source :js-kotoba-v1)
            reference (ir/execute (:kir compiled) 'main [])
            web (web-result compiled)]
        (is (= expect reference))
        (is (zero? (:exit web)) (:err web))
        (is (= (str expect "\n") (:out web)))))))

(deftest shared-negative-corpus-fails-closed
  (doseq [{:keys [id source phase]} (:negative corpus)]
    (testing (name id)
      (is (contains? #{nil :runtime} phase))
      (if phase
        (let [compiled (compiler/compile-source source :js-kotoba-v1)]
          (is (thrown? clojure.lang.ExceptionInfo
                       (ir/execute (:kir compiled) 'main [])))
          (is (not (zero? (:exit (web-result compiled))))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (compiler/check-source source)))))))
