(ns test.nbb.clock-transport
  "Repeatable, JVM-free regression test for the `:cljs` real wall/monotonic
  sources in `kotoba.compiler.provider.clock-transport` (ADR 0073). The
  `:clj` sources have their own property tests under `clojure -M:test`
  (`test/kotoba/compiler/clock_transport_test.clj`); `clojure.test` never
  loads cljs, so this is the only place the `:cljs` branch -- and the
  matching `:cljs` fix this ADR makes to `clock.cljc`'s own `valid-tick?`/
  observation-sequence counter (see their docstrings) -- actually runs.

  Every `:i64`-typed value flowing through here (wall milliseconds,
  monotonic nanoseconds, the observation sequence) is a `bigint` on
  `:cljs`, NOT a plain cljs number -- `cljs.core/=`, unlike `<=`, does NOT
  consider a bigint and a same-valued plain number equal (`(= 1 (js/BigInt
  1))` => false, confirmed live), so every equality assertion below
  compares bigint against bigint explicitly (`kotoba.compiler.cljs-i64`'s
  own `one`/`zero`, or an explicit `js/BigInt` literal) rather than against
  a bare cljs integer literal.

  Run from the repo root: `npm run test-nbb-clock-transport`."
  (:require [kotoba.compiler.admission :as admission]
            [kotoba.compiler.cljs-i64 :as i64]
            [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.clock :as clock]
            [kotoba.compiler.provider.clock-transport :as transport]
            [kotoba.compiler.reference-runtime :as runtime]))

(def source
  (str "(ns app.clock (:export [now]) (:capabilities #{:clock/now}))"
       "(defn now [request " (pr-str clock/request-type) "] "
       (pr-str clock/result-type) " (typed-cap-call :clock/now "
       (pr-str clock/request-type) " " (pr-str clock/result-type) " request))"))

(defn- hosted []
  (let [provider (clock/provider (transport/production-clock-source))
        hir (frontend/analyze source)
        _ (admission/check hir {:allow #{[:cap/call 7]}})
        kir (ir/lower hir)]
    (runtime/instantiate kir {:allow #{7} :providers {7 provider}})))

(defn- non-negative-i64? [n]
  (and (i64/bigint-value? n) (not (i64/k-neg? n))))

(defn- performance-now-nanos []
  (js/BigInt (js/Math.round (* (.now js/performance) 1e6))))

(defn- check [name ok? detail]
  {:name name :ok? (boolean ok?) :detail (when-not ok? detail)})

(defn- wall-now-case []
  (try
    (let [{:keys [wall-now]} (transport/production-clock-source)
          before (js/Date.now)
          observed (wall-now)
          after (js/Date.now)]
      (check "cljs-wall-now-is-a-non-negative-bigint-sandwiched-by-Date-now"
             (and (non-negative-i64? observed) (<= before observed after))
             (pr-str {:before before :observed observed :after after})))
    (catch :default e
      (check "cljs-wall-now-is-a-non-negative-bigint-sandwiched-by-Date-now" false (.-message e)))))

(defn- monotonic-now-plausible-case []
  (try
    (let [{:keys [monotonic-now]} (transport/production-clock-source)
          before (performance-now-nanos)
          observed (monotonic-now)
          after (performance-now-nanos)]
      (check "cljs-monotonic-now-is-a-non-negative-bigint-sandwiched-by-independent-performance-now-reads"
             (and (non-negative-i64? observed) (<= before observed after))
             (pr-str {:before before :observed observed :after after})))
    (catch :default e
      (check "cljs-monotonic-now-is-a-non-negative-bigint-sandwiched-by-independent-performance-now-reads"
             false (.-message e)))))

(defn- monotonic-never-regresses-case []
  (try
    (let [{:keys [monotonic-now]} (transport/production-clock-source)
          ticks (repeatedly 10000 monotonic-now)
          all-non-negative? (every? non-negative-i64? ticks)
          non-decreasing? (apply <= ticks)]
      (check "cljs-monotonic-now-never-regresses-across-10000-calls"
             (and all-non-negative? non-decreasing?)
             (pr-str {:all-non-negative? all-non-negative? :non-decreasing? non-decreasing?})))
    (catch :default e
      (check "cljs-monotonic-now-never-regresses-across-10000-calls" false (.-message e)))))

(defn- provider-round-trip-case []
  (try
    (let [runtime (hosted)
          before (js/Date.now)
          [result-type tag [_ unix-millis observation-sequence]]
          ((:invoke runtime) 'now [[clock/request-type :wall false]])
          after (js/Date.now)]
      (check "cljs-real-wall-observation-round-trips-through-the-typed-provider-boundary"
             (and (= clock/result-type result-type) (= :wall tag)
                  (non-negative-i64? unix-millis) (<= before unix-millis after)
                  (= i64/one observation-sequence))
             (pr-str {:result-type result-type :tag tag :unix-millis unix-millis
                      :observation-sequence observation-sequence :before before :after after})))
    (catch :default e
      (check "cljs-real-wall-observation-round-trips-through-the-typed-provider-boundary" false (.-message e)))))

(defn- monotonic-round-trip-never-regresses-case []
  (try
    (let [runtime (hosted)
          observe! #((:invoke runtime) 'now [[clock/request-type :monotonic false]])
          results (repeatedly 500 observe!)
          all-monotonic-tag? (every? (fn [[result-type tag _]]
                                       (and (= clock/result-type result-type) (= :monotonic tag)))
                                     results)
          nanos (map (fn [[_ _ [_ n _]]] n) results)
          sequences (map (fn [[_ _ [_ _ s]]] s) results)
          all-non-negative? (every? non-negative-i64? nanos)
          non-decreasing? (apply <= nanos)
          expected-sequences (map #(js/BigInt %) (range 1 501))
          sequential? (= expected-sequences sequences)]
      (check "cljs-real-monotonic-observations-round-trip-and-never-regress-through-the-typed-provider-boundary"
             (and all-monotonic-tag? all-non-negative? non-decreasing? sequential?)
             (pr-str {:all-monotonic-tag? all-monotonic-tag? :all-non-negative? all-non-negative?
                      :non-decreasing? non-decreasing? :sequential? sequential?})))
    (catch :default e
      (check "cljs-real-monotonic-observations-round-trip-and-never-regress-through-the-typed-provider-boundary"
             false (.-message e)))))

(let [results [(wall-now-case)
               (monotonic-now-plausible-case)
               (monotonic-never-regresses-case)
               (provider-round-trip-case)
               (monotonic-round-trip-never-regresses-case)]
      failures (remove :ok? results)]
  (doseq [{:keys [name ok? detail]} results]
    (println (if ok? "PASS" "FAIL") name (or detail "")))
  (println (count results) "cases," (count failures) "failed")
  (when (seq failures) (.exit js/process 1)))
