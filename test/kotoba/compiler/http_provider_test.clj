(ns kotoba.compiler.http-provider-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.http :as http]
            [kotoba.compiler.reference-runtime :as runtime]))

(def source
  (str "(ns app.http (:export [post]) (:capabilities #{:http/post}))"
       "(defn post [request " (pr-str http/request-type) "] "
       (pr-str http/result-type) " (typed-cap-call :http/post "
       (pr-str http/request-type) " " (pr-str http/result-type) " request))"))

(defn- hosted [transport]
  (let [provider (http/provider {:allowed-origins #{"https://api.example.test"}
                                 :transport transport})
        kir (ir/lower (:hir (compiler/check-source source {:allow #{[:cap/call 4]}})))]
    (runtime/instantiate kir {:allow #{4} :providers {4 provider}})))

(deftest post-crosses-a-bounded-typed-boundary
  (let [seen (atom nil)
        runtime (hosted (fn [request]
                          (reset! seen request)
                          {:status 201 :headers {:content-type "application/json"}
                           :body "{\"ok\":true}"}))
        headers [http/header-set-type
                 [[http/header-type :content-type "application/json"]]]
        request [http/request-type "https://api.example.test/v1/items"
                 headers "{\"name\":\"kotoba\"}" 5000]]
    (is (= [http/result-type :ok
            [http/response-type 201
             [http/header-set-type
              [[http/header-type :content-type "application/json"]]]
             "{\"ok\":true}"]]
           ((:invoke runtime) 'post [request])))
    (is (= {:url "https://api.example.test/v1/items"
            :headers {:content-type "application/json"}
            :body "{\"name\":\"kotoba\"}" :timeout-ms 5000}
           @seen))))

(deftest destinations-and-timeouts-fail-closed
  (let [called? (atom false)
        runtime (hosted (fn [_] (reset! called? true)))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"origin is not allowed"
         ((:invoke runtime) 'post
          [[http/request-type "https://other.example.test/path"
            [http/header-set-type []] "" 1000]])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"timeout is outside"
         ((:invoke runtime) 'post
          [[http/request-type "https://api.example.test/path"
            [http/header-set-type []] "" 0]])))
    (is (false? @called?))))

(deftest transport-errors-remain-typed-values
  (let [runtime (hosted (fn [_] {:error {:code :http/timeout
                                         :message "deadline exceeded"
                                         :retryable true}}))]
    (is (= [http/result-type :error
            [http/error-type :http/timeout "deadline exceeded" true]]
           ((:invoke runtime) 'post
            [[http/request-type "https://api.example.test/path"
              [http/header-set-type []] "" 1000]])))))

(deftest host-transport-exceptions-are-redacted-and-typed
  (let [runtime (hosted (fn [_] (throw (ex-info "secret host detail" {}))))]
    (is (= [http/result-type :error
            [http/error-type :http/transport "transport failed" false]]
           ((:invoke runtime) 'post
            [[http/request-type "https://api.example.test/path"
              [http/header-set-type []] "" 1000]])))))
