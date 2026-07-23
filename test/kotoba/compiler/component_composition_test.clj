(ns kotoba.compiler.component-composition-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.component-artifact :as artifact]
            [kotoba.compiler.component-composition :as composition]
            [kotoba.compiler.component-core :as component-core]
            [kotoba.compiler.component-wit :as wit]))

(def capability-kir
  {:format :kotoba.kir/v4 :exports ['invoke] :schemas {}
   :functions [{:name 'invoke :params ['request]
                :param-types [:i64] :result :i64
                :body '(typed-cap-call 4 :i64 :i64 request)}]})

(deftest scalar-provider-closes-the-application-world
  (let [world (wit/emit capability-kir)
        application (artifact/package
                     (component-core/emit capability-kir :wasm32-wasi-kotoba-v1)
                     capability-kir world)
        provider (composition/package-scalar-identity-provider :http/post :i64)
        closed (composition/compose-closed application [provider])]
    (is (= :wasm-component-provider/v1 (:format provider)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [:http/post] (:application-imports closed)))
    (is (= [{:capability :http/post :descriptor :i64}] (:providers closed)))
    (is (= [0 97 115 109 13 0 1 0]
           (mapv #(bit-and (int %) 0xff) (take 8 (:bytes closed)))))))

(deftest composition-fails-closed-without-the-required-provider
  (let [world (wit/emit capability-kir)
        application (artifact/package
                     (component-core/emit capability-kir :wasm32-wasi-kotoba-v1)
                     capability-kir world)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not exactly close"
                          (composition/compose-closed application [])))))

(deftest sealed-scalar-record-provider-closes-the-application-world
  (let [descriptor [:ref :demo/point]
        schema [:record :demo/point [[:x :i64] [:weight :f64] [:visible :bool]]]
        schemas {:demo/point schema}
        kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result descriptor
                          :body (list 'typed-cap-call 4 descriptor descriptor 'request)}]}
        world (wit/emit kir)
        application (artifact/package
                     (component-core/emit kir :wasm32-wasi-kotoba-v1) kir world)
        provider (composition/package-record-identity-provider
                  :http/post descriptor schemas)
        closed (composition/compose-closed application [provider])]
    (is (= :record-capability-call (:canonical-lowering application)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [{:capability :http/post :descriptor descriptor}]
           (:providers closed)))))

(deftest structured-capability-boundary-remains-sealed-and-scalar
  (let [descriptor [:ref :demo/point]
        schema [:record :demo/point [[:x :i64] [:label :string]]]
        kir {:format :kotoba.kir/v4 :exports ['invoke]
             :schemas {:demo/point schema}
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result descriptor
                          :body (list 'typed-cap-call 4 descriptor descriptor 'request)}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component-core/emit kir :wasm32-wasi-kotoba-v1)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sealed scalar schema"
                          (composition/package-record-identity-provider
                           :http/post descriptor (:schemas kir))))))

(deftest scalar-only-variant-provider-closes-the-application-world
  ;; ADR 0055: a variant crosses a `typed-cap-call` boundary and is closed by
  ;; a real composed (application + provider) component for the first time.
  ;; `demo/flag-or-ratio` is the exact ADR 0052 bool/f32 join-table fixture,
  ;; proving the full `join-core-type`/`variant-coerce-ops` table survives a
  ;; real cross-component crossing, not only a single-module identity export.
  (let [descriptor [:ref :demo/flag-or-ratio]
        schemas {:demo/flag-or-ratio [:variant :demo/flag-or-ratio
                                      [[:urgent :bool] [:weight :f32]]]}
        kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result descriptor
                          :body (list 'typed-cap-call 8 descriptor descriptor 'request)}]}
        world (wit/emit kir)
        application (artifact/package
                     (component-core/emit kir :wasm32-wasi-kotoba-v1) kir world)
        provider (composition/package-variant-identity-provider
                  :state/transact descriptor schemas)
        closed (composition/compose-closed application [provider])]
    (is (= :variant-capability-call (:canonical-lowering application)))
    (is (= :wasm-component-provider/v1 (:format provider)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [{:capability :state/transact :descriptor descriptor}]
           (:providers closed)))
    (is (= [0 97 115 109 13 0 1 0]
           (mapv #(bit-and (int %) 0xff) (take 8 (:bytes closed)))))))

(deftest variant-with-record-case-closes-the-application-world
  ;; ADR 0056: a case wrapping a sealed all-scalar record now crosses a
  ;; `typed-cap-call` boundary too (ADR 0055 found this blocked at the `wac
  ;; plug` layer with `type not valid to be used as import`; `wac` 0.10.0
  ;; fixes it -- bytecodealliance/wac#205). `demo/state-outcome` is a
  ;; structural slice of `state-v1`'s own `result` shape: `found: entry`
  ;; (`entry` a scalar-only record here, narrower than `state-v1`'s real
  ;; keyword/string-bearing `entry`) / `missing: bool`.
  (let [descriptor [:ref :demo/state-outcome]
        schemas {:demo/state-entry [:record :demo/state-entry [[:key :i64] [:value :i64]]]
                 :demo/state-outcome [:variant :demo/state-outcome
                                      [[:found [:ref :demo/state-entry]] [:missing :bool]]]}
        kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result descriptor
                          :body (list 'typed-cap-call 8 descriptor descriptor 'request)}]}
        world (wit/emit kir)
        application (artifact/package
                     (component-core/emit kir :wasm32-wasi-kotoba-v1) kir world)
        provider (composition/package-variant-identity-provider
                  :state/transact descriptor schemas)
        closed (composition/compose-closed application [provider])]
    (is (= :variant-capability-call (:canonical-lowering application)))
    (is (= :wasm-component-provider/v1 (:format provider)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [{:capability :state/transact :descriptor descriptor}]
           (:providers closed)))
    (is (= [0 97 115 109 13 0 1 0]
           (mapv #(bit-and (int %) 0xff) (take 8 (:bytes closed)))))))

(deftest variant-with-string-keyword-record-case-boundary-remains-sealed
  ;; A case wrapping a sealed *string/keyword-bearing* record (the ADR 0053
  ;; shape) remains fail-closed for a capability-call boundary -- ADR 0056
  ;; only widened admission to the all-scalar record case ADR 0052 already
  ;; proved for identity-export; string/keyword data crossing a
  ;; capability-call boundary at all is a separate, still-unattempted gap
  ;; (see ADR 0056's 'Remaining gaps'), not something the `wac` fix touches.
  ;; `component-core/emit` rejects it at admission (before any encoding is
  ;; attempted), and `package-variant-identity-provider` independently
  ;; rejects it too, so the boundary fails closed at both layers rather than
  ;; surfacing only as a downstream `wac` encoding error.
  (let [descriptor [:ref :demo/cap-outcome]
        schemas {:demo/cap-entry [:record :demo/cap-entry
                                  [[:key :keyword] [:value :string] [:version :i64]]]
                 :demo/cap-outcome [:variant :demo/cap-outcome
                                    [[:found [:ref :demo/cap-entry]] [:missing :bool]]]}
        kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result descriptor
                          :body (list 'typed-cap-call 8 descriptor descriptor 'request)}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component-core/emit kir :wasm32-wasi-kotoba-v1)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"scalar or sealed all-scalar record cases"
                          (composition/package-variant-identity-provider
                           :state/transact descriptor schemas)))))
