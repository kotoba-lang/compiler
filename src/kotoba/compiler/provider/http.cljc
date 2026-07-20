(ns kotoba.compiler.provider.http
  "Bounded HTTPS reference provider. Network authority remains host-owned."
  (:require [clojure.string :as string]
            [kotoba.compiler.value :as value]))

(def capability-id 4)
(def max-headers 32)
(def max-timeout-ms 30000)
(def max-url-bytes 4096)

(def header-type
  [:record :kotoba.http/header [[:name :keyword] [:value :string]]])
(def header-set-type [:set header-type])
(def request-type
  [:record :kotoba.http/post-request
   [[:url :string] [:headers header-set-type] [:body :string] [:timeout-ms :i64]]])
(def response-type
  [:record :kotoba.http/response
   [[:status :i64] [:headers header-set-type] [:body :string]]])
(def error-type
  [:record :kotoba.http/error
   [[:code :keyword] [:message :string] [:retryable :bool]]])
(def result-type
  [:variant :kotoba.http/result [[:ok response-type] [:error error-type]]])

(def schemas
  {:kotoba.http/header header-type
   :kotoba.http/post-request request-type
   :kotoba.http/response response-type
   :kotoba.http/error error-type
   :kotoba.http/result result-type})

(defn- https-origin [url]
  (value/bounded-string! url max-url-bytes)
  (when (string/includes? url "#")
    (throw (ex-info "HTTP URL fragments are not admitted" {:phase :http-provider})))
  (if-let [[_ host port]
           (re-matches #"https://([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(?::([0-9]+))?(?:/[^ ]*)?"
                       url)]
    (str "https://" (string/lower-case host) (when port (str ":" port)))
    (throw (ex-info "HTTP URL must be an absolute HTTPS URL"
                    {:phase :http-provider :url url}))))

(defn- validate-headers! [headers]
  (when (> (count headers) max-headers)
    (throw (ex-info "HTTP header limit reached" {:phase :http-provider})))
  (let [names (mapv second headers)]
    (when-not (= (count names) (count (set names)))
      (throw (ex-info "HTTP header names must be unique" {:phase :http-provider})))
    (doseq [[_ name text] headers]
      (value/bounded-keyword! name value/keyword-value-byte-limit)
      (value/bounded-string! text value/string-value-byte-limit)))
  headers)

(defn- header-map [headers]
  (into {} (map (fn [[_ name text]] [name text])) headers))

(defn- typed-headers [headers]
  (when-not (map? headers)
    (throw (ex-info "HTTP transport headers must be a map" {:phase :http-provider})))
  (let [items (mapv (fn [[name text]] [header-type name text]) headers)]
    (validate-headers! items)
    [header-set-type items]))

(defn- error [code message retryable]
  (value/bounded-keyword! code value/keyword-value-byte-limit)
  (value/bounded-string! message value/string-value-byte-limit)
  [result-type :error [error-type code message retryable]])

(defn- invoke-transport [transport request]
  (try
    (transport request)
    (catch #?(:clj Throwable :cljs :default) _
      {:error {:code :http/transport
               :message "transport failed"
               :retryable false}})))

(defn provider
  "Creates an HTTPS POST provider around a host-supplied synchronous transport.
  `allowed-origins` is an exact, closed set such as #{\"https://api.example\"}.
  The transport receives a plain host map and must return either
  {:status i64 :headers {keyword string} :body string} or
  {:error {:code keyword :message string :retryable bool}}."
  [{:keys [allowed-origins transport]}]
  (when-not (and (set? allowed-origins) (every? string? allowed-origins))
    (throw (ex-info "HTTP allowed-origins must be a set of strings"
                    {:phase :http-provider})))
  (doseq [origin allowed-origins]
    (when-not (= origin (https-origin origin))
      (throw (ex-info "HTTP allowed origin must be canonical"
                      {:phase :http-provider :origin origin}))))
  (when-not (fn? transport)
    (throw (ex-info "HTTP transport must be a function" {:phase :http-provider})))
  {:request-type request-type
   :result-type result-type
   :invoke
   (fn [[actual-type url [_ headers] body timeout-ms]]
     (when-not (= actual-type request-type)
       (throw (ex-info "HTTP request contract mismatch" {:phase :http-provider})))
     (let [origin (https-origin url)]
       (when-not (contains? allowed-origins origin)
         (throw (ex-info "HTTP origin is not allowed"
                         {:phase :http-provider :origin origin}))))
     (validate-headers! headers)
     (value/bounded-string! body value/string-value-byte-limit)
     (when-not (<= 1 timeout-ms max-timeout-ms)
       (throw (ex-info "HTTP timeout is outside the admitted range"
                       {:phase :http-provider :timeout-ms timeout-ms})))
     (let [reply (invoke-transport
                  transport {:url url :headers (header-map headers)
                             :body body :timeout-ms timeout-ms})]
       (if-let [{:keys [code message retryable]} (:error reply)]
         (error code message retryable)
         (let [{:keys [status headers body]} reply]
           (when-not (<= 100 status 599)
             (throw (ex-info "HTTP transport status is invalid"
                             {:phase :http-provider :status status})))
           (value/bounded-string! body value/string-value-byte-limit)
           [result-type :ok [response-type status (typed-headers headers) body]]))))})
