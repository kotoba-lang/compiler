(ns kotoba.compiler.provider.llm
  "Bounded synchronous LLM reference provider. Credentials and SDKs stay host-owned."
  (:require [kotoba.compiler.value :as value]))

(def capability-id 11)
(def max-input-bytes 65536)
(def max-output-bytes 65536)
(def max-output-tokens 4096)
(def max-temperature-milli 2000)

(def request-type
  [:record :kotoba.llm/generate-request
   [[:model :keyword] [:system :string] [:prompt :string]
    [:max-output-tokens :i64] [:temperature-milli :i64]]])
(def usage-type
  [:record :kotoba.llm/usage
   [[:input-tokens :i64] [:output-tokens :i64]]])
(def completion-type
  [:record :kotoba.llm/completion
   [[:text :string] [:finish-reason :keyword] [:usage usage-type]]])
(def error-type
  [:record :kotoba.llm/error
   [[:code :keyword] [:message :string] [:retryable :bool]]])
(def result-type
  [:variant :kotoba.llm/result [[:ok completion-type] [:error error-type]]])

(def schemas
  {:kotoba.llm/generate-request request-type
   :kotoba.llm/usage usage-type
   :kotoba.llm/completion completion-type
   :kotoba.llm/error error-type
   :kotoba.llm/result result-type})

(defn- error [code message retryable]
  (value/bounded-keyword! code value/keyword-value-byte-limit)
  (value/bounded-string! message value/string-value-byte-limit)
  [result-type :error [error-type code message retryable]])

(defn- invoke-transport [transport request]
  (try
    (transport request)
    (catch #?(:clj Throwable :cljs :default) _
      {:error {:code :llm/transport
               :message "provider failed"
               :retryable false}})))

(defn- valid-token-count? [tokens]
  (and (integer? tokens) (<= 0 tokens)))

(defn provider
  "Creates a typed LLM provider around a host-supplied synchronous transport.
  `allowed-models` is an exact closed set of qualified model keywords. The
  transport receives and returns plain immutable host maps; secrets, SDK
  objects, streams, and callbacks never cross the guest boundary."
  [{:keys [allowed-models transport]}]
  (when-not (and (set? allowed-models) (every? keyword? allowed-models))
    (throw (ex-info "LLM allowed-models must be a set of keywords"
                    {:phase :llm-provider})))
  (doseq [model allowed-models]
    (value/bounded-keyword! model value/keyword-value-byte-limit))
  (when-not (fn? transport)
    (throw (ex-info "LLM transport must be a function" {:phase :llm-provider})))
  {:request-type request-type
   :result-type result-type
   :invoke
   (fn [[actual-type model system prompt output-tokens temperature-milli]]
     (when-not (= actual-type request-type)
       (throw (ex-info "LLM request contract mismatch" {:phase :llm-provider})))
     (when-not (contains? allowed-models model)
       (throw (ex-info "LLM model is not allowed"
                       {:phase :llm-provider :model model})))
     (value/bounded-string! system max-input-bytes)
     (value/bounded-string! prompt max-input-bytes)
     (when-not (<= 1 output-tokens max-output-tokens)
       (throw (ex-info "LLM output token budget is outside the admitted range"
                       {:phase :llm-provider :max-output-tokens output-tokens})))
     (when-not (<= 0 temperature-milli max-temperature-milli)
       (throw (ex-info "LLM temperature is outside the admitted range"
                       {:phase :llm-provider :temperature-milli temperature-milli})))
     (let [reply (invoke-transport
                  transport {:model model :system system :prompt prompt
                             :max-output-tokens output-tokens
                             :temperature-milli temperature-milli})]
       (if-let [{:keys [code message retryable]} (:error reply)]
         (error code message retryable)
         (let [{:keys [text finish-reason input-tokens output-tokens]} reply]
           (value/bounded-string! text max-output-bytes)
           (value/bounded-keyword! finish-reason value/keyword-value-byte-limit)
           (when-not (and (valid-token-count? input-tokens)
                          (valid-token-count? output-tokens)
                          (<= output-tokens max-output-tokens))
             (throw (ex-info "LLM transport usage is invalid" {:phase :llm-provider})))
           [result-type :ok
            [completion-type text finish-reason
             [usage-type input-tokens output-tokens]]]))))})
