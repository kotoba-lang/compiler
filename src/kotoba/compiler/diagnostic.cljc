(ns kotoba.compiler.diagnostic)

(def phase-codes
  {:usage :kotoba/invalid-usage
   :decode :kotoba/invalid-data
   :read :kotoba/source-read-failed
   :subset :kotoba/source-rejected
   :admission :kotoba/admission-denied
   :ir :kotoba/lowering-failed
   :verify :kotoba/verification-failed
   :coverage :kotoba/coverage-failed
   :signature :kotoba/signature-failed
   :trust :kotoba/trust-failed
   :runtime-identity :kotoba/runtime-identity-failed
   :output :kotoba/output-failed
   :execute :kotoba/execution-failed
   :receipt :kotoba/receipt-failed
   :internal :kotoba/internal-error})

(defn from-error [error source-name]
  (let [data (ex-data error)
        phase (or (:phase data) :internal)]
    (cond-> {:format :kotoba.diagnostic/v1
             :code (get phase-codes phase :kotoba/internal-error)
             :severity :error}
      (string? source-name) (assoc :source source-name)
      (map? (:span data)) (assoc :span (:span data)))))
