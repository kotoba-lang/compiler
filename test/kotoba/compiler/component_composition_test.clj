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

(deftest variant-with-string-keyword-record-case-closes-the-application-world
  ;; ADR 0057: a case wrapping a sealed *string/keyword-bearing* record (the
  ;; ADR 0053 shape) now crosses a `typed-cap-call` boundary too, closing
  ;; the exact gap both ADR 0055 and ADR 0056 recorded as separately
  ;; unattempted (string/keyword data crossing a capability-call boundary at
  ;; all, independent of the `wac plug` defect ADR 0056 fixed).
  ;; `demo/cap-entry` is `state-v1`'s own real `entry` shape exactly
  ;; (`key: keyword, value: string, version: i64`); `demo/cap-outcome`
  ;; (`found: entry`/`missing: bool`) is a structural slice of `state-v1`'s
  ;; own `result` shape, deliberately narrower only in using the same
  ;; identity as both request and result (matching ADR 0055/0056's own
  ;; same-identity discipline for a wiring-only identity provider).
  (let [descriptor [:ref :demo/cap-outcome]
        schemas {:demo/cap-entry [:record :demo/cap-entry
                                  [[:key :keyword] [:value :string] [:version :i64]]]
                 :demo/cap-outcome [:variant :demo/cap-outcome
                                    [[:found [:ref :demo/cap-entry]] [:missing :bool]]]}
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

(deftest variant-with-nested-record-case-boundary-remains-sealed
  ;; A case wrapping an ADR 0051 one-level-nested record (a field whose own
  ;; type is itself a sealed record, rather than a scalar or a string/
  ;; keyword leaf) remains fail-closed for a capability-call boundary -- ADR
  ;; 0057 widened admission to exactly the ADR 0053 flat string/keyword-
  ;; bearing record shape, not one level deeper. `component-core/emit`
  ;; rejects it at admission (before any encoding is attempted), and
  ;; `package-variant-identity-provider` independently rejects it too, so
  ;; the boundary fails closed at both layers rather than surfacing only as
  ;; a downstream `wac` encoding error.
  (let [descriptor [:ref :demo/cap-nested-outcome]
        schemas {:demo/cap-inner [:record :demo/cap-inner [[:count :i64]]]
                 :demo/cap-nested-entry [:record :demo/cap-nested-entry
                                         [[:inner [:ref :demo/cap-inner]] [:label :string]]]
                 :demo/cap-nested-outcome [:variant :demo/cap-nested-outcome
                                           [[:found [:ref :demo/cap-nested-entry]]
                                            [:missing :bool]]]}
        kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result descriptor
                          :body (list 'typed-cap-call 8 descriptor descriptor 'request)}]}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component-core/emit kir :wasm32-wasi-kotoba-v1)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"scalar, sealed all-scalar record, or sealed string/keyword-bearing record cases"
                          (composition/package-variant-identity-provider
                           :state/transact descriptor schemas)))))

