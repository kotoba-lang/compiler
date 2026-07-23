(ns kotoba.compiler.component-composition-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.canonical-abi :as canonical]
            [kotoba.compiler.component-artifact :as artifact]
            [kotoba.compiler.component-composition :as composition]
            [kotoba.compiler.component-core :as component-core]
            [kotoba.compiler.component-wit :as wit]
            [kotoba.compiler.wasm-tools :as wasm-tools])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

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

(deftest state-real-provider-rejects-non-state-v1-shape
  ;; ADR 0060: `package-state-provider`/`component-core/state-provider-wat`
  ;; are deliberately narrow to `state-v1`'s own literal shape (case tags,
  ;; field names/order), not a generic 'real provider for any asymmetric
  ;; variant crossing'. ADR 0058's own `demo/state-request`/`demo/state-
  ;; result` fixture (identical case COUNT and identical record SHAPE, but
  ;; every field `:i64` rather than `state-v1`'s real `:keyword`/`:string`)
  ;; is close enough structurally to be a meaningful negative case, and is
  ;; rejected at the WAT-emission layer before any `wac`/`wasm-tools`
  ;; command ever runs.
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
                                      [:error [:ref :demo/state-error]]]]}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"state-v1's own literal request/result shape"
         (composition/package-state-provider
          :state/transact descriptor result-descriptor schemas)))))

