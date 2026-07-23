(ns kotoba.compiler.component-composition-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
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

(deftest asymmetric-variant-provider-rejects-same-identity-and-nested-record-cases
  ;; The provider-side layer fails closed independently of the KIR-admission
  ;; layer, matching every prior ADR's double-layer discipline: same-identity
  ;; (that is `variant-capability-call`'s own path, not this one) and a case
  ;; wrapping an ADR 0051 one-level-nested record (never admitted by either
  ;; the same-identity or different-identity path) are both rejected by
  ;; `package-variant-asymmetric-provider` itself. A string/keyword-bearing
  ;; case is NO LONGER in this list -- ADR 0059 admits it, see
  ;; `asymmetric-state-shaped-string-keyword-variant-provider-closes-the-
  ;; application-world` below.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires two distinct admitted scalar-or-record variant identities"
       (composition/package-variant-asymmetric-provider
        :state/transact [:ref :demo/flag-or-ratio] [:ref :demo/flag-or-ratio]
        {:demo/flag-or-ratio [:variant :demo/flag-or-ratio [[:urgent :bool] [:weight :f32]]]})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"requires two distinct admitted scalar-or-record variant identities"
       (composition/package-variant-asymmetric-provider
        :state/transact [:ref :demo/cap-nested-outcome] [:ref :demo/other-outcome]
        {:demo/cap-inner [:record :demo/cap-inner [[:count :i64]]]
         :demo/cap-nested-entry [:record :demo/cap-nested-entry
                                 [[:inner [:ref :demo/cap-inner]] [:label :string]]]
         :demo/cap-nested-outcome [:variant :demo/cap-nested-outcome
                                   [[:found [:ref :demo/cap-nested-entry]] [:missing :bool]]]
         :demo/other-outcome [:variant :demo/other-outcome [[:urgent :bool]]]}))))

