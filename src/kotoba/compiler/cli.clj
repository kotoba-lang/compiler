(ns kotoba.compiler.cli
  (:require [clojure.java.io :as io]
            [kotoba.compiler.bounded-edn :as bounded-edn]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.native-executor :as native-executor]
            [kotoba.compiler.receipt :as receipt]
            [kotoba.compiler.runtime-identity :as runtime-identity]
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
    (let [key (bounded-edn/read-file (second args))
          output (or (option args "--output") "kotoba-trust.edn")
          trust {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
                 :revoked-signers #{} :revoked-artifacts #{}
                 :revoked-runtime-sha256 #{}}]
      (spit output (pr-str trust))
      (println (pr-str {:ok true :output output :signer (:signer key)})))
    "trust-runtime"
    (let [evidence (bounded-edn/read-file (second args))
          trust-path (option args "--trust")
          trust (signing/validate-trust! (bounded-edn/read-file trust-path))
          runtime (:runtime evidence)
          runtime-sha (runtime-identity/identity-sha256 runtime)
          output (or (option args "--output") trust-path)
          updated (update trust :trusted-runtime-sha256 (fnil conj #{}) runtime-sha)]
      (spit output (pr-str updated))
      (println (pr-str {:ok true :output output :runtime-sha256 runtime-sha})))
    "sign"
    (let [artifact (bounded-edn/read-file (second args))
          key (bounded-edn/read-file (option args "--key"))
          output (or (option args "--output") "program.signed.kexe")
          not-before (Long/parseLong (or (option args "--not-before") "0"))
          expires (Long/parseLong (or (option args "--expires")
                                      (str (+ (quot (System/currentTimeMillis) 1000) 86400))))
          envelope (signing/sign artifact key {:not-before not-before :expires expires})]
      (spit output (pr-str envelope))
      (println (pr-str {:ok true :output output :signer (get-in envelope [:statement :signer])})))
    "verify-signed"
    (let [envelope (bounded-edn/read-file (second args))
          trust (bounded-edn/read-file (option args "--trust"))
          now (Long/parseLong (or (option args "--now")
                                  (str (quot (System/currentTimeMillis) 1000))))
          result (signing/verify envelope trust now)]
      (println (pr-str (dissoc result :artifact))))
    "run"
    (let [envelope (bounded-edn/read-file (second args))
          trust (bounded-edn/read-file (option args "--trust"))
          policy (bounded-edn/read-file (option args "--policy"))
          input (bounded-edn/read-file (option args "--input"))
          executor-key (bounded-edn/read-file (option args "--executor-key"))
          now (Long/parseLong (or (option args "--now")
                                  (str (quot (System/currentTimeMillis) 1000))))
          parent-path (option args "--parent")
          parent (when parent-path (bounded-edn/read-file parent-path))
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
    (let [envelope (bounded-edn/read-file (option args "--signed"))
          trust (bounded-edn/read-file (option args "--trust"))
          policy (bounded-edn/read-file (option args "--policy"))
          input (bounded-edn/read-file (option args "--input"))
          output-value (bounded-edn/read-file (option args "--result"))
          parent-path (option args "--parent")
          parent (when parent-path (bounded-edn/read-file parent-path))
          executor-key (bounded-edn/read-file (option args "--executor-key"))
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
    (let [value (bounded-edn/read-file (second args))
          envelope (bounded-edn/read-file (option args "--signed"))
          trust (bounded-edn/read-file (option args "--trust"))
          policy (bounded-edn/read-file (option args "--policy"))
          input (bounded-edn/read-file (option args "--input"))
          output-value (bounded-edn/read-file (option args "--result"))
          parent-path (option args "--parent")
          parent (when parent-path (bounded-edn/read-file parent-path))]
      (println (pr-str (receipt/verify value envelope trust policy input output-value
                                       {:now (Long/parseLong (option args "--now"))
                                        :parent parent}))))
    "verify-chain"
    (let [receipts (bounded-edn/read-file (second args))]
      (println (pr-str (receipt/verify-chain receipts))))
    "check"
    (let [input (second args)
          policy-path (option args "--policy")
          policy (if policy-path (bounded-edn/read-file policy-path) {})
          result (compiler/check-source (bounded-edn/read-text-file input) policy)]
      (println (pr-str {:ok true
                        :effects (get-in result [:hir :effects])
                        :admission (:admission result)})))
    "compile"
    (let [input (second args) target (parse-target (or (option args "--target") "wasm32"))
          output (or (option args "--output") (str input (if (= target :wasm32-kotoba-v1) ".wasm" ".kexe")))
          policy-path (option args "--policy")
          policy (if policy-path (bounded-edn/read-file policy-path) {})
          result (compiler/compile-source (bounded-edn/read-text-file input) target policy)]
      (if (= :wasm/v1 (:format result))
        (with-open [out (io/output-stream output)] (.write out ^bytes (:bytes result)))
        (spit output (pr-str (:artifact result))))
      (println (pr-str {:ok true :target target :output output})))
    "verify"
    (let [artifact (bounded-edn/read-file (second args))]
      (verifier/verify-artifact! artifact)
      (println (pr-str {:ok true :verified true :target (:target artifact)})))
    "extract-native"
    (let [artifact (bounded-edn/read-file (second args))
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
