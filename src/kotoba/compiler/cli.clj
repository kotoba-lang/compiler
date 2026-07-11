(ns kotoba.compiler.cli
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.verifier :as verifier])
  (:gen-class))

(defn- parse-target [s]
  (case s "wasm32" :wasm32-kotoba-v1 "x86_64" :x86_64-kotoba-v1
        "aarch64" :aarch64-kotoba-v1 (keyword s)))

(defn- option [args flag] (second (drop-while #(not= flag %) args)))

(defn -main [& args]
  (case (first args)
    "compile"
    (let [input (second args) target (parse-target (or (option args "--target") "wasm32"))
          output (or (option args "--output") (str input (if (= target :wasm32-kotoba-v1) ".wasm" ".kexe")))
          result (compiler/compile-source (slurp input) target)]
      (if (= :wasm/v1 (:format result))
        (with-open [out (io/output-stream output)] (.write out ^bytes (:bytes result)))
        (spit output (pr-str (:artifact result))))
      (println (pr-str {:ok true :target target :output output})))
    "verify"
    (let [artifact (edn/read-string (slurp (second args)))]
      (verifier/verify-artifact! artifact)
      (println (pr-str {:ok true :verified true :target (:target artifact)})))
    (do (binding [*out* *err*] (println "usage: kotoba -M compile <file> --target wasm32|x86_64|aarch64 --output <file> | kotoba -M verify <file>"))
        (System/exit 2))))
