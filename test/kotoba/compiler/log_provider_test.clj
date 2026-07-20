(ns kotoba.compiler.log-provider-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.log :as log]
            [kotoba.compiler.reference-runtime :as runtime]))

(def source
  (str "(ns app.log (:export [append read]) "
       "(:capabilities #{:log/append :log/read}))"
       "(defn append [request " (pr-str log/append-request-type) "] "
       (pr-str log/append-result-type) " (typed-cap-call :log/append "
       (pr-str log/append-request-type) " " (pr-str log/append-result-type) " request))"
       "(defn read [request " (pr-str log/read-request-type) "] "
       (pr-str log/read-result-type) " (typed-cap-call :log/read "
       (pr-str log/read-request-type) " " (pr-str log/read-result-type) " request))"))

(defn- hosted []
  (let [kit (log/create-provider)
        kir (ir/lower (:hir (compiler/check-source
                             source {:allow #{[:cap/call 5] [:cap/call 6]}})))]
    {:kit kit
     :runtime (runtime/instantiate kir {:allow #{5 6} :providers (:providers kit)})}))

(deftest append-and-read-use-structured-bounded-values
  (let [{:keys [runtime]} (hosted)
        fields [log/field-set-type [[log/field-type :request/id "r-1"]]]
        request [log/append-request-type :log/info :app/started "ready" fields]
        entry [log/entry-type 1 :log/info :app/started "ready" fields]]
    (is (= [log/append-result-type 1]
           ((:invoke runtime) 'append [request])))
    (is (= [log/read-result-type 1 1 false [log/entry-set-type [entry]]]
           ((:invoke runtime) 'read [[log/read-request-type 0 8]])))))

(deftest field-and-read-limits-fail-before-mutation
  (let [{:keys [kit runtime]} (hosted)
        fields (mapv (fn [index]
                       [log/field-type (keyword "field" (str index)) "value"])
                     (range 5))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"field limit"
         ((:invoke runtime) 'append
          [[log/append-request-type :log/info :app/event "message"
            [log/field-set-type fields]]])))
    (is (empty? ((:snapshot kit))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"read limit"
         ((:invoke runtime) 'read [[log/read-request-type 0 9]])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"cursor must be non-negative"
         ((:invoke runtime) 'read [[log/read-request-type -1 1]])))))

(deftest retained-window-signals-truncation
  (let [{:keys [runtime]} (hosted)
        fields [log/field-set-type []]]
    (dotimes [index (inc log/max-retained-entries)]
      ((:invoke runtime) 'append
       [[log/append-request-type :log/info :app/event (str index) fields]]))
    (let [[_ oldest latest truncated [_ entries]]
          ((:invoke runtime) 'read [[log/read-request-type 0 1]])]
      (is (= 2 oldest))
      (is (= 257 latest))
      (is (true? truncated))
      (is (= 2 (second (first entries)))))))
