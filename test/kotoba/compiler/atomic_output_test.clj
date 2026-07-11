(ns kotoba.compiler.atomic-output-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.atomic-output :as atomic-output])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn- delete-tree! [^Path root]
  (when (Files/exists root (make-array LinkOption 0))
    (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (doseq [path (reverse (iterator-seq (.iterator paths)))]
        (Files/deleteIfExists path)))))

(deftest output-is-atomic-and-does-not-follow-destination-symlinks
  (let [directory (Files/createTempDirectory "kotoba-output-"
                                              (make-array FileAttribute 0))
        victim (.resolve directory "victim")
        output (.resolve directory "output.edn")]
    (try
      (Files/write victim (.getBytes "unchanged" StandardCharsets/UTF_8)
                   (make-array java.nio.file.OpenOption 0))
      (Files/createSymbolicLink output victim (make-array FileAttribute 0))
      (atomic-output/write-edn! (str output) {:safe true})
      (is (= "unchanged" (Files/readString victim)))
      (is (not (Files/isSymbolicLink output)))
      (is (= {:safe true} (edn/read-string (Files/readString output))))
      (finally (delete-tree! directory)))))

(deftest private-output-is-created-with-owner-only-permissions
  (let [directory (Files/createTempDirectory "kotoba-private-"
                                              (make-array FileAttribute 0))
        output (.resolve directory "key.edn")]
    (try
      (atomic-output/write-edn! (str output) {:private "key"} {:private? true})
      (is (= (PosixFilePermissions/fromString "rw-------")
             (Files/getPosixFilePermissions output (make-array LinkOption 0))))
      (finally (delete-tree! directory)))))

(deftest executable-output-is-owner-only-and-executable
  (let [directory (Files/createTempDirectory "kotoba-executable-"
                                              (make-array FileAttribute 0))
        output (.resolve directory "loader")]
    (try
      (atomic-output/write-bytes! (str output) (.getBytes "binary" StandardCharsets/UTF_8)
                                  {:executable? true})
      (is (= (PosixFilePermissions/fromString "rwx------")
             (Files/getPosixFilePermissions output (make-array LinkOption 0))))
      (finally (delete-tree! directory)))))
