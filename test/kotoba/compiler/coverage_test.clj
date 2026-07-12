(ns kotoba.compiler.coverage-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.coverage :as coverage])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def digest (apply str (repeat 64 "a")))
(def evidence (apply str (repeat 64 "b")))
(def base
  {:format :kotoba.coverage-manifest/v1
   :dataset {:name "fixture" :url "https://example.test/data"
             :retrieved-at 1 :sha256 digest}
   :population :interactive-web
   :period {:from "2026-04" :to "2026-06"}
   :observations [{:platform :a :share-bps 9400}
                  {:platform :b :share-bps 100}
                  {:platform :unknown :share-bps 500}]
   :support [{:platform :a :status :release :paths #{:native} :evidence [evidence]}
             {:platform :b :status :preview :paths #{:wasm} :evidence []}]
   :threshold-bps 9500})

(deftest coverage-excludes-unknown-and-counts-only-evidenced-release-platforms
  (is (= {:format :kotoba.coverage-report/v1
          :population :interactive-web
          :period {:from "2026-04" :to "2026-06"}
          :dataset-sha256 digest
          :unknown-bps 500 :identifiable-bps 9500 :supported-bps 9400
          :coverage-bps 9894 :threshold-bps 9500 :goal-met? true
          :release-platforms #{:a}}
         (coverage/report base))))

(deftest coverage-manifests-fail-closed
  (doseq [[label malformed]
          [["unknown field" (assoc base :claim 10000)]
           ["wrong total" (assoc-in base [:observations 0 :share-bps] 9399)]
           ["duplicate platform" (assoc-in base [:observations 1 :platform] :a)]
           ["missing unknown" (assoc-in base [:observations 2 :platform] :c)]
           ["release without evidence" (assoc-in base [:support 0 :evidence] [])]
           ["preview with forged evidence" (assoc-in base [:support 1 :evidence] [evidence])]
           ["unknown release platform" (assoc-in base [:support 1 :platform] :c)]
           ["invalid digest" (assoc-in base [:dataset :sha256] "a")]
           ["invalid period" (assoc-in base [:period :from] "2026-13")]]]
    (testing label
      (is (thrown? clojure.lang.ExceptionInfo (coverage/report malformed))))))

(deftest raw-dataset-bytes-are-bound-to-the-manifest
  (let [path (Files/createTempFile "kotoba-coverage" ".csv"
                                   (make-array FileAttribute 0))]
    (try
      (Files/write path (.getBytes "fixture" "UTF-8") (make-array java.nio.file.OpenOption 0))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"digest mismatch"
                            (coverage/verify-dataset! base (str path))))
      (finally (Files/deleteIfExists path)))))
