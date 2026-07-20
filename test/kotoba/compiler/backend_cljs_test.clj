(ns kotoba.compiler.backend-cljs-test
  "Tests for ADR-2607151500's new cljs backend (kotoba.compiler.backend.cljs):
  KIR -> plain ClojureScript source text, a genuinely separate execution
  target alongside wasm32/x86_64/aarch64.

  These tests eval the emitted source under PLAIN JVM CLOJURE, not a real
  cljs/nbb runtime -- deliberately, matching this compiler's own existing
  convention of not depending on an external runtime (Chicory for wasm) in
  its committed test suite (see frontend_extensions_test.clj's addendum
  notes: real Chicory execution was verified via an uncommitted scratch
  script, not a committed test). This is a legitimate real-execution check
  here specifically because every construct `backend.cljs/emit` ever emits
  (defn/let/if/vector/nth/atom/swap!/throw/ex-info, a namespaced map
  literal) is valid, semantically identical Clojure AND ClojureScript --
  there is nothing cljs-specific in the emitted text this backend produces.
  Real nbb execution of the exact same generated sources (including the
  fuel-exhaustion global-depletion property) was independently verified by
  hand before this commit; see ADR-2607151500."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as shell]
            [clojure.tools.reader :as reader]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.provider.clock :as clock]
            [kotoba.compiler.provider.http :as http]
            [kotoba.compiler.provider.llm :as llm]
            [kotoba.compiler.provider.log :as log]
            [kotoba.compiler.provider.state :as state]
            [kotoba.compiler.provider.storage :as storage]
            [kotoba.compiler.provider.ui :as ui]))

(defn- compile-cljs
  ([source] (compiler/compile-source source :cljs-kotoba-v1))
  ([source policy] (compiler/compile-source source :cljs-kotoba-v1 policy)))