(deftest asymmetric-string-keyword-request-variant-provider-closes-the-application-world
  ;; ADR 0059: the different-identity crossing now also admits a case
  ;; wrapping a sealed *string/keyword-bearing* record (the ADR 0053/0057
  ;; shape) on the REQUEST side -- ADR 0058 deliberately left this
  ;; unattempted. `demo/cap-string-outcome` (`found: cap-entry`/`missing:
  ;; bool`, `cap-entry` = `state-v1`'s own real `entry` shape verbatim)
  ;; crosses to `demo/other-outcome` (bare `urgent: bool`), a genuinely
  ;; different, smaller variant with no string/keyword leaf at all -- proving
  ;; a string-bearing REQUEST can cross to a plain scalar RESULT.
  (let [descriptor [:ref :demo/cap-string-outcome]
        result-descriptor [:ref :demo/other-outcome]
        schemas {:demo/cap-entry [:record :demo/cap-entry
                                  [[:key :keyword] [:value :string] [:version :i64]]]
                 :demo/cap-string-outcome [:variant :demo/cap-string-outcome
                                           [[:found [:ref :demo/cap-entry]] [:missing :bool]]]
                 :demo/other-outcome [:variant :demo/other-outcome [[:urgent :bool]]]}
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

(deftest asymmetric-string-keyword-result-variant-provider-closes-the-application-world
  ;; The reverse pairing of the test above: the STRING/KEYWORD-bearing side
  ;; is the RESULT, not the request -- `demo/other-outcome` (bare
  ;; `urgent: bool`) crosses to `demo/cap-string-outcome` (`found: cap-
  ;; entry`/`missing: bool`), proving the widened admission and the new
  ;; independent RESULT-side memory-page sizing
  ;; (`kotoba.compiler.component-core/string-headroom-bytes`,
  ;; `plan-result-string-data`) both engage correctly when the string/
  ;; keyword leaf is on the opposite side from the prior test.
  (let [descriptor [:ref :demo/other-outcome]
        result-descriptor [:ref :demo/cap-string-outcome]
        schemas {:demo/cap-entry [:record :demo/cap-entry
                                  [[:key :keyword] [:value :string] [:version :i64]]]
                 :demo/cap-string-outcome [:variant :demo/cap-string-outcome
                                           [[:found [:ref :demo/cap-entry]] [:missing :bool]]]
                 :demo/other-outcome [:variant :demo/other-outcome [[:urgent :bool]]]}
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

(defn- ref-ify
  "Test-only structural converter from an inline-embedded variant/record EDN
  shape (exactly `resources/kotoba/lang/capability-kits/state-v1.edn`'s own
  `:request`/`:result` -- every case payload a `[:record name fields]`
  literally inline, never `[:ref name]`) into this codebase's own
  established `[:ref name]` + separate `schemas`-map convention (every
  fixture in this ADR chain, including ADR 0058's own `demo/state-request`/
  `demo/state-result`, uses this convention -- `component-composition.clj`'s
  own admission twins, unlike `component-core.clj`'s, accept `[:ref ...]`
  case payloads, matching every prior fixture, not inline `[:record ...]`).
  Preserves every identity name, case tag, field name, and field type
  unchanged -- a pure structural re-nesting, not a hand transcription, so a
  test using this function's OUTPUT is provably derived from state-v1.edn's
  own literal EDN (read via `edn/read-string`/`slurp`) rather than retyped
  by hand."
  [variant-descriptor]
  (let [[_ variant-name cases] variant-descriptor
        schemas (atom {})
        ref-cases (mapv (fn [[tag payload]]
                          (if (and (vector? payload) (= :record (first payload)))
                            (let [[_ record-name _fields] payload]
                              (swap! schemas assoc record-name payload)
                              [tag [:ref record-name]])
                            [tag payload]))
                        cases)
        ref-variant [:variant variant-name ref-cases]]
    (swap! schemas assoc variant-name ref-variant)
    {:descriptor [:ref variant-name] :schemas @schemas}))

(deftest asymmetric-state-v1-literal-shape-provider-closes-the-application-world
  ;; The full `state-v1` literal target: `descriptor`/`result-descriptor`/
  ;; `schemas` below are derived PROGRAMMATICALLY (via `ref-ify`, a pure
  ;; structural re-nesting, not a hand transcription) from
  ;; `resources/kotoba/lang/capability-kits/state-v1.edn`'s own `:request`/
  ;; `:result` EDN, read directly from the real resource file -- not a
  ;; look-alike retyped by hand. This reaches `state-v1`'s own literal
  ;; 3-case request (`get`/`put`/`delete`) crossing to its own literal
  ;; 5-case result (`found`/`missing`/`written`/`deleted`/`error`, `found`/
  ;; `written` sharing one `entry` record type), with every field exactly
  ;; `:keyword`/`:string`/`:i64` as `state-v1.edn` itself declares -- no
  ;; field-type stand-in, unlike every prior ADR in this chain. Manual
  ;; Wasmtime 42.0.1 execution of this exact composed component is recorded
  ;; in this ADR's own Evidence section (this test proves composition and
  ;; validation only, matching every prior ADR's own test-suite/manual-
  ;; evidence split in this file).
  (let [state-kit (edn/read-string (slurp (io/resource "kotoba/lang/capability-kits/state-v1.edn")))
        request (ref-ify (:request state-kit))
        result (ref-ify (:result state-kit))
        schemas (merge (:schemas request) (:schemas result))
        descriptor (:descriptor request)
        result-descriptor (:descriptor result)]
    ;; Sanity: the converter changed representation (ref vs. inline) only --
    ;; every case tag, record identity, field name, and field type below is
    ;; state-v1.edn's own, not retyped.
    (is (= [:ref :kotoba.state/request] descriptor))
    (is (= [:ref :kotoba.state/result] result-descriptor))
    (is (= {:kotoba.state/get [:record :kotoba.state/get [[:key :keyword]]]
            :kotoba.state/put [:record :kotoba.state/put [[:key :keyword] [:value :string]]]
            :kotoba.state/delete [:record :kotoba.state/delete [[:key :keyword]]]
            :kotoba.state/request [:variant :kotoba.state/request
                                   [[:get [:ref :kotoba.state/get]]
                                    [:put [:ref :kotoba.state/put]]
                                    [:delete [:ref :kotoba.state/delete]]]]
            :kotoba.state/entry [:record :kotoba.state/entry
                                 [[:key :keyword] [:value :string] [:version :i64]]]
            :kotoba.state/error [:record :kotoba.state/error
                                 [[:code :keyword] [:message :string]]]
            :kotoba.state/result [:variant :kotoba.state/result
                                  [[:found [:ref :kotoba.state/entry]]
                                   [:missing :bool]
                                   [:written [:ref :kotoba.state/entry]]
                                   [:deleted :bool]
                                   [:error [:ref :kotoba.state/error]]]]}
           schemas))
    (let [kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
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
             (mapv #(bit-and (int %) 0xff) (take 8 (:bytes closed))))))))
