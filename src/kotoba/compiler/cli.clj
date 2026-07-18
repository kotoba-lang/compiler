(ns kotoba.compiler.cli
  (:require [kotoba.compiler.atomic-output :as atomic-output]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.bounded-edn :as bounded-edn]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.coverage :as coverage]
            [kotoba.compiler.coverage-evidence :as coverage-evidence]
            [kotoba.compiler.ios-aot :as ios-aot]
            [kotoba.compiler.native-executor :as native-executor]
            [kotoba.compiler.packaging.pe32plus :as pe32plus]
            [kotoba.compiler.receipt :as receipt]
            [kotoba.compiler.release :as release]
            [kotoba.compiler.runtime-identity :as runtime-identity]
            [kotoba.compiler.signing :as signing]
            [kotoba.compiler.source-path :as source-path]
            [kotoba.compiler.target :as target-profile]
            [kotoba.compiler.verifier :as verifier]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:gen-class))

(defn- parse-target [s]
  (case s "wasm32" :wasm32-kotoba-v1 "x86_64" :x86_64-kotoba-v1
        "aarch64" :aarch64-kotoba-v1
        "js" :js-kotoba-v1
        "javascript" :js-kotoba-v1
        "js-browser" :js-browser-kotoba-v1
        "wasm32-browser" :wasm32-browser-kotoba-v1
        "wasm32-wasi" :wasm32-wasi-kotoba-v1
        "x86_64-linux" :x86_64-linux-kotoba-v1
        "x86_64-macos" :x86_64-macos-kotoba-v1
        "x86_64-windows" :x86_64-windows-kotoba-v1
        "aarch64-linux" :aarch64-linux-kotoba-v1
        "aarch64-macos" :aarch64-macos-kotoba-v1
        "aarch64-windows" :aarch64-windows-kotoba-v1
        "aarch64-android" :aarch64-android-kotoba-v1
        "aarch64-ios" :aarch64-ios-kotoba-v1
        (keyword s)))