(defn- read-generated [source-text]
  (reader/read-string {:read-cond :allow :features #{:clj}}
                      (str "(" source-text ")")))

(defn- eval-in-fresh-ns
  "Reads and evals every top-level form EXCEPT the emitted `(ns ...)` form
  (a real cljs host would `require` the emitted namespace by name; here we
  just eval everything into a fresh throwaway JVM namespace instead, to
  keep every test isolated from the others -- the emitted `defonce`
  fuel atom must not leak between tests)."
  [source-text]
  (let [ns-sym (gensym "kotoba-cljs-eval-test-ns-")
        forms (read-generated source-text)
        target-ns (create-ns ns-sym)]
    (binding [*ns* target-ns]
      (clojure.core/refer-clojure)
      (doseq [form forms]
        (when-not (and (seq? form) (= 'ns (first form)))
          (eval form))))
    target-ns))

(defn- call [ns fn-sym & args]
  (apply (ns-resolve ns fn-sym) args))

;; ───────────────────────── emit shape + oracle parity ─────────────────────────

(deftest emit-produces-readable-multi-form-cljs-source
  (let [{:keys [source]} (compile-cljs "(defn main [] (+ 1 2))")
        forms (read-generated source)]
    (is (= '(ns kotoba.compiled.generated) (first forms)))
    (is (some #(and (seq? %) (= 'defn (first %)) (= 'main (second %))) forms))))

(deftest arithmetic-matches-oracle
  (let [compiled (compile-cljs "(defn main [] (+ 1 2))")
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 3 (:oracle-value (:kir compiled))))
    (is (= 3 (call ns 'main)))))

(deftest pair-round-trips-through-plain-vectors
  (let [source "(defn main [] (let [m (pair 1 2)] (+ (pair-first m) (pair-second m))))"
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 3 (:oracle-value (:kir compiled))))
    (is (= 3 (call ns 'main)))))

(deftest if-treats-zero-as-false-not-clojures-own-truthy-zero
  ;; The crux semantic gap this backend must bridge: cljs/clj's OWN `if`
  ;; treats 0 as truthy (unlike KIR's 0-is-false convention) -- if the
  ;; wrapping were missing, this would return the WRONG branch.
  (let [source "(defn main [] (if (= 1 2) 42 0))" ; (= 1 2) -> KIR value 0 -> KIR-false -> else branch (0)
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 0 (:oracle-value (:kir compiled))))
    (is (= 0 (call ns 'main)))))

(deftest named-function-calls-work-across-multiple-defns
  (let [source "(defn helper [x] (* x x)) (defn main [] (helper 7))"
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 49 (:oracle-value (:kir compiled))))
    (is (= 49 (call ns 'main)))
    (is (= 100 (call ns 'helper 10)))))

(deftest destructuring-and-loop-recur-lower-through-unchanged
  ;; loop/recur and destructuring are entirely FRONTEND desugars (ADR-
  ;; 2607150000) -- by the time KIR sees this source it's already plain
  ;; named-function recursion, so this backend needs no special handling;
  ;; this test is the proof.
  (let [source "(defn main [] (loop [i 0 acc 0] (if (= i 5) acc (recur (+ i 1) (+ acc i)))))"
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 10 (:oracle-value (:kir compiled))))
    (is (= 10 (call ns 'main)))))

;; ───────────────────────── division-by-zero / fuel ─────────────────────────

(deftest quot-by-zero-throws-instead-of-silently-returning-infinity
  ;; Plain cljs `quot` on JS numbers does NOT throw on a zero divisor
  ;; (produces Infinity/NaN) -- kotoba$quot's explicit guard is what makes
  ;; this backend trap the same way ir.clj's oracle and the wasm/native
  ;; backends do, rather than silently diverging into IEEE-754 semantics.
  (let [source "(defn maybe-div [a b] (quot a b)) (defn main [] (+ 1 2))"
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 3 (call ns 'main)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"division-by-zero"
                          (call ns 'maybe-div 10 0)))))

(deftest fuel-is-a-global-never-replenished-budget-not-per-call
  ;; Mirrors WASM's own module-global fuel semantics exactly (ir.clj/
  ;; backend/wasm.clj: charge once per function ENTRY, a single Instance
  ;; gets 512 calls total for its whole lifetime, never replenished) --
  ;; NOT 512 fresh calls every time `main` is invoked.
  (let [source "(defn noop [] 0) (defn main [] (+ 1 2))"
        compiled (compile-cljs source)
        ns (eval-in-fresh-ns (:source compiled))]
    (dotimes [_ 511] (call ns 'noop))
    (is (= 3 (call ns 'main)) "the 512th call still succeeds")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fuel-exhausted"
                          (call ns 'noop))
        "the 513th call, ever, on this loaded module traps -- not reset per top-level call")))

;; ───────────────────────── arithmetic overflow ─────────────────────────

(deftest arithmetic-within-safe-integer-range-is-unaffected
  (let [compiled (compile-cljs "(defn main [] (+ 9007199254740990 1))") ; == 2^53-1, the boundary itself
        ns (eval-in-fresh-ns (:source compiled))]
    (is (= 9007199254740991 (:oracle-value (:kir compiled))))
    (is (= 9007199254740991 (call ns 'main)))))

(deftest arithmetic-past-safe-integer-range-throws-instead-of-silently-diverging
  ;; True i64 wraparound parity would need every value represented as a JS
  ;; BigInt end to end -- not attempted (see this backend's own docstring).
  ;; Instead, exceeding JS's safe-integer bound (2^53-1, past which a plain
  ;; cljs `number` can no longer represent every integer exactly) throws
  ;; :arithmetic-overflow rather than silently continuing with an
  ;; imprecise value -- the same fail-closed posture already used for
  ;; fuel/division/capability, narrowing the wraparound gap from "silently
  ;; wrong" to "loudly fails."
  (let [compiled (compile-cljs "(defn main [] (* 100000000 100000000))") ; 10^16 > 2^53-1
        ns (eval-in-fresh-ns (:source compiled))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arithmetic-overflow"
                          (call ns 'main)))))

(deftest arithmetic-overflow-check-applies-to-plus-minus-and-times
  (doseq [[source expect-throw?]
          [["(defn main [] (+ 100000000 100000000))" false]
           ["(defn main [] (* 100000000 100000000))" true]
           ["(defn main [] (- 0 100000000000000000))" true]]]
    (let [compiled (compile-cljs source)
          ns (eval-in-fresh-ns (:source compiled))]
      (if expect-throw?
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"arithmetic-overflow" (call ns 'main))
            source)
        (is (= (:oracle-value (:kir compiled)) (call ns 'main)) source)))))

;; ───────────────────────── cap-call ─────────────────────────

(deftest cap-call-with-no-dispatcher-installed-is-denied-fail-closed
  ;; a capability POLICY that admits this cap-call at the frontend/admission
  ;; level -- so a throw here is genuinely this backend's OWN cljs-side
  ;; capability boundary (kotoba$cap-dispatch defaulting to nil), not the
  ;; earlier, stricter static admission gate short-circuiting first.
  (let [compiled (compile-cljs "(defn main [] (cap-call 1 2))" {:allow #{[:cap/call 1]}})
        ns (eval-in-fresh-ns (:source compiled))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"capability-denied"
                          (call ns 'main))
        "no dispatcher installed -- every cap-call denied, matching kotoba-lang/kotoba's own fail-closed has-capability-fn")))

(deftest cap-call-dispatches-to-the-installed-host-function
  (let [compiled (compile-cljs "(defn main [] (cap-call 1 42))" {:allow #{[:cap/call 1]}})
        ns (eval-in-fresh-ns (:source compiled))]
    (call ns 'set-cap-dispatch! (fn [cap-id value] (+ (* cap-id 100) value)))
    (is (= 142 (call ns 'main))
        "cap-id 1 and value 42 both reach the installed host function unchanged")))

;; ───────────────────────── typed provider ABI ─────────────────────────

(defn- typed-function [name capability request-type result-type]
  (str "(defn " name " [request " (pr-str request-type) "] "
       (pr-str result-type) " (typed-cap-call " capability " "
       (pr-str request-type) " " (pr-str result-type) " request))"))

(defn- application-provider-source []
  (str "(ns app.cljs-providers (:export [state ui-commit ui-event http llm storage clock log-append log-read]) "
       "(:capabilities #{:state/transact :ui/commit :ui/next-event :http/post "
       ":llm/generate :storage/transact :clock/now :log/append :log/read}))"
       (typed-function "state" ":state/transact" state/request-type state/result-type)
       (typed-function "ui-commit" ":ui/commit" ui/commit-request-type ui/commit-result-type)
       (typed-function "ui-event" ":ui/next-event" ui/event-request-type ui/event-result-type)
       (typed-function "http" ":http/post" http/request-type http/result-type)
       (typed-function "llm" ":llm/generate" llm/request-type llm/result-type)
       (typed-function "storage" ":storage/transact" storage/request-type storage/result-type)
       (typed-function "clock" ":clock/now" clock/request-type clock/result-type)
       (typed-function "log-append" ":log/append" log/append-request-type log/append-result-type)
       (typed-function "log-read" ":log/read" log/read-request-type log/read-result-type)))

(def application-effects
  {:allow #{[:cap/call 4] [:cap/call 5] [:cap/call 6] [:cap/call 7]
            [:cap/call 8] [:cap/call 9] [:cap/call 10] [:cap/call 11]
            [:cap/call 12]}})

(deftest typed-provider-dispatch-runs-all-application-kit-contracts
  (let [ui-kit (ui/create-provider)
        log-kit (log/create-provider)
        providers
        {4 (http/provider {:allowed-origins #{"https://api.example.test"}
                           :transport (fn [_] {:status 200 :headers {} :body "ok"})})
         5 (get-in log-kit [:providers 5])
         6 (get-in log-kit [:providers 6])
         7 (clock/provider {:wall-now (constantly 1700000000000)
                            :monotonic-now (constantly 1)})
         8 (state/provider)
         9 (get-in ui-kit [:providers 9])
         10 (get-in ui-kit [:providers 10])
         11 (llm/provider {:allowed-models #{:example/text-v1}
                           :transport (fn [_] {:text "ok" :finish-reason :llm/stop
                                              :input-tokens 1 :output-tokens 1})})
         12 (storage/provider {:storage-namespace :example/data
                               :transport (fn [_] {:tag :missing})})}
        compiled (compile-cljs (application-provider-source) application-effects)
        ns (eval-in-fresh-ns (:source compiled))
        invoke #(apply call ns %)]
    (call ns 'set-typed-providers! {:allow (set (keys providers)) :providers providers})
    (is (= [state/result-type :missing false]
           (invoke ['state [state/request-type :get [state/get-type :profile/name]]])))
    (is (= [ui/commit-result-type 1 0]
           (invoke ['ui-commit [ui/commit-request-type 0 [ui/node-set-type []]]])))
    (is (= [ui/event-result-type false]
           (invoke ['ui-event [ui/event-request-type 0]])))
    (is (= [http/result-type :ok
            [http/response-type 200 [http/header-set-type []] "ok"]]
           (invoke ['http [http/request-type "https://api.example.test/v1"
                           [http/header-set-type []] "" 1000]])))
    (is (= [llm/result-type :ok
            [llm/completion-type "ok" :llm/stop [llm/usage-type 1 1]]]
           (invoke ['llm [llm/request-type :example/text-v1 "" "hello" 16 0]])))
    (is (= [storage/result-type :missing false]
           (invoke ['storage [storage/request-type :get
                              [storage/get-type :profile/name]]])))
    (is (= [clock/result-type :wall [clock/wall-type 1700000000000 1]]
           (invoke ['clock [clock/request-type :wall false]])))
    (is (= [log/append-result-type 1]
           (invoke ['log-append [log/append-request-type :log/info :app/ready
                                 "ready" [log/field-set-type []]]])))
    (is (= 1 (nth (invoke ['log-read [log/read-request-type 0 1]]) 2)))))

(deftest typed-provider-boundary-denies-and-validates-both-sides
  (let [request-type [:record :demo.cljs/request [[:text :string]]]
        result-type [:record :demo.cljs/result [[:ok :bool]]]
        source (str "(ns demo.cljs (:export [invoke]) (:capabilities #{:http/post}))"
                    (typed-function "invoke" ":http/post" request-type result-type))
        compiled (compile-cljs source {:allow #{[:cap/call 4]}})
        ns (eval-in-fresh-ns (:source compiled))
        request [request-type "hello"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"typed capability denied"
                          (call ns 'invoke request)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"typed provider contract mismatch"
         (call ns 'set-typed-providers!
               {:allow #{4}
                :providers {4 {:request-type :string :result-type result-type
                               :invoke identity}}})))
    (call ns 'set-typed-providers!
          {:allow #{4}
           :providers {4 {:request-type request-type :result-type result-type
                          :invoke (fn [_] [request-type "forged"])}}})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid-typed-value"
                          (call ns 'invoke request)))
    (let [called? (atom false)]
      (call ns 'set-typed-providers!
            {:allow #{4}
             :providers {4 {:request-type request-type :result-type result-type
                            :invoke (fn [_] (reset! called? true)
                                      [result-type true])}}})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid-typed-value"
                            (call ns 'invoke [request-type 1])))
      (is (false? @called?))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"invalid-typed-value"
           (call ns 'invoke [request-type (apply str (repeat 16385 "😀"))])))
      (is (false? @called?)))))

(deftest real-nbb-executes-the-typed-provider-codec
  (let [request-type [:record :demo.nbb/request
                      [[:name :string] [:tags [:set :keyword]]]]
        result-type [:record :demo.nbb/result [[:accepted [:option :bool]]]]
        source (str "(ns demo.nbb (:export [invoke]) (:capabilities #{:http/post}))"
                    (typed-function "invoke" ":http/post" request-type result-type))
        compiled (compile-cljs source {:allow #{[:cap/call 4]}})
        request [request-type "ことば😀" [[:set :keyword] [:app/safe]]]
        result [result-type [[:option :bool] true true]]
        script (doto (java.io.File/createTempFile "kotoba-typed-provider-" ".cljs")
                 (.deleteOnExit))]
    (spit script
          (str (:source compiled) "\n"
               "(set-typed-providers! "
               (pr-str {:allow #{4}
                        :providers {4 {:request-type request-type
                                       :result-type result-type
                                       :invoke (list 'fn ['request] result)}}}) ")\n"
               "(when-not (= " (pr-str result) " (invoke " (pr-str request) ")) "
               "  (throw (ex-info \"typed-provider-nbb-mismatch\" {})))\n"
               "(println \"typed-provider-nbb-ok\")\n"))
    (let [execution (shell/sh "npx" "nbb" (.getPath script))]
      (is (zero? (:exit execution)) (:err execution))
      (is (= "typed-provider-nbb-ok\n" (:out execution))))))

(deftest real-nbb-round-trips-full-i64-provider-values
  (let [request-type [:record :demo.i64/request [[:minimum :i64] [:maximum :i64]]]
        result-type [:record :demo.i64/result [[:minimum :i64] [:maximum :i64]]]
        source (str "(ns demo.i64 (:export [invoke]) (:capabilities #{:http/post}))"
                    (typed-function "invoke" ":http/post" request-type result-type))
        compiled (compile-cljs source {:allow #{[:cap/call 4]}})
        script (doto (java.io.File/createTempFile "kotoba-typed-i64-" ".cljs")
                 (.deleteOnExit))]
    (spit script
          (str (:source compiled) "\n"
               "(def minimum (js/BigInt \"-9223372036854775808\"))\n"
               "(def maximum (js/BigInt \"9223372036854775807\"))\n"
               "(def below (js/BigInt \"-9223372036854775809\"))\n"
               "(def above (js/BigInt \"9223372036854775808\"))\n"
               "(def request-type " (pr-str request-type) ")\n"
               "(def result-type " (pr-str result-type) ")\n"
               "(set-typed-providers! {:allow #{4} :providers {4 "
               "{:request-type request-type :result-type result-type "
               ":invoke (fn [request] [result-type (nth request 1) (nth request 2)])}}})\n"
               "(when-not (= [result-type minimum maximum] "
               "(invoke [request-type minimum maximum])) "
               "(throw (ex-info \"full-i64-round-trip\" {})))\n"
               "(doseq [invalid [below above]] "
               "(try (invoke [request-type invalid maximum]) "
               "(throw (ex-info \"out-of-range-admitted\" {})) "
               "(catch :default error "
               "(when (= \"out-of-range-admitted\" (ex-message error)) (throw error)))))\n"
               "(set-typed-providers! {:allow #{4} :providers {4 "
               "{:request-type request-type :result-type result-type "
               ":invoke (fn [_] [result-type above maximum])}}})\n"
               "(try (invoke [request-type minimum maximum]) "
               "(throw (ex-info \"out-of-range-result-admitted\" {})) "
               "(catch :default error "
               "(when (= \"out-of-range-result-admitted\" (ex-message error)) (throw error))))\n"
               "(println \"typed-provider-full-i64-ok\")\n"))
    (let [execution (shell/sh "npx" "nbb" (.getPath script))]
      (is (zero? (:exit execution)) (:err execution))
      (is (= "typed-provider-full-i64-ok\n" (:out execution))))))

(deftest cljs-typed-admission-remains-narrow-to-boundaries
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"typed values currently require"
       (compile-cljs
        "(defn main [] [:record :demo/one [[:x :i64]]]
           (record [:record :demo/one [[:x :i64]]] 1))"))))

;; ───────────────────────── cross-backend consistency ─────────────────────────

(deftest cljs-target-is-in-compatibility-targets-and-agrees-with-other-backends
  (let [source "(defn main [] (+ 1 2))"
        results (map #(compiler/compile-source source %) compiler/targets)]
    (is (contains? compiler/targets :cljs-kotoba-v1))
    (is (= 1 (count (set (map :kir results)))))
    (is (= 3 (:oracle-value (:kir (first results)))))))
