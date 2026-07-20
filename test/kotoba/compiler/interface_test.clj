(ns kotoba.compiler.interface-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.cli :as cli]
            [kotoba.compiler.interface :as interface])
  (:import [java.io StringWriter]))

(def source
  "(ns demo.api (:export [public]))
   (def secret 41)
   (defn public [value :i64] :i64 (+ value secret))
   (defn- hidden [] 0)")

(deftest inspection-is-deterministic-public-and-sealed
  (let [a (interface/inspect-source source)
        b (interface/inspect-source source)]
    (is (= a b))
    (is (interface/valid? a))
    (is (= 'demo.api (:namespace a)))
    (is (= [{:name 'public :arity 1 :param-types [:i64]
             :result :i64 :effects #{}}]
           (:exports a)))
    (is (not-any? #(contains? a %) [:functions :body :constants :source :path]))
    (is (not (interface/valid? (assoc-in a [:exports 0 :result] :string))))))

(deftest cli-inspect-emits-the-versioned-interface
  (let [file (doto (java.io.File/createTempFile "kotoba-interface-" ".cljk")
               (.deleteOnExit))
        out (StringWriter.)]
    (spit file source)
    (binding [*out* out] (cli/-main "inspect" (.getPath file)))
    (let [value (edn/read-string (str out))]
      (is (= interface/schema (:format value)))
      (is (interface/valid? value)))))

(deftest inspection-exposes-source-name-and-sealed-multi-arity-abi-symbol
  (let [value (interface/inspect-source
               "(ns demo.multi (:export [offset]))
                (defn offset ([x] (offset x 1)) ([x delta] (+ x delta)))")]
    (is (= [{:name 'offset :abi-name 'offset$arity$1 :arity 1
             :param-types [:i64] :result :i64 :effects #{}}
            {:name 'offset :abi-name 'offset$arity$2 :arity 2
             :param-types [:i64 :i64] :result :i64 :effects #{}}]
           (:exports value)))
    (is (interface/valid? value))))
