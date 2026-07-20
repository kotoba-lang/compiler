(ns kotoba.compiler.ui-provider-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.ui :as ui]
            [kotoba.compiler.reference-runtime :as runtime]))

(def source
  (str "(ns app.ui (:export [commit next-event]) "
       "(:capabilities #{:ui/commit :ui/next-event}))"
       "(defn commit [request " (pr-str ui/commit-request-type) "] "
       (pr-str ui/commit-result-type) " (typed-cap-call :ui/commit "
       (pr-str ui/commit-request-type) " " (pr-str ui/commit-result-type) " request))"
       "(defn next-event [request " (pr-str ui/event-request-type) "] "
       (pr-str ui/event-result-type) " (typed-cap-call :ui/next-event "
       (pr-str ui/event-request-type) " " (pr-str ui/event-result-type) " request))"))

(defn- hosted []
  (let [kit (ui/create-provider)
        kir (ir/lower (:hir (compiler/check-source
                             source {:allow #{[:cap/call 9] [:cap/call 10]}})))]
    {:kit kit
     :runtime (runtime/instantiate kir {:allow #{9 10} :providers (:providers kit)})}))

(deftest declarative-view-and-events-cross-only-typed-boundaries
  (let [{:keys [kit runtime]} (hosted)
        none [ui/parent-type false]
        node [ui/node-type :view/title none :ui/text "Hello"]
        nodes [ui/node-set-type [node]]
        commit [ui/commit-request-type 0 nodes]]
    (is (= [ui/commit-result-type 1 1]
           ((:invoke runtime) 'commit [commit])))
    (is (= {:revision 1 :nodes [node]} ((:snapshot kit))))
    (is (= 1 ((:enqueue! kit) :view/title :ui/click "open")))
    (is (= [ui/event-result-type true
            [ui/event-type 1 :view/title :ui/click "open"]]
           ((:invoke runtime) 'next-event [[ui/event-request-type 0]])))
    (is (= [ui/event-result-type false]
           ((:invoke runtime) 'next-event [[ui/event-request-type 1]])))))

(deftest stale-view-revisions-fail-closed
  (let [{:keys [runtime]} (hosted)
        nodes [ui/node-set-type []]
        request [ui/commit-request-type 1 nodes]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"revision conflict"
                          ((:invoke runtime) 'commit [request])))))
