(ns kotoba.compiler.receipt
  (:require [kotoba.compiler.admission :as admission]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.runtime-identity :as runtime-identity]
            [kotoba.compiler.signing :as signing]))

(def statuses #{:ok :trap :denied})

(defn- receipt-hash [receipt]
  (artifact/sha256 (dissoc receipt :receipt-sha256 :executor)))

(defn valid-hash? [receipt]
  (and (string? (:receipt-sha256 receipt))
       (= (:receipt-sha256 receipt) (receipt-hash receipt))))

(defn create
  [envelope trust policy input output
   {:keys [now started-at finished-at status target entry fuel-initial fuel-remaining parent
           executor-key]}]
  (let [{kexe :artifact signer :signer} (signing/verify envelope trust now)
        required (:effects kexe)
        policy-result (admission/check {:effects required} policy)
        parent-sha (when parent
                     (do (when-not (valid-hash? parent)
                           (throw (ex-info "parent receipt integrity mismatch" {:phase :receipt})))
                         (:receipt-sha256 parent)))]
    (when-let [runtime (and (map? output) (:runtime output))]
      (runtime-identity/admit! runtime trust))
    (when-not (contains? statuses status)
      (throw (ex-info "invalid receipt status" {:phase :receipt :status status})))
    (when-not (and (integer? started-at) (integer? finished-at) (<= started-at finished-at))
      (throw (ex-info "invalid receipt time interval" {:phase :receipt})))
    (when-not (and (integer? fuel-initial) (integer? fuel-remaining)
                   (<= 0 fuel-remaining fuel-initial))
      (throw (ex-info "invalid receipt fuel accounting" {:phase :receipt})))
    (when-not (and (= target (:target kexe)) (symbol? entry)
                   (contains? (:exports kexe) entry))
      (throw (ex-info "receipt target or entry does not match artifact"
                      {:phase :receipt :target target :entry entry})))
    (when-not (signing/valid-key? executor-key)
      (throw (ex-info "receipt requires a valid executor signing key" {:phase :receipt})))
    (let [body {:format :kotoba.run-receipt/v1
                :artifact-envelope-sha256 (artifact/sha256 envelope)
                :artifact-sha256 (:sha256 kexe)
                :signer signer
                :target target :entry entry
                :required-effects required
                :policy-sha256 (artifact/sha256 policy)
                :input-sha256 (artifact/sha256 input)
                :output-sha256 (artifact/sha256 output)
                :fuel {:initial fuel-initial :remaining fuel-remaining
                       :consumed (- fuel-initial fuel-remaining)}
                :status status :started-at started-at :finished-at finished-at
                :parent parent-sha
                :admission-sha256 (artifact/sha256 policy-result)}
          receipt-sha (artifact/sha256 body)
          executor-statement {:format :kotoba.receipt-attestation/v1
                              :receipt-sha256 receipt-sha
                              :executor (:signer executor-key)}]
      (assoc body :receipt-sha256 receipt-sha
             :executor {:signer (:signer executor-key)
                        :public-key (:public-key executor-key)
                        :signature (signing/sign-value executor-key executor-statement)}))))

(defn verify
  [receipt envelope trust policy input output {:keys [now parent]}]
  (when-not (= :kotoba.run-receipt/v1 (:format receipt))
    (throw (ex-info "unknown run receipt format" {:phase :receipt})))
  (when-not (valid-hash? receipt)
    (throw (ex-info "receipt integrity mismatch" {:phase :receipt})))
  (let [{kexe :artifact signer :signer} (signing/verify envelope trust now)
        required (:effects kexe)
        policy-result (admission/check {:effects required} policy)
        expected-parent (some-> parent :receipt-sha256)
        fuel (:fuel receipt)
        executor (:executor receipt)
        executor-statement {:format :kotoba.receipt-attestation/v1
                            :receipt-sha256 (:receipt-sha256 receipt)
                            :executor (:signer executor)}]
    (when-let [runtime (and (map? output) (:runtime output))]
      (runtime-identity/admit! runtime trust))
    (when (and parent (not (valid-hash? parent)))
      (throw (ex-info "parent receipt integrity mismatch" {:phase :receipt})))
    (when-not (and (= (:signer executor) (signing/signer-id (:public-key executor)))
                   (contains? (:trusted-signers trust) (:signer executor))
                   (not (contains? (:revoked-signers trust) (:signer executor)))
                   (signing/verify-value (:public-key executor) executor-statement
                                         (:signature executor)))
      (throw (ex-info "executor receipt attestation rejected" {:phase :receipt})))
    (when-not (and (= (:artifact-envelope-sha256 receipt) (artifact/sha256 envelope))
                   (= (:artifact-sha256 receipt) (:sha256 kexe))
                   (= (:signer receipt) signer)
                   (= (:target receipt) (:target kexe))
                   (contains? (:exports kexe) (:entry receipt))
                   (= (:required-effects receipt) required)
                   (= (:policy-sha256 receipt) (artifact/sha256 policy))
                   (= (:admission-sha256 receipt) (artifact/sha256 policy-result))
                   (= (:input-sha256 receipt) (artifact/sha256 input))
                   (= (:output-sha256 receipt) (artifact/sha256 output))
                   (= (:parent receipt) expected-parent)
                   (contains? statuses (:status receipt))
                   (integer? (:started-at receipt)) (integer? (:finished-at receipt))
                   (<= (:started-at receipt) (:finished-at receipt))
                   (integer? (:initial fuel)) (integer? (:remaining fuel))
                   (<= 0 (:remaining fuel) (:initial fuel))
                   (= (:consumed fuel) (- (:initial fuel) (:remaining fuel))))
      (throw (ex-info "receipt evidence mismatch" {:phase :receipt})))
    {:verified? true :receipt-sha256 (:receipt-sha256 receipt)
     :artifact-sha256 (:artifact-sha256 receipt) :status (:status receipt)}))

(defn verify-chain [receipts]
  (when (> (count receipts) 10000)
    (throw (ex-info "receipt chain exceeds verification limit" {:phase :receipt})))
  (loop [remaining receipts parent nil seen #{}]
    (if-let [receipt (first remaining)]
      (do
        (when-not (valid-hash? receipt)
          (throw (ex-info "receipt chain integrity mismatch" {:phase :receipt})))
        (when-not (= (:parent receipt) (some-> parent :receipt-sha256))
          (throw (ex-info "receipt parent link mismatch" {:phase :receipt})))
        (when (contains? seen (:receipt-sha256 receipt))
          (throw (ex-info "receipt chain cycle" {:phase :receipt})))
        (recur (next remaining) receipt (conj seen (:receipt-sha256 receipt))))
      {:verified? true :count (count receipts) :head (some-> parent :receipt-sha256)})))
