(ns kotoba.compiler.ios-aot-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ios-aot :as ios-aot]))

(def source
  "(defn helper [x] (+ x 1)) (defn main [] (helper 41))")

(deftest packages-verified-ios-code-as-deterministic-static-text
  (let [ios (:artifact (compiler/compile-source source :aarch64-ios-kotoba-v1))
        first (ios-aot/package ios 'main)
        second (ios-aot/package ios 'main)
        assembly (String. ^bytes (:assembly first) "UTF-8")]
    (is (= (seq (:assembly first)) (seq (:assembly second))))
    (is (= (:manifest first) (:manifest second)))
    (is (= :kotoba.ios-aot/v1 (get-in first [:manifest :format])))
    (is (= :aarch64-ios-kotoba-v1 (get-in first [:manifest :target])))
    (is (= 0 (get-in first [:manifest :entry :arity])))
    (is (.contains assembly ".section __TEXT,__text,regular,pure_instructions"))
    (is (.contains assembly ".globl _kotoba_ios_code_end"))
    (is (.contains assembly ".no_dead_strip _kotoba_ios_code_end"))
    (is (.contains assembly ".set _kotoba_ios_entry, _kotoba_ios_code_start + "))
    (is (.contains assembly ".asciz \"aarch64-ios-kotoba-v1\""))))

(deftest rejects-non-ios-and-substituted-artifacts
  (let [android (:artifact (compiler/compile-source source :aarch64-android-kotoba-v1))
        ios (:artifact (compiler/compile-source source :aarch64-ios-kotoba-v1))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit iOS target"
                          (ios-aot/package android 'main)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target profile"
                          (ios-aot/package
                           (artifact/seal
                            (assoc ios :target-profile (:target-profile android))) 'main)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not exported"
                          (ios-aot/package ios 'missing)))))
