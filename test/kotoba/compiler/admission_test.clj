(ns kotoba.compiler.admission-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]))

(def effect-source
  "(defn audit [x] (cap-call 7 x))
   (defn helper [x] (audit x))
   (defn main [] 42)")

(def hybrid-policy
  {:kotoba.security/crypto-policy-version 1 :mode :hybrid-required
   :hybrid-epoch-floor 1})

(def hybrid-envelope
  {:envelope/provider {:provider/id :kagi :provider/fips-validated false}
   :envelope/kem? true :envelope/hybrid? true :envelope/epoch 2
   :envelope/algorithms [:x25519 :ml-kem-768]})

(deftest production-artifact-admission-requires-real-hybrid-pqc
  (let [base {:allow #{[:cap/call 7]} :crypto-required? true
              :crypto-policy hybrid-policy :crypto-envelope hybrid-envelope}]
    (is (true? (get-in (compiler/check-source effect-source base)
                       [:admission :crypto :valid?])))
    (doseq [envelope [(assoc hybrid-envelope :envelope/algorithms [:x25519])
                      (assoc hybrid-envelope :envelope/hybrid? false)
                      (assoc hybrid-envelope :envelope/epoch 0)]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"hybrid PQC policy denies compilation"
                            (compiler/check-source effect-source
                                                   (assoc base :crypto-envelope envelope)))))))

(deftest effects-propagate-and-all-exports-are-covered
  (let [{:keys [hir admission]}
        (compiler/check-source effect-source {:allow #{[:cap/call 7] [:cap/call 9]}})
        by-name (into {} (map (juxt :name identity) (:functions hir)))]
    (is (= #{[:cap/call 7]} (:effects hir)))
    (is (= #{[:cap/call 7]} (:effects (get by-name 'audit))))
    (is (= #{[:cap/call 7]} (:effects (get by-name 'helper))))
    (is (= #{} (:effects (get by-name 'main))))
    (is (= {:allow #{[:cap/call 7]}} (:minimal-policy admission)))
    (is (= #{[:cap/call 9]} (:unused-grants admission)))))

(deftest admission-is-deny-by-default
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denies required effects"
                        (compiler/check-source effect-source)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denies required effects"
                        (compiler/check-source effect-source {:allow #{[:cap/call 8]}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed capability policy"
                        (compiler/check-source effect-source {:allow #{[:network "*"]}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed capability policy"
                        (compiler/check-source effect-source {:allow #{[:cap/call 999]}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed capability policy"
                        (compiler/check-source effect-source
                                               {:allow #{[:cap/call 7]} :ignored true})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed capability policy"
                        (compiler/check-source effect-source {:allow [[:cap/call 7]]}))))

(deftest admission-enforces-shared-four-axis-abac
  (let [policy {:allow #{[:cap/call 7]}
                :attributes {:subject {:id :builder :tenant "alpha"}
                             :resource {:tenant "alpha" :trust :reviewed}
                             :environment {:surface :ci :device-trusted? true}}
                :abac {:policy/id :compiler/release
                       :subject/ids #{:builder}
                       :resource/tenants #{"alpha"}
                       :resource/trust #{:reviewed}
                       :action/ids #{:compiler/admit}
                       :action/capabilities #{[:cap/call 7]}
                       :environment/surfaces #{:ci}
                       :environment/require-device-trust? true
                       :tenant/isolation? true}}
        admission (:admission (compiler/check-source effect-source policy))]
    (is (true? (get-in admission [:abac :abac/allowed?])))
    (is (= :compiler/release (get-in admission [:abac :abac/policy-id])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"ABAC policy denies compilation"
         (compiler/check-source effect-source
                                (assoc-in policy [:attributes :environment :surface]
                                          :developer-laptop))))))

(deftest compiler-prevents-implicit-classification-downgrade
  (let [base {:allow #{[:cap/call 7]}
              :attributes {:subject {:id :release-bot}
                           :environment {:now "2026-07-19T12:00:00Z"}}
              :information-flow {:input-classifications [:confidential]
                                 :output-classification :public}}
        grant {:id :release-redaction :subject :release-bot :purpose :release
               :from :confidential :to :public
               :expires-at "2026-07-20T00:00:00Z"}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"information-flow policy denies compilation"
                          (compiler/check-source effect-source base)))
    (is (true?
         (get-in (compiler/check-source
                  effect-source
                  (-> base
                      (assoc-in [:attributes :purpose] :release)
                      (assoc-in [:information-flow :purpose] :release)
                      (assoc-in [:information-flow :declassification-grant] grant)))
                 [:admission :information-flow :information-flow/allowed?])))))

(deftest dynamic-capability-identifiers-and-native-codegen-fail-closed
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal capability id"
                        (compiler/check-source
                         "(defn f [cap x] (cap-call cap x)) (defn main [] 0)"
                         {:allow #{[:cap/call 7]}})))
  (let [wasm (compiler/compile-source effect-source :wasm32-kotoba-v1
                                      {:allow #{[:cap/call 7]}})]
    (is (= #{[:cap/call 7]} (get-in wasm [:admission :required])))
    (is (= [0 97 115 109] (mapv #(bit-and (int %) 0xff) (take 4 (:bytes wasm))))))
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (let [artifact (:artifact (compiler/compile-source effect-source target
                                                       {:allow #{[:cap/call 7]}}))]
      (is (= #{[:cap/call 7]} (:effects artifact)))
      (is (= {:version 2 :fuel-offset 8 :allow-bitmap-offset 16
              :allow-bitmap-bytes 32 :cap-call-offset 48
              :pair-new-offset 56 :pair-first-offset 64
              :pair-second-offset 72 :pair-capacity 4096
              :kgraph-assert-offset 80 :kgraph-get-offset 88
              :kgraph-count-offset 96 :kgraph-entity-at-offset 104
              :kgraph-capacity 4096
              :string-equal-offset 112 :string-concat-offset 120
              :string-pool-capacity 65536}
             (:context-abi artifact))))))

(deftest mutual-call-effects-reach-fixpoint
  (let [source "(defn left [x] (if (= x 0) (cap-call 3 x) (right (- x 1))))
                (defn right [x] (left x))
                (defn main [] 0)"
        hir (:hir (compiler/check-source source {:allow #{[:cap/call 3]}}))
        effects (into {} (map (juxt :name :effects) (:functions hir)))]
    (is (= #{[:cap/call 3]} (get effects 'left)))
    (is (= #{[:cap/call 3]} (get effects 'right)))))

(deftest effects-cannot-hide-in-lexical-bindings
  (let [source "(defn hidden [x] (let [y (cap-call 5 x)] (+ y 1)))
                (defn main [] 0)"
        hir (:hir (compiler/check-source source {:allow #{[:cap/call 5]}}))]
    (is (= #{[:cap/call 5]} (:effects hir)))))
