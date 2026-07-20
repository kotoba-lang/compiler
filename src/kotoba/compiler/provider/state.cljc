(ns kotoba.compiler.provider.state
  "Bounded deterministic reference provider for :state/transact v1."
  (:require [kotoba.compiler.value :as value]))

(def capability-id 8)
(def max-entries 256)

(def get-type [:record :kotoba.state/get [[:key :keyword]]])
(def put-type [:record :kotoba.state/put [[:key :keyword] [:value :string]]])
(def delete-type [:record :kotoba.state/delete [[:key :keyword]]])
(def request-type
  [:variant :kotoba.state/request
   [[:get get-type] [:put put-type] [:delete delete-type]]])

(def entry-type
  [:record :kotoba.state/entry [[:key :keyword] [:value :string] [:version :i64]]])
(def error-type
  [:record :kotoba.state/error [[:code :keyword] [:message :string]]])
(def result-type
  [:variant :kotoba.state/result
   [[:found entry-type] [:missing :bool] [:written entry-type]
    [:deleted :bool] [:error error-type]]])

(def schemas
  {:kotoba.state/get get-type
   :kotoba.state/put put-type
   :kotoba.state/delete delete-type
   :kotoba.state/request request-type
   :kotoba.state/entry entry-type
   :kotoba.state/error error-type
   :kotoba.state/result result-type})

(defn- result [tag payload]
  [result-type tag payload])

(defn- entry [key {:keys [value version]}]
  [entry-type key value version])

(defn provider
  "Creates one isolated bounded state provider. State is host-owned and is
  observable by guest code only through the typed request/result contract."
  ([] (provider {}))
  ([initial]
   (when-not (and (map? initial) (<= (count initial) max-entries)
                  (every? keyword? (keys initial)) (every? string? (vals initial)))
     (throw (ex-info "invalid initial state" {:phase :state-provider})))
   (doseq [[key text] initial]
     (value/bounded-keyword! key value/keyword-value-byte-limit)
     (value/bounded-string! text value/string-value-byte-limit))
   (let [cells (atom (into {} (map (fn [[key text]] [key {:value text :version 1}])) initial))
         next-version (atom (inc (count initial)))]
     {:request-type request-type
      :result-type result-type
      :invoke
      (fn [[actual-type operation payload]]
        (when-not (= actual-type request-type)
          (throw (ex-info "state request contract mismatch" {:phase :state-provider})))
        (case operation
          :get
          (let [[_ key] payload]
            (if-let [stored (get @cells key)]
              (result :found (entry key stored))
              (result :missing false)))

          :put
          (let [[_ key text] payload]
            (value/bounded-string! text value/string-value-byte-limit)
            (if (and (not (contains? @cells key)) (>= (count @cells) max-entries))
              (result :error [error-type :state/capacity "state entry limit reached"])
              (let [version (swap! next-version inc)
                    stored {:value text :version version}]
                (swap! cells assoc key stored)
                (result :written (entry key stored)))))

          :delete
          (let [[_ key] payload
                present? (contains? @cells key)]
            (swap! cells dissoc key)
            (result :deleted present?))

          (throw (ex-info "unknown state operation" {:phase :state-provider
                                                       :operation operation}))))})))
