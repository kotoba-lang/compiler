(ns kotoba.compiler.provider.ui
  "Declarative bounded UI reference provider. No DOM or host object is exposed."
  (:require [kotoba.compiler.value :as value]))

(def commit-capability-id 9)
(def event-capability-id 10)
(def max-nodes 32)
(def max-events 64)

(def parent-type [:option :keyword])
(def node-type
  [:record :kotoba.ui/node
   [[:id :keyword] [:parent parent-type] [:kind :keyword] [:text :string]]])
(def node-set-type [:set node-type])
(def commit-request-type
  [:record :kotoba.ui/commit-request [[:base-revision :i64] [:nodes node-set-type]]])
(def commit-result-type
  [:record :kotoba.ui/commit-result [[:revision :i64] [:node-count :i64]]])
(def event-request-type
  [:record :kotoba.ui/event-request [[:after-revision :i64]]])
(def event-type
  [:record :kotoba.ui/event
   [[:revision :i64] [:target :keyword] [:kind :keyword] [:value :string]]])
(def event-result-type [:option event-type])

(def schemas
  {:kotoba.ui/node node-type
   :kotoba.ui/commit-request commit-request-type
   :kotoba.ui/commit-result commit-result-type
   :kotoba.ui/event-request event-request-type
   :kotoba.ui/event event-type})

(defn create-provider
  "Returns exact provider entries plus host-only enqueue!/snapshot functions.
  Only the :provider values are installed in the guest runtime registry."
  []
  (let [view (atom {:revision 0 :nodes []})
        events (atom [])
        event-revision (atom 0)
        enqueue!
        (fn [target kind text]
          (value/bounded-keyword! target value/keyword-value-byte-limit)
          (value/bounded-keyword! kind value/keyword-value-byte-limit)
          (value/bounded-string! text value/string-value-byte-limit)
          (when (>= (count @events) max-events)
            (throw (ex-info "UI event queue limit reached" {:phase :ui-provider})))
          (let [revision (swap! event-revision inc)
                event [event-type revision target kind text]]
            (swap! events conj event)
            revision))]
    {:providers
     {commit-capability-id
      {:request-type commit-request-type
       :result-type commit-result-type
       :invoke
       (fn [[actual-type base-revision [_ nodes]]]
         (when-not (= actual-type commit-request-type)
           (throw (ex-info "UI commit contract mismatch" {:phase :ui-provider})))
         (when-not (= base-revision (:revision @view))
           (throw (ex-info "UI revision conflict"
                           {:phase :ui-provider :expected (:revision @view)
                            :actual base-revision})))
         (when (> (count nodes) max-nodes)
           (throw (ex-info "UI node limit reached" {:phase :ui-provider})))
         (let [ids (mapv second nodes)
               id-set (set ids)]
           (when-not (= (count ids) (count id-set))
             (throw (ex-info "UI node ids must be unique" {:phase :ui-provider})))
           (doseq [[_ id [_ has-parent? parent] _ _] nodes]
             (when (and has-parent? (not (contains? id-set parent)))
               (throw (ex-info "UI node parent is missing"
                               {:phase :ui-provider :node id :parent parent}))))
           (let [revision (inc base-revision)]
             (reset! view {:revision revision :nodes (vec nodes)})
             [commit-result-type revision (count nodes)])))}

      event-capability-id
      {:request-type event-request-type
       :result-type event-result-type
       :invoke
       (fn [[actual-type after-revision]]
         (when-not (= actual-type event-request-type)
           (throw (ex-info "UI event request contract mismatch" {:phase :ui-provider})))
         (if-let [event (first (drop-while #(<= (second %) after-revision) @events))]
           (do (swap! events (fn [queued] (vec (remove #(= event %) queued))))
               [event-result-type true event])
           [event-result-type false]))}}
     :enqueue! enqueue!
     :snapshot #(deref view)}))