(deftest asymmetric-scalar-variant-provider-closes-the-application-world
  ;; ADR 0058: request and result are now allowed to be two DIFFERENT sealed
  ;; variant identities -- the exact dimension every capability-call ADR
  ;; through 0057 named as still unattempted. `demo/flag-or-ratio` (the ADR
  ;; 0052/0055 bool/f32 request) crosses to `demo/status-outcome` (a
  ;; genuinely different, differently-shaped bool/f64 result) through a real
  ;; composed application-plus-provider component for the first time with
  ;; request-type != result-type.
  (let [descriptor [:ref :demo/flag-or-ratio]
        result-descriptor [:ref :demo/status-outcome]
        schemas {:demo/flag-or-ratio [:variant :demo/flag-or-ratio
                                      [[:urgent :bool] [:weight :f32]]]
                 :demo/status-outcome [:variant :demo/status-outcome
                                       [[:ready :bool] [:failed :f64]]]}
        kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result result-descriptor
                          :body (list 'typed-cap-call 8 descriptor result-descriptor 'request)}]}
        world (wit/emit kir)
        application (artifact/package
                     (component-core/emit kir :wasm32-wasi-kotoba-v1) kir world)
        provider (composition/package-variant-asymmetric-provider
                  :state/transact descriptor result-descriptor schemas)
        closed (composition/compose-closed application [provider])]
    (is (= :different-variant-capability-call (:canonical-lowering application)))
    (is (= :wasm-component-provider/v1 (:format provider)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [{:capability :state/transact :descriptor descriptor}]
           (:providers closed)))
    (is (= [0 97 115 109 13 0 1 0]
           (mapv #(bit-and (int %) 0xff) (take 8 (:bytes closed)))))))

(deftest asymmetric-record-case-variant-provider-closes-the-application-world
  ;; The different-identity crossing also admits a case wrapping a sealed
  ;; all-scalar record (ADR 0052/0056) on either side, not only a bare
  ;; scalar -- `demo/cap-outcome` (`tally: cap-tally`/`empty: bool`) crosses
  ;; to `demo/other-record-outcome` (`total: cap-total`), two different
  ;; record-cased variants.
  (let [descriptor [:ref :demo/cap-outcome]
        result-descriptor [:ref :demo/other-record-outcome]
        schemas {:demo/cap-tally [:record :demo/cap-tally [[:count :i64]]]
                 :demo/cap-outcome [:variant :demo/cap-outcome
                                    [[:tally [:ref :demo/cap-tally]] [:empty :bool]]]
                 :demo/cap-total [:record :demo/cap-total [[:sum :i64] [:ok :bool]]]
                 :demo/other-record-outcome [:variant :demo/other-record-outcome
                                             [[:total [:ref :demo/cap-total]]]]}
        kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result result-descriptor
                          :body (list 'typed-cap-call 8 descriptor result-descriptor 'request)}]}
        world (wit/emit kir)
        application (artifact/package
                     (component-core/emit kir :wasm32-wasi-kotoba-v1) kir world)
        provider (composition/package-variant-asymmetric-provider
                  :state/transact descriptor result-descriptor schemas)
        closed (composition/compose-closed application [provider])]
    (is (= :different-variant-capability-call (:canonical-lowering application)))
    (is (= :wasm-component-provider/v1 (:format provider)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [0 97 115 109 13 0 1 0]
           (mapv #(bit-and (int %) 0xff) (take 8 (:bytes closed)))))))

(deftest asymmetric-state-shaped-variant-provider-closes-the-application-world
  ;; The closest this ADR chain has come to `state-v1`'s own literal shape:
  ;; a 3-case request (`get`/`put`/`delete`, matching `state-v1.edn`'s own
  ;; case names) crossing to a genuinely different 5-case result
  ;; (`found`/`missing`/`written`/`deleted`/`error`, also matching
  ;; `state-v1.edn`'s own case names, with `found`/`written` sharing one
  ;; record type exactly as the real `state-v1` result does). Deliberately
  ;; narrower than `state-v1`'s own literal EDN only in field *type*: every
  ;; field here is `:i64`, a structural stand-in for `state-v1`'s real
  ;; `:keyword`/`:string` fields, exactly mirroring ADR 0056's own
  ;; `demo/state-outcome` precedent (a structural stand-in narrower only in
  ;; field type, not in case shape/count/name) before ADR 0057 later closed
  ;; the string/keyword-field gap for the *same*-identity path. Closing the
  ;; same gap for this *different*-identity path is explicitly out of scope
  ;; for this ADR -- see its own "Remaining gaps".
  (let [descriptor [:ref :demo/state-request]
        result-descriptor [:ref :demo/state-result]
        schemas {:demo/get-request [:record :demo/get-request [[:key :i64]]]
                 :demo/put-request [:record :demo/put-request [[:key :i64] [:value :i64]]]
                 :demo/delete-request [:record :demo/delete-request [[:key :i64]]]
                 :demo/state-request [:variant :demo/state-request
                                      [[:get [:ref :demo/get-request]]
                                       [:put [:ref :demo/put-request]]
                                       [:delete [:ref :demo/delete-request]]]]
                 :demo/state-entry [:record :demo/state-entry
                                    [[:key :i64] [:value :i64] [:version :i64]]]
                 :demo/state-error [:record :demo/state-error [[:code :i64] [:message :i64]]]
                 :demo/state-result [:variant :demo/state-result
                                     [[:found [:ref :demo/state-entry]]
                                      [:missing :bool]
                                      [:written [:ref :demo/state-entry]]
                                      [:deleted :bool]
                                      [:error [:ref :demo/state-error]]]]}
        kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result result-descriptor
                          :body (list 'typed-cap-call 8 descriptor result-descriptor 'request)}]}
        world (wit/emit kir)
        application (artifact/package
                     (component-core/emit kir :wasm32-wasi-kotoba-v1) kir world)
        provider (composition/package-variant-asymmetric-provider
                  :state/transact descriptor result-descriptor schemas)
        closed (composition/compose-closed application [provider])]
    (is (= :different-variant-capability-call (:canonical-lowering application)))
    (is (= :wasm-component-provider/v1 (:format provider)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [0 97 115 109 13 0 1 0]
           (mapv #(bit-and (int %) 0xff) (take 8 (:bytes closed)))))))

(deftest asymmetric-variant-provider-rejects-same-identity-and-string-cases
  ;; The provider-side layer fails closed independently of the KIR-admission
  ;; layer, matching every prior ADR's double-layer discipline: same-identity
  ;; (that is `variant-capability-call`'s own path, not this one) and a
  ;; string/keyword-bearing case (out of scope for this ADR's narrower
  ;; case-kind set) are both rejected by `package-variant-asymmetric-provider`
  ;; itself.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires two distinct admitted scalar-or-record variant identities"
       (composition/package-variant-asymmetric-provider
        :state/transact [:ref :demo/flag-or-ratio] [:ref :demo/flag-or-ratio]
        {:demo/flag-or-ratio [:variant :demo/flag-or-ratio [[:urgent :bool] [:weight :f32]]]})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires two distinct admitted scalar-or-record variant identities"
       (composition/package-variant-asymmetric-provider
        :state/transact [:ref :demo/cap-string-outcome] [:ref :demo/other-outcome]
        {:demo/cap-entry [:record :demo/cap-entry
                          [[:key :keyword] [:value :string] [:version :i64]]]
         :demo/cap-string-outcome [:variant :demo/cap-string-outcome
                                   [[:found [:ref :demo/cap-entry]] [:missing :bool]]]
         :demo/other-outcome [:variant :demo/other-outcome [[:urgent :bool]]]}))))
