(ns kotoba.compiler.cli
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.native-executor :as native-executor]
            [kotoba.compiler.receipt :as receipt]
            [kotoba.compiler.signing :as signing]
            [kotoba.compiler.verifier :as verifier])
  (:gen-class))

(defn- parse-target [s]
  (case s "wasm32" :wasm32-kotoba-v1 "x86_64" :x86_64-kotoba-v1
        "aarch64" :aarch64-kotoba-v1 (keyword s)))

(defn- option [args flag] (second (drop-while #(not= flag %) args)))

(defn -main [& args]
  (case (first args)
    "keygen"
    (let [output (or (option args "--output") "kotoba-signing-key.edn")
          key (signing/generate-keypair)]
      (spit output (pr-str key))
      (println (pr-str {:ok true :output output :signer (:signer key)})))
    "trust-key"
    (let [key (edn/read-string (slurp (second args)))
          output (or (option args "--output") "kotoba-trust.edn")
          trust {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
                 :revoked-signers #{} :revoked-artifacts #{}}]
      (spit output (pr-str trust))
      (println (pr-str {:ok true :output output :signer (:signer key)})))
    "sign"
    (let [artifact (edn/read-string (slurp (second args)))
          key (edn/read-string (slurp (option args "--key")))
          output (or (option args "--output") "program.signed.kexe")
          not-before (Long/parseLong (or (option args "--not-before") "0"))
          expires (Long/parseLong (or (option args "--expires")
                                      (str (+ (quot (System/currentTimeMillis) 1000) 86400))))
          envelope (signing/sign artifact key {:not-before not-before :expires expires})]
      (spit output (pr-str envelope))
      (println (pr-str {:ok true :output output :signer (get-in envelope [:statement :signer])})))
    "verify-signed"
    (let [envelope (edn/read-string (slurp (second args)))
          trust (edn/read-string (slurp (option args "--trust")))
          now (Long/parseLong (or (option args "--now")
                                  (str (quot (System/currentTimeMillis) 1000))))
          result (signing/verify envelope trust now)]
      (println (pr-str (dissoc result :artifact))))
    "run"
    (let [envelope (edn/read-string (slurp (second args)))
          trust (edn/read-string (slurp (option args "--trust")))
          policy (edn/read-string (slurp (option args "--policy")))
          input (edn/read-string (slurp (option args "--input")))
          executor-key (edn/read-string (slurp (option args "--executor-key")))
          now (Long/parseLong (or (option args "--now")
                                  (str (quot (System/currentTimeMillis) 1000))))
          parent-path (option args "--parent")
          parent (when parent-path (edn/read-string (slurp parent-path)))
          entry (symbol (or (option args "--entry") "main"))
          execution (native-executor/execute envelope trust policy input
                                              {:now now :entry entry})
          report (:report execution)
          evidence (:evidence execution)
          value (receipt/create
                 envelope trust policy input evidence
                 {:now now :started-at (:started-at execution)
                  :finished-at (:finished-at execution)
                  :status (:status evidence) :target (:target execution)
                  :entry entry :fuel-initial (get-in report [:fuel :initial])
                  :fuel-remaining (get-in report [:fuel :remaining])
                  :parent parent :executor-key executor-key})
          result-path (or (option args "--result-output") "run.result.edn")
          receipt-path (or (option args "--output") "run.receipt.edn")]
      (spit result-path (pr-str evidence))
      (spit receipt-path (pr-str value))
      (println (pr-str {:ok (= :ok (:status evidence))
                        :status (:status evidence) :result evidence
                        :result-output result-path :output receipt-path
                        :receipt-sha256 (:receipt-sha256 value)}))
      (when-not (= :ok (:status evidence)) (System/exit 120)))
    "receipt"
    (let [envelope (edn/read-string (slurp (option args "--signed")))
          trust (edn/read-string (slurp (option args "--trust")))
          policy (edn/read-string (slurp (option args "--policy")))
          input (edn/read-string (slurp (option args "--input")))
          output-value (edn/read-string (slurp (option args "--result")))
          parent-path (option args "--parent")
          parent (when parent-path (edn/read-string (slurp parent-path)))
          executor-key (edn/read-string (slurp (option args "--executor-key")))
          output-path (or (option args "--output") "run.receipt.edn")
          value (receipt/create
                 envelope trust policy input output-value
                 {:now (Long/parseLong (option args "--now"))
                  :started-at (Long/parseLong (option args "--started-at"))
                  :finished-at (Long/parseLong (option args "--finished-at"))
                  :status (keyword (option args "--status"))
                  :target (parse-target (option args "--target"))
                  :entry (symbol (or (option args "--entry") "main"))
                  :fuel-initial (Long/parseLong (or (option args "--fuel-initial") "256"))
                  :fuel-remaining (Long/parseLong (option args "--fuel-remaining"))
                  :parent parent :executor-key executor-key})]
      (spit output-path (pr-str value))
      (println (pr-str {:ok true :output output-path :receipt-sha256 (:receipt-sha256 value)})))
    "verify-receipt"
    (let [value (edn/read-string (slurp (second args)))
          envelope (edn/read-string (slurp (option args "--signed")))
          trust (edn/read-string (slurp (option args "--trust")))
          policy (edn/read-string (slurp (option args "--policy")))
          input (edn/read-string (slurp (option args "--input")))
          output-value (edn/read-string (slurp (option args "--result")))
          parent-path (option args "--parent")
          parent (when parent-path (edn/read-string (slurp parent-path)))]
      (println (pr-str (receipt/verify value envelope trust policy input output-value
                                       {:now (Long/parseLong (option args "--now"))
                                        :parent parent}))))
    "verify-chain"
    (let [receipts (edn/read-string (slurp (second args)))]
      (println (pr-str (receipt/verify-chain receipts))))
    "check"
    (let [input (second args)
          policy-path (option args "--policy")
          policy (if policy-path (edn/read-string (slurp policy-path)) {})
          result (compiler/check-source (slurp input) policy)]
      (println (pr-str {:ok true
                        :effects (get-in result [:hir :effects])
                        :admission (:admission result)})))
    "compile"
    (let [input (second args) target (parse-target (or (option args "--target") "wasm32"))
          output (or (option args "--output") (str input (if (= target :wasm32-kotoba-v1) ".wasm" ".kexe")))
          policy-path (option args "--policy")
          policy (if policy-path (edn/read-string (slurp policy-path)) {})
          result (compiler/compile-source (slurp input) target policy)]
      (if (= :wasm/v1 (:format result))
        (with-open [out (io/output-stream output)] (.write out ^bytes (:bytes result)))
        (spit output (pr-str (:artifact result))))
      (println (pr-str {:ok true :target target :output output})))
    "verify"
    (let [artifact (edn/read-string (slurp (second args)))]
      (verifier/verify-artifact! artifact)
      (println (pr-str {:ok true :verified true :target (:target artifact)})))
    "extract-native"
    (let [artifact (edn/read-string (slurp (second args)))
          symbol (symbol (or (option args "--symbol") "main"))
          output (or (option args "--output") "program.bin")
          _ (verifier/verify-artifact! artifact)
          export (get (:exports artifact) symbol)]
      (when-not export
        (throw (ex-info "unknown native export" {:symbol symbol :available (keys (:exports artifact))})))
      (with-open [out (io/output-stream output)]
        (.write out ^bytes (byte-array (map unchecked-byte (:code artifact)))))
      (println (pr-str (merge {:ok true :output output :symbol symbol} export))))
    (do (binding [*out* *err*] (println "usage: kotoba -M check <file> [--policy policy.edn] | kotoba -M compile <file> --target wasm32|x86_64|aarch64 --output <file> | kotoba -M verify <file>"))
        (System/exit 2))))
