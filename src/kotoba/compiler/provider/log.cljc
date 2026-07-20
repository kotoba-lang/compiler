(ns kotoba.compiler.provider.log
  "Bounded structured log reference provider. No host logger object is exposed."
  (:require [kotoba.compiler.value :as value]))

(def read-capability-id 5)
(def append-capability-id 6)
(def max-fields 4)
(def max-read-entries 8)
(def max-retained-entries 256)

(def field-type
  [:record :kotoba.log/field [[:key :keyword] [:value :string]]])
(def field-set-type [:set field-type])
(def append-request-type
  [:record :kotoba.log/append-request
   [[:level :keyword] [:event :keyword] [:message :string] [:fields field-set-type]]])
(def entry-type
  [:record :kotoba.log/entry
   [[:sequence :i64] [:level :keyword] [:event :keyword]
    [:message :string] [:fields field-set-type]]])
(def append-result-type
  [:record :kotoba.log/append-result [[:sequence :i64]]])
(def read-request-type
  [:record :kotoba.log/read-request [[:after-sequence :i64] [:limit :i64]]])
(def entry-set-type [:set entry-type])
(def read-result-type
  [:record :kotoba.log/read-result
   [[:oldest-sequence :i64] [:latest-sequence :i64]
    [:truncated :bool] [:entries entry-set-type]]])

(def schemas
  {:kotoba.log/field field-type
   :kotoba.log/append-request append-request-type
   :kotoba.log/entry entry-type
   :kotoba.log/append-result append-result-type
   :kotoba.log/read-request read-request-type
   :kotoba.log/read-result read-result-type})

(defn- validate-fields! [fields]
  (when (> (count fields) max-fields)
    (throw (ex-info "log field limit reached" {:phase :log-provider})))
  (let [keys (mapv second fields)]
    (when-not (= (count keys) (count (set keys)))
      (throw (ex-info "log field keys must be unique" {:phase :log-provider})))
    (doseq [[_ key text] fields]
      (value/bounded-keyword! key value/keyword-value-byte-limit)
      (value/bounded-string! text value/string-value-byte-limit)))
  fields)

(defn create-provider
  "Creates an isolated host-owned structured audit buffer. The returned
  :providers map is guest-installable; :snapshot is a host-only inspection
  function. When the retention bound is exceeded the oldest entry is removed,
  and read results explicitly report whether the requested cursor was truncated."
  []
  (let [entries (atom [])
        sequence-number (atom 0)
        append-provider
        {:request-type append-request-type
         :result-type append-result-type
         :invoke
         (fn [[actual-type level event message [_ fields]]]
           (when-not (= actual-type append-request-type)
             (throw (ex-info "log append contract mismatch" {:phase :log-provider})))
           (value/bounded-keyword! level value/keyword-value-byte-limit)
           (value/bounded-keyword! event value/keyword-value-byte-limit)
           (value/bounded-string! message value/string-value-byte-limit)
           (validate-fields! fields)
           (let [sequence (swap! sequence-number inc)
                 entry [entry-type sequence level event message [field-set-type fields]]]
             (swap! entries
                    (fn [current]
                      (let [retained (conj current entry)]
                        (if (> (count retained) max-retained-entries)
                          (subvec retained 1)
                          retained))))
             [append-result-type sequence]))}
        read-provider
        {:request-type read-request-type
         :result-type read-result-type
         :invoke
         (fn [[actual-type after-sequence limit]]
           (when-not (= actual-type read-request-type)
             (throw (ex-info "log read contract mismatch" {:phase :log-provider})))
           (when (neg? after-sequence)
             (throw (ex-info "log read cursor must be non-negative"
                             {:phase :log-provider :after-sequence after-sequence})))
           (when-not (<= 1 limit max-read-entries)
             (throw (ex-info "log read limit is outside the admitted range"
                             {:phase :log-provider :limit limit})))
           (let [current @entries
                 latest @sequence-number
                 oldest (if (seq current) (second (first current)) (inc latest))
                 truncated (< after-sequence (dec oldest))
                 selected (->> current
                               (drop-while #(<= (second %) after-sequence))
                               (take limit)
                               vec)]
             [read-result-type oldest latest truncated [entry-set-type selected]]))}]
    {:providers {read-capability-id read-provider
                 append-capability-id append-provider}
     :snapshot #(deref entries)}))
