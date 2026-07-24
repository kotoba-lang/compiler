(ns kotoba.compiler.clock-transport-test
  "Tests for `kotoba.compiler.provider.clock-transport`'s `:clj` real
  wall/monotonic sources.

  These are deterministic property checks against the REAL
  `System/currentTimeMillis`/`System/nanoTime`-backed sources (no fake
  server needed -- unlike ADR 0064/0066/0071's LLM/HTTP/storage
  transports, there is no network to fake here), plus one end-to-end round
  trip through the full typed `kotoba.compiler.provider.clock/provider`
  boundary via `kotoba.compiler.reference-runtime`, mirroring
  `clock_provider_test.clj`'s own fixture-based test shape.

  The `:cljs` source in the same namespace is verified separately, under
  nbb, by `test/nbb/clock-transport.cljs` -- see docs/adr/0073
  'Evidence' for why (`clojure -M:test` never loads cljs)."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.clock :as clock]
            [kotoba.compiler.provider.clock-transport :as transport]
            [kotoba.compiler.reference-runtime :as runtime]))

;; ---------------------------------------------------------------------------
;; group 1 -- pure property checks of the real sources directly, no provider
;; ---------------------------------------------------------------------------

(deftest wall-now-is-a-non-negative-integer-close-to-the-real-current-time
  (let [{:keys [wall-now]} (transport/production-clock-source)
        before (System/currentTimeMillis)
        observed (wall-now)
        after (System/currentTimeMillis)]
    (is (integer? observed))
    (is (<= 0 observed))
    (testing "sandwiched between two System/currentTimeMillis readings taken immediately around it"
      (is (<= before observed after)))))

(deftest monotonic-now-starts-near-zero-and-is-always-non-negative
  (let [{:keys [monotonic-now]} (transport/production-clock-source)
        first-tick (monotonic-now)]
    (is (integer? first-tick))
    (is (<= 0 first-tick))
    (testing "the anchor makes the very first observation small (well under one second in nanoseconds)"
      (is (< first-tick 1000000000)))))

(deftest monotonic-now-never-regresses-across-many-tight-loop-calls
  (let [{:keys [monotonic-now]} (transport/production-clock-source)
        ticks (repeatedly 10000 monotonic-now)]
    (is (every? #(and (integer? %) (<= 0 %)) ticks))
    (is (apply <= ticks))))

(deftest each-call-to-production-clock-source-builds-an-independently-anchored-monotonic-source
  ;; Two separate constructions must not share anchor state -- each is its
  ;; own provider instance with its own origin, exactly like two separate
  ;; `System/nanoTime` calls in two unrelated processes would be.
  (let [first-source (:monotonic-now (transport/production-clock-source))
        _ (Thread/sleep 5)
        second-source (:monotonic-now (transport/production-clock-source))]
    (is (< (first-source) 1000000000))
    (is (< (second-source) 1000000000))))

;; ---------------------------------------------------------------------------
;; group 2 -- end to end, through the typed clock/provider boundary
;; ---------------------------------------------------------------------------

(def source
  (str "(ns app.clock (:export [now]) (:capabilities #{:clock/now}))"
       "(defn now [request " (pr-str clock/request-type) "] "
       (pr-str clock/result-type) " (typed-cap-call :clock/now "
       (pr-str clock/request-type) " " (pr-str clock/result-type) " request))"))

(defn- hosted []
  (let [provider (clock/provider (transport/production-clock-source))
        kir (ir/lower (:hir (compiler/check-source source {:allow #{[:cap/call 7]}})))]
    (runtime/instantiate kir {:allow #{7} :providers {7 provider}})))

(deftest real-wall-observation-round-trips-through-the-typed-provider-boundary
  (let [runtime (hosted)
        before (System/currentTimeMillis)
        [result-type tag [_ unix-millis observation-sequence]]
        ((:invoke runtime) 'now [[clock/request-type :wall false]])
        after (System/currentTimeMillis)]
    (is (= clock/result-type result-type))
    (is (= :wall tag))
    (is (<= before unix-millis after))
    (is (= 1 observation-sequence))))

(deftest real-monotonic-observations-round-trip-and-never-regress-through-the-typed-provider-boundary
  (let [runtime (hosted)
        observe! #((:invoke runtime) 'now [[clock/request-type :monotonic false]])
        results (repeatedly 500 observe!)
        nanos (map (fn [[_ _ [_ n _]]] n) results)
        sequences (map (fn [[_ _ [_ _ s]]] s) results)]
    (is (every? (fn [[result-type tag _]] (and (= clock/result-type result-type) (= :monotonic tag))) results))
    (is (apply <= nanos))
    (is (= (range 1 501) sequences))))

(deftest wall-and-monotonic-share-one-observation-sequence-through-the-real-provider
  (let [runtime (hosted)
        [_ _ [_ _ first-sequence]] ((:invoke runtime) 'now [[clock/request-type :wall false]])
        [_ _ [_ _ second-sequence]] ((:invoke runtime) 'now [[clock/request-type :monotonic false]])]
    (is (= 1 first-sequence))
    (is (= 2 second-sequence))))