(defn- ref-ify
  "Duplicated from the primary `asymmetric-state-v1-literal-shape-provider-
  closes-the-application-world` fixture above (same pure structural
  re-nesting, not a hand transcription) so this deftest's own derivation of
  `state-v1.edn`'s literal shape is independent and does not rely on test
  ordering or shared mutable state."
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

(defn- state-v1-descriptors
  "`{:descriptor :result-descriptor :schemas}` for `state-v1.edn`'s own
  literal `:request`/`:result` EDN, read directly from the real resource
  file via `edn/read-string`/`slurp` and converted through `ref-ify` --
  identical derivation to the primary ADR 0059 fixture above, factored out
  so both this file's real-provider deftests and its stateful-sequence
  driver fixture below share one derivation rather than three independent
  transcriptions."
  []
  (let [state-kit (edn/read-string (slurp (io/resource "kotoba/lang/capability-kits/state-v1.edn")))
        request (ref-ify (:request state-kit))
        result (ref-ify (:result state-kit))]
    {:descriptor (:descriptor request)
     :result-descriptor (:descriptor result)
     :schemas (merge (:schemas request) (:schemas result))}))

(deftest state-real-provider-closes-the-application-world
  ;; ADR 0060: the first REAL (non-wiring-only) provider in this ADR chain,
  ;; for `state-v1`'s own literal shape exactly (via `state-v1-descriptors`,
  ;; the same programmatic `ref-ify` derivation ADR 0059's own primary
  ;; fixture uses -- not a hand transcription). This deftest proves
  ;; composition and `wasm-tools validate` only, matching every ADR in this
  ;; chain's own test-suite/manual-evidence split; real Wasmtime execution of
  ;; this exact composed component, including the full stateful sequence
  ;; proof, is recorded in this ADR's own Evidence section.
  (let [{:keys [descriptor result-descriptor schemas]} (state-v1-descriptors)
        kir {:format :kotoba.kir/v4 :exports ['invoke] :schemas schemas
             :functions [{:name 'invoke :params ['request]
                          :param-types [descriptor] :result result-descriptor
                          :body (list 'typed-cap-call 8 descriptor result-descriptor 'request)}]}
        world (wit/emit kir)
        application (artifact/package
                     (component-core/emit kir :wasm32-wasi-kotoba-v1) kir world)
        provider (composition/package-state-provider
                  :state/transact descriptor result-descriptor schemas)
        closed (composition/compose-closed application [provider])]
    (is (= :different-variant-capability-call (:canonical-lowering application)))
    (is (= :wasm-component-provider/v1 (:format provider)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [{:capability :state/transact :descriptor descriptor}]
           (:providers closed)))
    (is (= [0 97 115 109 13 0 1 0]
           (mapv #(bit-and (int %) 0xff) (take 8 (:bytes closed)))))))

;; --- Stateful-sequence driver fixture (ADR 0060) ---------------------------
;;
;; Every prior ADR in this chain proved a capability crossing via ONE
;; round trip per Wasmtime process invocation (`wasmtime run --invoke`
;; instantiates the composed component fresh each time it is run, so a
;; SEPARATE process invocation per step cannot observe cross-call state --
;; the provider's own bounded table would simply reset every time). Proving
;; this ADR's own central claim (REAL persistent mutable state across calls
;; WITHIN one component instance) therefore needs every step of the
;; sequence to run inside ONE Wasmtime invocation. `state-driver-wat`/
;; `state-driver-wit` build a small, hand-written, TEST-ONLY "driver"
;; application component (bypassing the standard KIR/`typed-cap-call`
;; admission pipeline, which only ever admits a function body that IS a
;; single `typed-cap-call` -- a multi-step application-side capability
;; SEQUENCE is a separate, unattempted KIR-admission widening this ADR does
;; not attempt, matching its own narrow scope) that imports the SAME
;; `state` capability interface `variant-capability-wat`'s own generated
;; application import already uses, and internally issues FOURTEEN
;; sequential calls to the imported (real) provider, checking each one's
;; own discriminant and payload fields against an independently-computed
;; expected value and folding a pass/fail bit per step into one `u32`
;; bitmask result -- so a single Wasmtime invocation's single scalar return
;; value is enough evidence to confirm (or, if any bit is unset, pinpoint
;; exactly which step) the full stateful sequence behaved correctly. This
;; deftest builds and composes/validates the driver artifact (matching
;; every other deftest in this file's own composition/validation-only
;; scope); the actual Wasmtime execution and its resulting bitmask are
;; recorded, including a deliberate negative-control run confirming the
;; harness itself discriminates failure, in this ADR's own Evidence section.

(def state-driver-steps
  "The 14-step stateful sequence `state-driver-wat` proves, `[disc key
  value-or-nil expect]` per step (`disc` 0=get/1=put/2=delete, matching
  `state-v1`'s own request case order). `expect` is `{:tag n ...}` where
  `n` is the RESULT case index (0=found 1=missing 2=written 3=deleted
  4=error) plus whichever fields that case carries. Traces exactly the
  sequence this ADR's own task framing suggested, extended to also exercise
  delete-on-an-absent-key and the capacity-exhaustion/existing-key-still-
  succeeds distinction: get(k1)->missing; put(k1,v1)->written v=2; get(k1)
  ->found v=2 (proves cross-call persistence); put(k1,v2)->written v=3
  (proves the version counter is real and increments); get(k2)->missing
  (proves no cross-key contamination); delete(k1)->deleted true; get(k1)
  ->missing (proves deletion is real); delete(k1) again->deleted false
  (matching `kotoba.compiler.provider.state`'s own 'delete on an absent key
  still succeeds, reporting false' semantics); put k2/k3/k4/k5 (fills the
  4-slot table from k1's now-vacated state); put k6 (a SIXTH distinct key,
  table full)->error `state/capacity`; put k2 again (an EXISTING key,
  table still full)->written v=8, proving the capacity check rejects only
  NEW keys, not every write, once full."
  [[0 "k1" nil {:tag 1 :bool false}]
   [1 "k1" "v1" {:tag 2 :key "k1" :value "v1" :version 2}]
   [0 "k1" nil {:tag 0 :key "k1" :value "v1" :version 2}]
   [1 "k1" "v2" {:tag 2 :key "k1" :value "v2" :version 3}]
   [0 "k2" nil {:tag 1 :bool false}]
   [2 "k1" nil {:tag 3 :bool true}]
   [0 "k1" nil {:tag 1 :bool false}]
   [2 "k1" nil {:tag 3 :bool false}]
   [1 "k2" "a" {:tag 2 :key "k2" :value "a" :version 4}]
   [1 "k3" "b" {:tag 2 :key "k3" :value "b" :version 5}]
   [1 "k4" "c" {:tag 2 :key "k4" :value "c" :version 6}]
   [1 "k5" "d" {:tag 2 :key "k5" :value "d" :version 7}]
   [1 "k6" "e" {:tag 4 :code "state/capacity"}]
   [1 "k2" "z" {:tag 2 :key "k2" :value "z" :version 8}]])

(def state-driver-expected-mask
  "The `u32` bitmask `state-driver-wat` returns when every one of
  `state-driver-steps`' own 14 checks passes: bit `i` set means step `i`
  (0-indexed) matched its own expected discriminant/fields exactly."
  (dec (bit-shift-left 1 (count state-driver-steps))))

(defn- state-driver-wat-data [bytes]
  (apply str (map #(format "\\%02x" (bit-and (int %) 0xff)) bytes)))

(defn- state-driver-align-up [value alignment]
  (* alignment (quot (+ value (dec alignment)) alignment)))

(defn- state-driver-literal-plan
  "`{text {:pointer :length}}` for every literal key/value/error-code string
  `state-driver-steps` references, laid out as sequential fixed `(data
  ...)` segments starting at byte 8 -- the same 'compile-time-literal
  content needs no runtime allocation, just a fixed address' technique ADR
  0059's own `plan-result-string-data` already established, applied here to
  the DRIVER's own REQUEST literals and CHECK-comparison literals rather
  than a provider's RESULT literals."
  []
  (let [literals ["k1" "k2" "k3" "k4" "k5" "k6" "v1" "v2" "a" "b" "c" "d" "e" "z"
                  "state/capacity"]]
    (loop [remaining literals offset 8 acc {}]
      (if-let [text (first remaining)]
        (let [bytes (vec (.getBytes ^String text "UTF-8"))]
          (recur (next remaining) (+ offset (count bytes))
                 (assoc acc text {:pointer offset :length (count bytes)})))
        acc))))

(defn- state-driver-field-offset [record-layout field-name]
  (:offset (some #(when (= field-name (:name %)) %) (:fields record-layout))))

(defn state-driver-wit
  "WIT text for the stateful-sequence driver: identical `types`/`state`
  interface declarations to `variant-capability-wat`'s own generated
  application WIT for `state-v1`'s shape (confirmed by direct comparison
  against `component-wit/emit`'s own output for the primary fixture above),
  importing `state` and exporting one bare scalar `run: func() -> u32` --
  deliberately the simplest possible export shape (no structured
  parameters or result), since this driver's own job is to run a fixed
  internal sequence, not to accept caller input."
  []
  (str
   "package kotoba:application@1.0.0;\n\n"
   "interface types {\n"
   "  record kotoba-state-get {\n    key: string,\n  }\n"
   "  record kotoba-state-put {\n    key: string,\n    value: string,\n  }\n"
   "  record kotoba-state-delete {\n    key: string,\n  }\n"
   "  variant kotoba-state-request {\n"
   "    get(kotoba-state-get),\n    put(kotoba-state-put),\n    delete(kotoba-state-delete),\n"
   "  }\n"
   "  record kotoba-state-entry {\n    key: string,\n    value: string,\n    version: s64,\n  }\n"
   "  record kotoba-state-error {\n    code: string,\n    message: string,\n  }\n"
   "  variant kotoba-state-result {\n"
   "    found(kotoba-state-entry),\n    missing(bool),\n    written(kotoba-state-entry),\n"
   "    deleted(bool),\n    error(kotoba-state-error),\n"
   "  }\n"
   "}\n\n"
   "interface state {\n"
   "  use types.{kotoba-state-request, kotoba-state-result};\n"
   "  transact: func(request: kotoba-state-request) -> kotoba-state-result;\n"
   "}\n\n"
   "world driver {\n"
   "  import state;\n"
   "  export run: func() -> u32;\n"
   "}\n"))

(defn state-driver-wat
  "The stateful-sequence driver's own core WAT module: imports the SAME
  `cm32p2|kotoba:application/state@1|transact` core function every
  `variant-capability-wat`-generated application already imports, then runs
  `state-driver-steps` internally in order within ONE exported `run`
  call -- each step allocates a fresh result area via this module's OWN
  bump allocator (reused across steps, `$next` explicitly reset back to
  `arena-base` after each step's checks complete, since this driver's own
  `_post` fires only ONCE, after `run` fully returns, not between internal
  steps), pushes that step's own (fixed, compile-time-literal) request
  discriminant/key/value onto the stack, calls the imported provider,
  then checks the returned discriminant and whichever fields that step's
  own `expect` names via `$bytes-equal` (i64 equality for `version`, i32
  equality for a bare bool payload) -- exactly the same per-field checks a
  human reading a decoded Wasmtime result would perform by eye, just
  performed by the driver itself so the entire 14-step sequence fits in one
  Wasmtime invocation. `result-descriptor`/`schemas` (`state-v1`'s own
  shape) determine every field offset via `canonical/layout`, the same
  Canonical ABI layout planner `state-provider-wat` itself uses -- this
  driver never hand-guesses an offset."
  [result-descriptor schemas]
  (let [result-layout (canonical/layout result-descriptor schemas)
        result-cases (:cases result-layout)
        entry-layout (:layout (nth result-cases 0))
        error-layout (:layout (nth result-cases 4))
        payload-offset (:payload-offset result-layout)
        result-size (:size result-layout)
        result-alignment (:alignment result-layout)
        key-off (+ payload-offset (state-driver-field-offset entry-layout :key))
        value-off (+ payload-offset (state-driver-field-offset entry-layout :value))
        version-off (+ payload-offset (state-driver-field-offset entry-layout :version))
        code-off (+ payload-offset (state-driver-field-offset error-layout :code))
        literal-plan (state-driver-literal-plan)
        literal-end (reduce (fn [acc [_ {:keys [pointer length]}]] (max acc (+ pointer length)))
                            8 literal-plan)
        arena-base (state-driver-align-up literal-end 8)
        ;; Generous, not tight (matching every other allocator in this ADR
        ;; chain): one call's own result-side headroom (up to two
        ;; string/keyword leaves, `entry`'s key+value or `error`'s
        ;; code+message) plus the result struct itself. `$next` is reset to
        ;; `arena-base` after EVERY step (see docstring above), so only ONE
        ;; call's worth of headroom is ever needed regardless of step count.
        result-headroom-bytes (* 3 65536)
        required-bytes (+ arena-base result-headroom-bytes result-size)
        pages (max 1 (quot (+ required-bytes 65535) 65536))
        capacity-bytes (* pages 65536)
        lit (fn [text] (get literal-plan text))
        call-args (fn [disc key value]
                    (let [{kp :pointer kl :length} (lit key)]
                      (if value
                        (let [{vp :pointer vl :length} (lit value)]
                          (str "i32.const " disc " i32.const " kp " i32.const " kl
                               " i32.const " vp " i32.const " vl))
                        (str "i32.const " disc " i32.const " kp " i32.const " kl
                             " i32.const 0 i32.const 0"))))
        field-check-wat
        (fn [check]
          (case (:tag check)
            (0 2)
            (str
             "    local.get $ret i32.load offset=" key-off
             " local.get $ret i32.load offset=" (+ key-off 4)
             " i32.const " (:pointer (lit (:key check)))
             " i32.const " (:length (lit (:key check)))
             " call $bytes-equal i32.eqz if i32.const 0 local.set $ok end\n"
             "    local.get $ret i32.load offset=" value-off
             " local.get $ret i32.load offset=" (+ value-off 4)
             " i32.const " (:pointer (lit (:value check)))
             " i32.const " (:length (lit (:value check)))
             " call $bytes-equal i32.eqz if i32.const 0 local.set $ok end\n"
             "    local.get $ret i64.load offset=" version-off
             " i64.const " (:version check) " i64.ne if i32.const 0 local.set $ok end\n")
            (1 3)
            (str
             "    local.get $ret i32.load8_u offset=" payload-offset " i32.const "
             (if (:bool check) 1 0) " i32.ne if i32.const 0 local.set $ok end\n")
            4
            (str
             "    local.get $ret i32.load offset=" code-off
             " local.get $ret i32.load offset=" (+ code-off 4)
             " i32.const " (:pointer (lit (:code check)))
             " i32.const " (:length (lit (:code check)))
             " call $bytes-equal i32.eqz if i32.const 0 local.set $ok end\n")))
        step-wat
        (fn [index [disc key value expect]]
          (str
           "    i32.const 0 i32.const 0 i32.const " result-alignment
           " i32.const " result-size " call $realloc local.set $ret\n"
           "    " (call-args disc key value) " local.get $ret call $provider\n"
           "    i32.const 1 local.set $ok\n"
           "    local.get $ret i32.load8_u offset=0 i32.const " (:tag expect)
           " i32.ne if i32.const 0 local.set $ok end\n"
           (field-check-wat expect)
           "    local.get $ok\n"
           "    if\n"
           "      local.get $mask i32.const " (bit-shift-left 1 index) " i32.or local.set $mask\n"
           "    end\n"
           "    i32.const " arena-base " global.set $next\n"))
        data-segments
        (apply str
               (map (fn [[text {:keys [pointer]}]]
                      (str "  (data (i32.const " pointer ") \""
                           (state-driver-wat-data (vec (.getBytes ^String text "UTF-8")))
                           "\")\n"))
                    literal-plan))]
    (str
     "(module\n"
     "  (import \"cm32p2|kotoba:application/state@1\" \"transact\""
     " (func $provider (param i32) (param i32) (param i32) (param i32) (param i32) (param i32)))\n"
     "  (memory (export \"cm32p2_memory\") " pages " " pages ")\n"
     "  (global $next (mut i32) (i32.const " arena-base "))\n"
     "  (func $realloc (export \"cm32p2_realloc\")\n"
     "    (param $old-ptr i32) (param $old-size i32)\n"
     "    (param $align i32) (param $new-size i32) (result i32)\n"
     "    (local $ptr i32) (local $end i32) (local $copy-size i32)\n"
     "    local.get $new-size i32.eqz if i32.const 0 return end\n"
     "    local.get $align i32.eqz if unreachable end\n"
     "    local.get $align i32.const 8 i32.gt_u if unreachable end\n"
     "    local.get $align local.get $align i32.const 1 i32.sub i32.and if unreachable end\n"
     "    global.get $next local.get $align i32.const 1 i32.sub i32.add\n"
     "    i32.const 0 local.get $align i32.sub i32.and local.tee $ptr\n"
     "    local.get $new-size i32.add local.tee $end local.get $ptr i32.lt_u\n"
     "    if unreachable end\n"
     "    local.get $end i32.const " capacity-bytes " i32.gt_u if unreachable end\n"
     "    local.get $end global.set $next\n"
     "    local.get $old-ptr i32.eqz if else\n"
     "      local.get $old-size local.get $new-size i32.lt_u\n"
     "      if (result i32) local.get $old-size else local.get $new-size end\n"
     "      local.set $copy-size\n"
     "      local.get $ptr local.get $old-ptr local.get $copy-size memory.copy\n"
     "    end local.get $ptr)\n"
     "  (func $bytes-equal (param $a i32) (param $alen i32)"
     " (param $b i32) (param $blen i32) (result i32)\n"
     "    (local $i i32)\n"
     "    local.get $alen local.get $blen i32.ne if i32.const 0 return end\n"
     "    i32.const 0 local.set $i\n"
     "    loop $scan\n"
     "      local.get $i local.get $alen i32.ge_u if i32.const 1 return end\n"
     "      local.get $a local.get $i i32.add i32.load8_u\n"
     "      local.get $b local.get $i i32.add i32.load8_u\n"
     "      i32.ne if i32.const 0 return end\n"
     "      local.get $i i32.const 1 i32.add local.set $i\n"
     "      br $scan\n"
     "    end\n"
     "    i32.const 1)\n"
     "  (func (export \"cm32p2||run\") (result i32)\n"
     "    (local $ret i32) (local $mask i32) (local $ok i32)\n"
     "    i32.const 0 local.set $mask\n"
     (apply str (map-indexed step-wat state-driver-steps))
     "    local.get $mask)\n"
     "  (func (export \"cm32p2||run_post\") (param i32)\n"
     "    i32.const " arena-base " global.set $next)\n"
     "  (func (export \"cm32p2_initialize\") i32.const " arena-base " global.set $next)\n"
     data-segments
     ")\n")))

(defn- package-state-driver
  "Embed/wrap `state-driver-wat`/`state-driver-wit` into a
  `:wasm-component/v1` application artifact -- the driver's own counterpart
  to `kotoba.compiler.component-artifact/package`, since the driver is
  hand-authored WAT/WIT rather than compiled from KIR."
  [result-descriptor schemas]
  (let [wit (state-driver-wit)
        core-wat (state-driver-wat result-descriptor schemas)
        dir (Files/createTempDirectory "kotoba-state-driver-" (make-array FileAttribute 0))
        world (.resolve dir "driver.wit") core (.resolve dir "driver.wasm")
        embedded (.resolve dir "embedded.wasm") component (.resolve dir "driver.component.wasm")]
    (try
      (Files/writeString world wit (make-array java.nio.file.OpenOption 0))
      (Files/write core (wasm-tools/parse-wat core-wat) (make-array java.nio.file.OpenOption 0))
      (wasm-tools/run-command! ["wasm-tools" "component" "embed" (str world) (str core)
                                "--encoding" "utf8" "-o" (str embedded)])
      (wasm-tools/run-command! ["wasm-tools" "component" "new" (str embedded)
                                "--reject-legacy-names" "-o" (str component)])
      {:format :wasm-component/v1 :imports [:state/transact]
       :bytes (Files/readAllBytes component)}
      (finally
        (doseq [path [component embedded core world]] (Files/deleteIfExists path))
        (Files/deleteIfExists dir)))))

(deftest state-stateful-sequence-driver-closes-and-validates
  ;; ADR 0060: the driver (`state-driver-wat`, running `state-driver-steps`
  ;; internally against the REAL provider `package-state-provider` builds)
  ;; composes and `wasm-tools validate`s cleanly, exactly as every other
  ;; composed artifact in this file does. The full 14-step sequence's own
  ;; PASS/FAIL bitmask (proving real cross-call persistence, isolation,
  ;; version increments, deletion, and capacity fail-closed behavior, not
  ;; merely composition) comes from real Wasmtime execution of this exact
  ;; artifact, recorded in this ADR's own Evidence section (matching every
  ;; ADR in this chain's own test-suite/manual-evidence split).
  (let [{:keys [result-descriptor schemas]} (state-v1-descriptors)
        driver (package-state-driver result-descriptor schemas)
        provider (composition/package-state-provider
                  :state/transact (:descriptor (state-v1-descriptors))
                  result-descriptor schemas)
        closed (composition/compose-closed driver [provider])]
    (is (= :wasm-component-provider/v1 (:format provider)))
    (is (= :wasm-component-closed/v1 (:format closed)))
    (is (= [0 97 115 109 13 0 1 0]
           (mapv #(bit-and (int %) 0xff) (take 8 (:bytes closed)))))
    (is (= 16383 state-driver-expected-mask))))
