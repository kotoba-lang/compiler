(ns kotoba.compiler.component-wit-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as shell]
            [kotoba.compiler.component-wit :as wit])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def kir
  {:format :kotoba.kir/v4
   :exports ['invoke]
   :schemas {:app/request [:record :app/request [[:url :string]]]
             :app/result [:variant :app/result
                          [[:ok [:ref :app/request]] [:error :string]]]}
   :functions [{:name 'invoke :params ['request]
                :param-types [[:ref :app/request]] :result [:ref :app/result]
                :body '(typed-cap-call 4 [:ref :app/request]
                                        [:ref :app/result] request)}]})

(deftest emits-deterministic-closed-wit-world
  (let [a (wit/emit kir)
        b (wit/emit kir)]
    (is (= a b))
    (is (= "0.3.0" (:wasi-version a)))
    (is (= [:http/post] (:imports a)))
    (is (= ['invoke] (:exports a)))
    (is (re-find #"package kotoba:application@1.0.0" (:source a)))
    (is (re-find #"interface http-post" (:source a)))
    (is (re-find #"import http-post;" (:source a)))
    (is (re-find #"export invoke: func\(request: app-request\) -> app-result;" (:source a)))
    (is (not (re-find #"wasi:filesystem|wasi:sockets" (:source a))))))

(deftest emitted-package-is-accepted-by-the-official-wit-toolchain
  (let [path (Files/createTempFile "kotoba-component-" ".wit"
                                   (make-array FileAttribute 0))]
    (try
      (Files/writeString path (:source (wit/emit kir)) (make-array java.nio.file.OpenOption 0))
      (let [result (shell/sh "wasm-tools" "component" "embed" (str path) "--dummy" "-t")]
        (is (zero? (:exit result)) (:err result))
        (is (re-find #"component-type" (:out result))))
      (finally (Files/deleteIfExists path)))))

(deftest rejects-unregistered-capabilities-and-name-collisions
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no WIT contract"
                        (wit/emit (assoc-in kir [:functions 0 :body]
                                           '(typed-cap-call 255 :i64 :i64 0)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collide"
                        (wit/emit (assoc kir :schemas
                                         {:app/a [:record :app/a [[:x :i64]]]
                                          :app.a [:record :app.a [[:x :i64]]]})))))
