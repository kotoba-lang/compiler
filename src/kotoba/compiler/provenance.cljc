(ns kotoba.compiler.provenance
  #?(:clj (:require [kotoba.compiler.artifact :as artifact]
                    [kotoba.compiler.compatibility :as compatibility])
     :cljs (:require [kotoba.compiler.artifact :as artifact]
                     [kotoba.compiler.compatibility :as compatibility]
                     ["node:crypto" :as crypto]))
  #?(:clj (:import (java.nio.charset StandardCharsets)
                   (java.security MessageDigest))))

(def schema :kotoba.provenance/v1)

;; Same JVM `MessageDigest`/Node `node:crypto` split
;; `kotoba.compiler.artifact/sha256` uses -- see that namespace's own
;; comment for why. `hex` uses `clojure.core/format`, which doesn't exist
;; in cljs at all (not just "throws" -- `format`'s cljs branch is
;; ITSELF unused, `node:crypto`'s `.digest "hex"` already returns a hex
;; string), so it's behind the reader-conditional too, not just its call
;; site.
#?(:clj (defn- hex [bytes]
         (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))
(defn- raw-sha256 [bytes]
  #?(:clj (hex (.digest (MessageDigest/getInstance "SHA-256") ^bytes bytes))
     :cljs (-> (crypto/createHash "sha256") (.update bytes) (.digest "hex"))))
(defn- text-sha256 [value]
  #?(:clj (raw-sha256 (.getBytes ^String value StandardCharsets/UTF_8))
     :cljs (raw-sha256 (js/Buffer.from value "utf8"))))
(defn- byte-size [value]
  #?(:clj (alength (.getBytes ^String value StandardCharsets/UTF_8))
     :cljs (.-length (js/Buffer.from value "utf8"))))
(defn- bytes-identity [format bytes]
  #?(:clj (let [value (byte-array (map unchecked-byte bytes))]
           {:format format :sha256 (raw-sha256 value) :size (alength value)})
     :cljs (let [value (js/Buffer.from (clj->js bytes))]
            {:format format :sha256 (raw-sha256 value) :size (.-length value)})))

(defn- outputs [result]
  (cond-> (sorted-map)
    (= :wasm/v1 (:format result))
    (assoc :primary (bytes-identity :wasm (:bytes result)))
    (= :cljs/v1 (:format result))
    (assoc :primary {:format :cljs-source :sha256 (text-sha256 (:source result))
                     :size (byte-size (:source result))})
    (= :javascript/v1 (:format result))
    (assoc :primary {:format :javascript-source :sha256 (text-sha256 (:source result))
                     :size (byte-size (:source result))})
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

(defn- sha256-equal? [a b]
  #?(:clj (MessageDigest/isEqual
           (.getBytes ^String a StandardCharsets/US_ASCII)
           (.getBytes ^String b StandardCharsets/US_ASCII))
     :cljs (and (= (count a) (count b))
               (crypto/timingSafeEqual (js/Buffer.from a "ascii") (js/Buffer.from b "ascii")))))

(defn verify!
  ([source policy result] (verify! source policy {} result))
  ([source policy build-metadata result]
   (let [actual (:provenance result)
        expected (descriptor source policy build-metadata (dissoc result :provenance))]
    (when-not (and (map? actual)
                   (= (set (keys expected)) (set (keys actual)))
                   (artifact/valid-seal? actual)
                   (sha256-equal? (:sha256 actual) (:sha256 expected)))
      (throw (ex-info "compilation provenance rejected"
                      {:phase :provenance :reason :identity-mismatch})))
     actual)))