(defn- option [args flag] (second (drop-while #(not= flag %) args)))

(def ^:dynamic *exit* (fn [status] (System/exit status)))

(defn- kotoba-source! [path] (source-path/admit! path))

(def ^:private detail-keys
  #{:phase :target :artifact-target :host-target :entry :arity :limit :status
    :reason :runtime-sha256 :not-before :expires :now})

(defn error-report [error]
  (let [data (ex-data error)
        phase (or (:phase data) :internal)
        details (select-keys data detail-keys)]
    (cond-> {:format :kotoba.cli-error/v1
             :ok false
             :error phase
             :message (if (= phase :internal) "internal compiler error" (ex-message error))}
      (seq details) (assoc :details details))))

(defn exit-code [phase]
  (case phase
    :usage 64
    (:decode :read :subset :admission :ir :verify :coverage) 65
    (:signature :trust :runtime-identity) 77
    :output 74
    :execute 69
    :receipt 76
    70))

(defn- dispatch! [& args]
  (case (first args)
    "keygen"
    (let [output (or (option args "--output") "kotoba-signing-key.edn")
          key (signing/generate-keypair)]
      (atomic-output/write-edn! output key {:private? true})
      (println (pr-str {:ok true :output output :signer (:signer key)})))
    "public-key"
    (let [key (bounded-edn/read-file (second args))
          public (signing/verification-key key)
          output (or (option args "--output") "kotoba-verification-key.edn")]
      (atomic-output/write-edn! output public)
      (println (pr-str {:ok true :output output :signer (:signer public)})))
    "trust-key"
    (let [key (bounded-edn/read-file (second args))
          signer (signing/trusted-signer-id! key)
          output (or (option args "--output") "kotoba-trust.edn")
          trust {:format :kotoba.trust/v1 :trusted-signers #{signer}
                 :revoked-signers #{} :revoked-artifacts #{}
                 :trusted-runtime-sha256 #{}
                 :revoked-runtime-sha256 #{}}]
      (atomic-output/write-edn! output trust)
      (println (pr-str {:ok true :output output :signer signer})))
    "trust-runtime"
    (let [evidence (bounded-edn/read-file (second args))
          _ (runtime-identity/validate-measurement! evidence)
          trust-path (option args "--trust")
          trust (signing/validate-trust! (bounded-edn/read-file trust-path))
          runtime (:runtime evidence)
          runtime-sha (runtime-identity/identity-sha256 runtime)
          output (or (option args "--output") trust-path)
          updated (update trust :trusted-runtime-sha256 (fnil conj #{}) runtime-sha)]
      (atomic-output/write-edn! output updated)
      (println (pr-str {:ok true :output output :runtime-sha256 runtime-sha})))
    "measure-runtime"
    (let [{:keys [runtime loader-bytes]} (native-executor/measure-runtime)
          output (or (option args "--output") "kotoba-runtime.edn")
          loader-output (or (option args "--loader-output") "kotoba-loader")]
      (atomic-output/write-bytes! loader-output loader-bytes {:executable? true})
      (atomic-output/write-edn! output {:format :kotoba.runtime-measurement/v1
                                        :runtime runtime})
      (println (pr-str {:ok true :output output :loader-output loader-output
                        :runtime-sha256 (runtime-identity/identity-sha256 runtime)})))
    "sign"
    (let [artifact (bounded-edn/read-file (second args))
          key (bounded-edn/read-file (option args "--key"))
          output (or (option args "--output") "program.signed.kexe")
          not-before (Long/parseLong (or (option args "--not-before") "0"))
          expires (Long/parseLong (or (option args "--expires")
                                      (str (+ (quot (System/currentTimeMillis) 1000) 86400))))
          envelope (signing/sign artifact key {:not-before not-before :expires expires})]
      (atomic-output/write-edn! output envelope)
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
          runtime-measurement (bounded-edn/read-file (option args "--runtime"))
          _ (runtime-identity/validate-measurement! runtime-measurement)
          execution (native-executor/execute envelope trust policy input
                                              {:now now :entry entry
                                               :runtime (:runtime runtime-measurement)
                                               :loader-path (option args "--loader")})
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
      (atomic-output/write-edn! result-path evidence)
      (atomic-output/write-edn! receipt-path value)
      (println (pr-str {:ok (= :ok (:status evidence))
                        :status (:status evidence) :result evidence
                        :result-output result-path :output receipt-path
                        :receipt-sha256 (:receipt-sha256 value)}))
      (when-not (= :ok (:status evidence)) (*exit* 120)))
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
      (atomic-output/write-edn! output-path value)
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
    (let [receipts (bounded-edn/read-file (second args))
          trust (bounded-edn/read-file (option args "--trust"))]
      (println (pr-str (receipt/verify-chain receipts trust))))
    "coverage"
    (let [manifest (bounded-edn/read-file (second args))
          _ (coverage/verify-dataset! manifest (option args "--dataset"))
          evidence-path (option args "--evidence")
          evidence (if evidence-path
                     (coverage-evidence/verify-bundle
                      (bounded-edn/read-file evidence-path)
                      (bounded-edn/read-file (option args "--trust"))
                      (Long/parseLong (or (option args "--now")
                                          (str (quot (System/currentTimeMillis) 1000)))))
                     [])]
      (println (pr-str (coverage/report manifest evidence))))
    "sign-coverage-evidence"
    (let [claim (bounded-edn/read-file (second args))
          key (bounded-edn/read-file (option args "--key"))
          output (or (option args "--output") "kotoba-coverage-evidence.edn")
          envelope (coverage-evidence/sign claim key)]
      (atomic-output/write-edn! output [envelope])
      (println (pr-str {:ok true :output output
                        :evidence-sha256 (artifact/sha256 (:statement envelope))})))
    "check"
    (let [input (kotoba-source! (second args))
          policy-path (option args "--policy")
          policy (if policy-path (bounded-edn/read-file policy-path) {})
          result (compiler/check-source (bounded-edn/read-text-file input) policy)]
      (println (pr-str {:ok true
                        :effects (get-in result [:hir :effects])
                        :admission (:admission result)})))
    "package-aiueos-boot"
    (let [input (second args)
          output (or (option args "--output") "BOOTX64.EFI")
          raw (java.nio.file.Files/readAllBytes
               (java.nio.file.Paths/get input (make-array String 0)))
          kernel (mapv #(bit-and (int %) 0xff) raw)
          packaged (pe32plus/package-embedded-kernel kernel)]
      (atomic-output/write-bytes! output
        (byte-array (map unchecked-byte (:bytes packaged))))
      (println (pr-str {:ok true :target :x86_64-aiueos-uefi-v1
                        :kernel input :output output
                        :kernel-sha256 (:embedded-kernel-sha256 packaged)})))
    "compile"
    (let [input (kotoba-source! (second args))
          target (parse-target (or (option args "--target") "wasm32"))
          output (or (option args "--output")
                     (str input (case (:execution (target-profile/profile target))
                                  :wasm ".wasm"
                                  :cljs ".cljs"
                                  :javascript ".mjs"
                                  :kernel ".o"
                                  :process ".elf"
                                  ".kexe")))
          policy-path (option args "--policy")
          policy (if policy-path (bounded-edn/read-file policy-path) {})
          artifact-kind (option args "--artifact")
          _ (when-not (contains? #{nil "object" "image"} artifact-kind)
              (throw (ex-info "unknown native artifact kind"
                              {:phase :artifact-target :artifact artifact-kind})))
          result (compiler/compile-source (bounded-edn/read-text-file input) target policy)]
      (case (:format result)
        :wasm/v1 (atomic-output/write-bytes! output (:bytes result))
        ;; ADR-2607151500: the cljs backend emits SOURCE TEXT, not an
        ;; artifact map -- write-edn! would pr-str this into a quoted/
        ;; escaped EDN string literal instead of directly readable cljs
        ;; source (a real, previously-silent bug: `compile --target
        ;; cljs-kotoba-v1` reported :ok true while writing the literal
        ;; text "nil" to --output, since :artifact is absent from a
        ;; :cljs/v1 result).
        :cljs/v1 (atomic-output/write-text! output (:source result))
        :javascript/v1 (do
                         (atomic-output/write-text! output (:source result))
                         (atomic-output/write-edn! (str output ".manifest.edn")
                                                   (:manifest result))
                         (atomic-output/write-text!
                          (str output ".manifest.json")
                          (json/write-str (:manifest result)
                                          :key-fn (fn [k] (if (keyword? k)
                                                            (subs (str k) 1)
                                                            (str k))))))
        :kexe/v1 (if-let [packaged (case artifact-kind
                                    "image" (:binary result)
                                    "object" (:object result)
                                    (or (:object result)
                                        (when (= :process (get-in result [:artifact :target-profile :execution]))
                                          (:binary result))))]
                   (atomic-output/write-bytes!
                    output (byte-array (map unchecked-byte (:bytes packaged))))
                   (atomic-output/write-edn! output (:artifact result)))
        (atomic-output/write-edn! output (:artifact result)))
      (println (pr-str {:ok true :target target :output output})))
    "package-ios"
    (let [input (bounded-edn/read-file (second args))
          entry (symbol (or (option args "--entry") "main"))
          output (or (option args "--output") "kotoba-ios-program.S")
          manifest-output (or (option args "--manifest-output") (str output ".edn"))
          packaged (ios-aot/package input entry)]
      (atomic-output/write-bytes! output (:assembly packaged))
      (atomic-output/write-edn! manifest-output (:manifest packaged))
      (println (pr-str {:ok true :target (:target input) :entry entry
                        :output output :manifest-output manifest-output})))
    "sbom"
    (let [input (second args)
          output (or (option args "--output") (str input ".spdx"))]
      (atomic-output/write-bytes! output (release/sbom-bytes input))
      (println (pr-str {:ok true :format :spdx/v2.3 :output output})))
    "attest-release"
    (let [input (second args)
          sbom (option args "--sbom")
          key (bounded-edn/read-file (option args "--key"))
          target (parse-target (option args "--target"))
          not-before (Long/parseLong (option args "--not-before"))
          expires (Long/parseLong (option args "--expires"))
          output (or (option args "--output") (str input ".attestation.edn"))
          envelope (release/attest input sbom target key not-before expires)]
      (atomic-output/write-edn! output envelope)
      (println (pr-str {:ok true :target target :output output
                        :subject-sha256 (get-in envelope [:statement :subject :sha256])})))
    "verify-release"
    (let [envelope (bounded-edn/read-file (second args))
          input (option args "--artifact")
          sbom (option args "--sbom")
          trust (bounded-edn/read-file (option args "--trust"))
          now (Long/parseLong (option args "--now"))
          result (release/verify! envelope input sbom trust now)]
      (println (pr-str (assoc result :ok true))))
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
        (throw (ex-info "unknown native export" {:phase :verify :entry symbol})))
      (atomic-output/write-bytes!
       output (byte-array (map unchecked-byte (:code artifact))))
      (println (pr-str (merge {:ok true :output output :symbol symbol} export))))
    (throw (ex-info "unknown or missing kotoba command" {:phase :usage}))))

(defn -main [& args]
  (try
    (apply dispatch! args)
    (catch clojure.lang.ExceptionInfo error
      (let [report (error-report error)]
        (binding [*out* *err*] (println (pr-str report)))
        (*exit* (exit-code (:error report)))))
    (catch Throwable _
      (binding [*out* *err*]
        (println (pr-str {:format :kotoba.cli-error/v1 :ok false
                          :error :internal :message "internal compiler error"})))
      (*exit* 70))))
