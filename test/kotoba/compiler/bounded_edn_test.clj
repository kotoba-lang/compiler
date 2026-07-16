(ns kotoba.compiler.bounded-edn-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.bounded-edn :as bounded-edn])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- trap [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error error)))

(deftest accepts-one-bounded-edn-value
  (is (= {:allow #{[:cap/call 7]} :args [1 -2]}
         (bounded-edn/read-string "{:allow #{[:cap/call 7]}, :args [1 -2]}"))))

(deftest rejects-structural-resource-attacks
  (doseq [[label input message]
          [["empty" "  ; nothing\n" #"empty"]
           ["trailing form" "{} {}" #"trailing forms"]
           ["reader depth" (str (apply str (repeat 129 "["))
                                 (apply str (repeat 129 "]"))) #"nesting"]
           ["oversized atom" (apply str (repeat 4097 "9")) #"token"]
           ["tagged object" "#inst \"2026-07-11\"" #"dispatch forms"]
           ["discard form" "{:safe true} #_ :hidden" #"dispatch forms"]
           ;; Previously untested here even though bounded-edn/validate-shape!
           ;; has enforced both since this namespace's introduction -- added
           ;; alongside porting these same two limits to the nbb-native path
           ;; (kotoba.compiler.nbb.cli/validate-edn-shape!, scripts/conformance.cljs).
           ["oversized string" (str "\"" (apply str (repeat 1048577 "a")) "\"") #"string exceeds"]
           ["too many nodes" (str "[" (clojure.string/join " " (repeat 200001 "1")) "]") #"too many nodes"]]]
    (testing label
      (let [error (trap #(bounded-edn/read-string input))]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (re-find message (ex-message error)))
        (is (= :decode (:phase (ex-data error))))))))

(deftest file-input-is-byte-bounded-and-strict-utf8
  (let [path (Files/createTempFile "kotoba-bounded-edn-" ".edn"
                                   (make-array FileAttribute 0))]
    (try
      (Files/write path (byte-array (map unchecked-byte [0x7b 0x7d 0xff]))
                   (make-array java.nio.file.OpenOption 0))
      (is (re-find #"valid UTF-8"
                   (ex-message (trap #(bounded-edn/read-file (str path))))))
      (Files/write path (.getBytes "{:0123456789 1}" "UTF-8")
                   (make-array java.nio.file.OpenOption 0))
      (with-redefs [bounded-edn/max-edn-bytes 8]
        (let [error (trap #(bounded-edn/read-file (str path)))]
          (is (re-find #"byte limit" (ex-message error)))
          (is (= 8 (:limit (ex-data error))))))
      (finally (Files/deleteIfExists path)))))
