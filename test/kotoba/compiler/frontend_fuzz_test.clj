(ns kotoba.compiler.frontend-fuzz-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.verifier :as verifier])
  (:import [java.util Random]))

(def seed 0x4b4f544f42414645)
(def alphabet "()[]{};\n abcdefghijklmnopqrstuvwxyz0123456789+-*/<>=#\"\\:")
(def valid-source
  "(defn helper [x] (let [y (* x 2)] (if (> y 0) y (- y))))\n(defn main [] (helper -21))\n")

(defn- mutate [^Random rng source]
  (let [length (count source) operation (.nextInt rng 3)
        index (if (zero? length) 0 (.nextInt rng length))
        ch (.charAt alphabet (.nextInt rng (count alphabet)))]
    (case operation
      0 (str (subs source 0 index) ch (subs source index))
      1 (if (zero? length) (str ch)
            (str (subs source 0 index) ch (subs source (inc index))))
      2 (if (zero? length) ""
            (str (subs source 0 index) (subs source (inc index)))))))

(defn- random-source [^Random rng]
  (apply str (repeatedly (.nextInt rng 256)
                         #(.charAt alphabet (.nextInt rng (count alphabet))))))

(defn- controlled-compile? [source]
  (try
    (let [hir (frontend/analyze source)
          policy {:allow (:effects hir)}
          results (into {} (map (fn [target]
                                  [target (compiler/compile-source source target policy)])
                                compiler/targets))
          kirs (map :kir (vals results))
          x86 (:artifact (get results :x86_64-kotoba-v1))
          arm (:artifact (get results :aarch64-kotoba-v1))
          wasm (get results :wasm32-kotoba-v1)
          wasm-again (compiler/compile-source source :wasm32-kotoba-v1 policy)]
      (and (= 1 (count (set kirs)))
           (= x86 (verifier/verify-artifact! x86))
           (= arm (verifier/verify-artifact! arm))
           (= (seq (:bytes wasm)) (seq (:bytes wasm-again)))))
    (catch clojure.lang.ExceptionInfo error
      (contains? #{:read :subset :admission :ir :verify} (:phase (ex-data error))))))

(deftest deterministic-frontend-byte-and-grammar-corpus
  (let [rng (Random. seed)]
    (dotimes [case-id 300]
      (is (controlled-compile? (mutate rng valid-source))
          (str "mutation seed=" seed " case=" case-id)))
    (dotimes [case-id 300]
      (is (controlled-compile? (random-source rng))
          (str "raw seed=" seed " case=" case-id)))))

(deftest frontend-admission-bounds-host-resource-inputs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source must be a string"
                        (frontend/analyze nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reader nesting"
                        (frontend/analyze
                         (str (apply str (repeat 513 "(")) "0"
                              (apply str (repeat 513 ")"))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside i64"
                        (frontend/analyze
                         "(defn main [] 9223372036854775808)")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source reader rejected"
                        (frontend/analyze "(defn main [] \"unterminated)"))))
