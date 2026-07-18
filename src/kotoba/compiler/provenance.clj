(ns kotoba.compiler.provenance
  (:require [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.compatibility :as compatibility])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def schema :kotoba.provenance/v1)

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))
(defn- raw-sha256 [^bytes bytes]
  (hex (.digest (MessageDigest/getInstance "SHA-256") bytes)))
(defn- text-sha256 [^String value]
  (raw-sha256 (.getBytes value StandardCharsets/UTF_8)))
(defn- bytes-identity [format bytes]
  (let [value (byte-array (map unchecked-byte bytes))]
    {:format format :sha256 (raw-sha256 value) :size (alength value)}))

(defn- outputs [result]
  (cond-> (sorted-map)
    (= :wasm/v1 (:format result))
    (assoc :primary (bytes-identity :wasm (:bytes result)))
    (= :cljs/v1 (:format result))
    (assoc :primary {:format :cljs-source :sha256 (text-sha256 (:source result))
                     :size (alength (.getBytes ^String (:source result) StandardCharsets/UTF_8))})
    (= :javascript/v1 (:format result))
    (assoc :primary {:format :javascript-source :sha256 (text-sha256 (:source result))
                     :size (alength (.getBytes ^String (:source result) StandardCharsets/UTF_8))})
    (= :kexe/v1 (:format result))
    (assoc :primary {:format :kotoba.kexe/v1 :sha256 (get-in result [:artifact :sha256])})
    (:binary result) (assoc :binary (bytes-identity (:format (:binary result)) (:bytes (:binary result))))
    (:object result) (assoc :object (bytes-identity (:format (:object result)) (:bytes (:object result))))))

(defn descriptor
  ([source policy result] (descriptor source policy {} result))
  ([source policy build-metadata result]
   (artifact/seal
    {:format schema
    :builder :kotoba-compiler/v1
    :compiler compatibility/compiler-version
    :language compatibility/language-version
    :source-sha256 (text-sha256 source)
    :policy-sha256 (artifact/sha256 policy)
    :build-metadata-sha256 (artifact/sha256 build-metadata)
    :hir-sha256 (artifact/sha256 (:hir result))
    :kir-sha256 (artifact/sha256 (:kir result))
    :target (:target result)
    :target-profile-sha256 (artifact/sha256 (or (:target-profile result)
                                                (get-in result [:artifact :target-profile])))
    :compatibility-sha256 (artifact/sha256 (:compatibility result))
    :outputs (outputs result)})))

(defn attach [source policy build-metadata result]
  (let [provenance (descriptor source policy build-metadata result)]
    (cond-> (assoc result :provenance provenance)
      (:manifest result) (assoc-in [:manifest :kotoba.artifact/provenance] provenance))))

(defn verify!
  ([source policy result] (verify! source policy {} result))
  ([source policy build-metadata result]
   (let [actual (:provenance result)
        expected (descriptor source policy build-metadata (dissoc result :provenance))]
    (when-not (and (map? actual)
                   (= (set (keys expected)) (set (keys actual)))
                   (artifact/valid-seal? actual)
                   (MessageDigest/isEqual
                    (.getBytes ^String (:sha256 actual) StandardCharsets/US_ASCII)
                    (.getBytes ^String (:sha256 expected) StandardCharsets/US_ASCII)))
      (throw (ex-info "compilation provenance rejected"
                      {:phase :provenance :reason :identity-mismatch})))
     actual)))
