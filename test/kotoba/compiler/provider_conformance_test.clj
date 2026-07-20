(ns kotoba.compiler.provider-conformance-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.clock :as clock]
            [kotoba.compiler.provider.conformance :as conformance]
            [kotoba.compiler.provider.http :as http]
            [kotoba.compiler.provider.llm :as llm]
            [kotoba.compiler.provider.log :as log]
            [kotoba.compiler.provider.state :as state]
            [kotoba.compiler.provider.storage :as storage]
            [kotoba.compiler.provider.ui :as ui]
            [kotoba.compiler.reference-runtime :as runtime]))

(defn- read-resource [path]
  (edn/read-string (slurp (io/resource path))))

(defn- fixtures []
  (let [ui-kit (ui/create-provider)
        log-kit (log/create-provider)]
    [{:name :state/transact :id 8 :provider (state/provider)}
     {:name :ui/commit :id 9 :provider (get-in ui-kit [:providers 9])}
     {:name :ui/next-event :id 10 :provider (get-in ui-kit [:providers 10])}
     {:name :http/post :id 4
      :provider (http/provider {:allowed-origins #{}
                                :transport (fn [_] {:error {:code :http/disabled
                                                            :message "disabled"
                                                            :retryable false}})})}
     {:name :llm/generate :id 11
      :provider (llm/provider {:allowed-models #{}
                               :transport (fn [_] {:error {:code :llm/disabled
                                                           :message "disabled"
                                                           :retryable false}})})}
     {:name :storage/transact :id 12
      :provider (storage/provider {:storage-namespace :conformance/storage
                                   :transport (fn [_] {:tag :error
                                                       :error {:code :storage/disabled
                                                               :message "disabled"
                                                               :retryable false}})})}
     {:name :clock/now :id 7
      :provider (clock/provider {:wall-now (constantly 0)
                                 :monotonic-now (constantly 0)})}
     {:name :log/read :id 5 :provider (get-in log-kit [:providers 5])}
     {:name :log/append :id 6 :provider (get-in log-kit [:providers 6])}]))

(deftest all-reference-kits-share-one-closed-qualification
  (let [manifest (read-resource "kotoba/lang/provider-conformance-v1.edn")
        expected (->> (:kits manifest) (mapcat :capabilities) vec)
        receipt (conformance/validate-suite! frontend/capability-registry (fixtures))]
    (is (= :kotoba.provider-conformance/v1 (:format receipt)))
    (is (= 9 (:capability-count receipt)))
    (is (= (set expected) (set (:capabilities receipt))))
    (doseq [{:keys [name version resource capabilities]} (:kits manifest)]
      (let [kit (read-resource resource)
            catalog (get-in (read-resource "kotoba/lang/application-language.edn")
                            [:host :kits name])]
        (is (= version (:kotoba.capability-kit/version kit)))
        (is (= {:version version :status :reference-implemented} catalog))
        (is (= (set capabilities)
               (set (or (:capabilities kit) [(:capability kit)]))))))))

(deftest malformed-provider-inventories-fail-closed
  (let [valid (first (fixtures))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"provider map is not exact"
         (conformance/validate-provider!
          frontend/capability-registry
          (update valid :provider assoc :ambient-host true))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not registered exactly"
         (conformance/validate-provider!
          frontend/capability-registry (assoc valid :id 255))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"duplicate ids"
         (conformance/validate-suite!
          frontend/capability-registry [valid (assoc valid :name :clock/now)])))))

(def probe-request-type
  [:record :kotoba.conformance/request [[:value :string]]])
(def probe-result-type
  [:record :kotoba.conformance/result [[:accepted :bool]]])

(def probe-source
  (str "(ns app.conformance (:export [probe]) (:capabilities #{:http/post}))"
       "(defn probe [request " (pr-str probe-request-type) "] "
       (pr-str probe-result-type) " (typed-cap-call :http/post "
       (pr-str probe-request-type) " " (pr-str probe-result-type) " request))"))

(defn- probe-kir []
  (ir/lower (:hir (compiler/check-source probe-source {:allow #{[:cap/call 4]}}))))

(deftest shared-runtime-lifecycle-fails-closed
  (let [called? (atom false)
        provider {:request-type probe-request-type
                  :result-type probe-result-type
                  :invoke (fn [_] (reset! called? true)
                            [probe-result-type true])}
        host (runtime/instantiate (probe-kir) {:allow #{4} :providers {4 provider}})]
    (is (thrown? clojure.lang.ExceptionInfo
                 ((:invoke host) 'probe [[probe-request-type 1]])))
    (is (false? @called?))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"capability denied"
         ((:invoke (runtime/instantiate (probe-kir)))
          'probe [[probe-request-type "valid"]])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"invalid-parametric-value"
         ((:invoke
           (runtime/instantiate
            (probe-kir)
            {:allow #{4}
             :providers {4 (assoc provider :invoke
                                  (fn [_] [probe-request-type "forged"]))}}))
          'probe [[probe-request-type "valid"]])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"does not match"
         (runtime/instantiate
          (probe-kir)
          {:allow #{4}
           :providers {4 (assoc provider :request-type :string)}})))))
