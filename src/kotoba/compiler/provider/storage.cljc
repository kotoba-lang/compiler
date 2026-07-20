(ns kotoba.compiler.provider.storage
  "Bounded durable-storage adapter. Paths and backend handles stay host-owned."
  (:require [kotoba.compiler.value :as value]))

(def capability-id 12)
(def max-value-bytes 65536)
(def expected-version-type [:option :i64])

(def get-type [:record :kotoba.storage/get [[:key :keyword]]])
(def put-type
  [:record :kotoba.storage/put
   [[:key :keyword] [:value :string] [:expected-version expected-version-type]]])
(def delete-type
  [:record :kotoba.storage/delete
   [[:key :keyword] [:expected-version expected-version-type]]])
(def request-type
  [:variant :kotoba.storage/request
   [[:get get-type] [:put put-type] [:delete delete-type]]])

(def entry-type
  [:record :kotoba.storage/entry
   [[:key :keyword] [:value :string] [:version :i64]]])
(def conflict-type
  [:record :kotoba.storage/conflict
   [[:key :keyword] [:current-version expected-version-type]]])
(def error-type
  [:record :kotoba.storage/error
   [[:code :keyword] [:message :string] [:retryable :bool]]])
(def result-type
  [:variant :kotoba.storage/result
   [[:found entry-type] [:missing :bool] [:written entry-type]
    [:deleted :bool] [:conflict conflict-type] [:error error-type]]])

(def schemas
  {:kotoba.storage/get get-type
   :kotoba.storage/put put-type
   :kotoba.storage/delete delete-type
   :kotoba.storage/request request-type
   :kotoba.storage/entry entry-type
   :kotoba.storage/conflict conflict-type
   :kotoba.storage/error error-type
   :kotoba.storage/result result-type})

(defn- result [tag payload]
  [result-type tag payload])

(defn- valid-version? [version]
  (and (integer? version) (<= 1 version)))

(defn- option-version [version]
  (if (nil? version)
    [expected-version-type false]
    (do
      (when-not (valid-version? version)
        (throw (ex-info "storage version is invalid" {:phase :storage-provider})))
      [expected-version-type true version])))

(defn- expected-version [[actual-type present? version]]
  (when-not (= actual-type expected-version-type)
    (throw (ex-info "storage expected-version contract mismatch"
                    {:phase :storage-provider})))
  (when present?
    (when-not (valid-version? version)
      (throw (ex-info "storage expected version is invalid"
                      {:phase :storage-provider})))
    version))

(defn- entry [key stored]
  (let [{:keys [value version]} stored]
    (value/bounded-string! value max-value-bytes)
    (when-not (valid-version? version)
      (throw (ex-info "storage version is invalid" {:phase :storage-provider})))
    [entry-type key value version]))

(defn- error [{:keys [code message retryable]}]
  (value/bounded-keyword! code value/keyword-value-byte-limit)
  (value/bounded-string! message value/string-value-byte-limit)
  (result :error [error-type code message retryable]))

(defn- invoke-transport [transport request]
  (try
    (transport request)
    (catch #?(:clj Throwable :cljs :default) _
      {:tag :error :error {:code :storage/transport
                           :message "storage provider failed"
                           :retryable false}})))

(defn- typed-result [key reply]
  (case (:tag reply)
    :found (result :found (entry key reply))
    :missing (result :missing false)
    :written (result :written (entry key reply))
    :deleted (result :deleted true)
    :conflict (result :conflict
                      [conflict-type key (option-version (:current-version reply))])
    :error (error (:error reply))
    (throw (ex-info "storage transport result tag is invalid"
                    {:phase :storage-provider :tag (:tag reply)}))))

(defn provider
  "Creates a storage provider around a host-supplied synchronous transport.
  The namespace is host-owned and never supplied by guest code. The transport
  receives {:namespace kw :operation kw :key kw ...} and returns a tagged map.
  It is responsible for durable commits, namespace quota, and atomic version
  checks; the adapter validates and types both sides of the boundary."
  [{:keys [storage-namespace transport]}]
  (when-not (and (qualified-keyword? storage-namespace)
                 (fn? transport))
    (throw (ex-info "storage provider requires a qualified namespace and transport"
                    {:phase :storage-provider})))
  (value/bounded-keyword! storage-namespace value/keyword-value-byte-limit)
  {:request-type request-type
   :result-type result-type
   :invoke
   (fn [[actual-type operation payload]]
     (when-not (= actual-type request-type)
       (throw (ex-info "storage request contract mismatch"
                       {:phase :storage-provider})))
     (case operation
       :get
       (let [[_ key] payload]
         (typed-result key
                       (invoke-transport transport
                                         {:namespace storage-namespace
                                          :operation :get :key key})))

       :put
       (let [[_ key text version-option] payload
             expected (expected-version version-option)]
         (value/bounded-string! text max-value-bytes)
         (typed-result key
                       (invoke-transport transport
                                         {:namespace storage-namespace
                                          :operation :put :key key :value text
                                          :expected-version expected})))

       :delete
       (let [[_ key version-option] payload
             expected (expected-version version-option)]
         (typed-result key
                       (invoke-transport transport
                                         {:namespace storage-namespace
                                          :operation :delete :key key
                                          :expected-version expected})))

       (throw (ex-info "unknown storage operation"
                       {:phase :storage-provider :operation operation}))))})
