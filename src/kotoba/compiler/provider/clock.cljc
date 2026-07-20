(ns kotoba.compiler.provider.clock
  "Typed clock reference provider. Host clock functions never cross the ABI."
  (:require [kotoba.compiler.value :as value]))

(def capability-id 7)

(def request-type
  [:variant :kotoba.clock/request [[:wall :bool] [:monotonic :bool]]])
(def wall-type
  [:record :kotoba.clock/wall
   [[:unix-millis :i64] [:observation-sequence :i64]]])
(def monotonic-type
  [:record :kotoba.clock/monotonic
   [[:nanos :i64] [:observation-sequence :i64]]])
(def error-type
  [:record :kotoba.clock/error [[:code :keyword] [:message :string]]])
(def result-type
  [:variant :kotoba.clock/result
   [[:wall wall-type] [:monotonic monotonic-type] [:error error-type]]])

(def schemas
  {:kotoba.clock/request request-type
   :kotoba.clock/wall wall-type
   :kotoba.clock/monotonic monotonic-type
   :kotoba.clock/error error-type
   :kotoba.clock/result result-type})

(defn- valid-tick? [tick]
  (and (integer? tick) (<= 0 tick)))

(defn- error [code message]
  (value/bounded-keyword! code value/keyword-value-byte-limit)
  (value/bounded-string! message value/string-value-byte-limit)
  [result-type :error [error-type code message]])

(defn- read-source [source]
  (try
    {:value (source)}
    (catch #?(:clj Throwable :cljs :default) _
      {:error (error :clock/source "clock source failed")})))

(defn provider
  "Creates an isolated clock provider from host-supplied zero-argument wall
  (Unix milliseconds) and monotonic (nanoseconds) functions. The provider
  adds a local observation sequence and rejects invalid or regressing ticks."
  [{:keys [wall-now monotonic-now]}]
  (when-not (and (fn? wall-now) (fn? monotonic-now))
    (throw (ex-info "clock provider requires wall and monotonic functions"
                    {:phase :clock-provider})))
  (let [sequence-number (atom 0)
        last-monotonic (atom nil)]
    {:request-type request-type
     :result-type result-type
     :invoke
     (fn [[actual-type domain _]]
       (when-not (= actual-type request-type)
         (throw (ex-info "clock request contract mismatch" {:phase :clock-provider})))
       (case domain
         :wall
         (let [{:keys [value] :as observation} (read-source wall-now)
               source-error (:error observation)]
           (cond
             source-error source-error
             (not (valid-tick? value)) (error :clock/invalid "wall clock value is invalid")
             :else [result-type :wall
                    [wall-type value (swap! sequence-number inc)]]))

         :monotonic
         (let [{:keys [value] :as observation} (read-source monotonic-now)
               source-error (:error observation)]
           (cond
             source-error source-error
             (not (valid-tick? value))
             (error :clock/invalid "monotonic clock value is invalid")
             (and (some? @last-monotonic) (< value @last-monotonic))
             (error :clock/regressed "monotonic clock regressed")
             :else
             (do
               (reset! last-monotonic value)
               [result-type :monotonic
                [monotonic-type value (swap! sequence-number inc)]])))

         (throw (ex-info "unknown clock domain"
                         {:phase :clock-provider :domain domain}))))}))
