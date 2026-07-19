(ns kotoba.compiler.artifact
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.security MessageDigest])
     :cljs (:require ["node:crypto" :as crypto]
                     [kotoba.compiler.cljs-i64 :as i64])))

;; A raw `.kotoba` i64 literal survives as a cljs `bigint` all the way into
;; KIR bodies (`kotoba.compiler.kotoba-reader`'s own docstring: parsed as
;; bigint specifically so a boundary literal like -9223372036854775808
;; round-trips exactly). `pr-str` on a bare `bigint` in THIS nbb/SCI
;; runtime renders `#object[BigInt 120]` -- neither valid EDN nor
;; byte-identical to the JVM path's plain `120` for the SAME KIR, which
;; would make every `sha256`/`canonical-bytes` call below produce a
;; DIFFERENT digest for byte-identical values depending only on which
;; runtime compiled them (confirmed live: kir-sha256 differed between the
;; JVM and nbb-native compiles of the identical `examples/fuel.kotoba`
;; source before this fix). `extend-protocol IPrintWithWriter` -- the
;; normal way to teach `pr-str` a new type -- does not resolve under nbb's
;; SCI interpreter (see `kotoba.compiler.kotoba-reader`'s own namespace
;; docstring for why that reader exists at all: the SAME protocol-dispatch
;; gap). Printing a `(symbol "120")` instead is a reader-transparent
;; workaround, not a hack: `pr-str` of ANY symbol renders its bare name
;; with no quoting, and re-reading that exact same digit text back through
;; a standard EDN/Clojure reader parses it as a NUMBER token again (token
;; classification is purely lexical, not tied to what originally produced
;; the characters) -- so the printed bytes, and everything hashed from
;; them, are identical to what a plain integer would have produced, with
;; no precision loss for values outside `js/Number`'s safe range (unlike
;; converting through `js/Number` first would cause).
;; The write-time counterpart to `canonical` below, MINUS the
;; sorting/normalization: preserves the value's own map-key/collection
;; order (unlike `canonical`, which is deliberately order-independent for
;; hashing) so a `pr-str`'d artifact/provenance map keeps the exact same
;; shape a plain `pr-str` on the JVM path would produce, differing only in
;; the one substitution that's actually needed. Exported (not `defn-`) so
;; write-time callers (`kotoba.compiler.nbb.cli`'s `compile` command) can
;; apply it to a whole result map before `pr-str`, same reason `canonical`
;; itself needs it before hashing.
(defn edn-safe [x]
  (cond
    #?@(:cljs [(i64/bigint-value? x) (symbol (.toString x))])
    (map? x) (into (empty x) (map (fn [[k v]] [(edn-safe k) (edn-safe v)])) x)
    (set? x) (into (empty x) (map edn-safe) x)
    (vector? x) (mapv edn-safe x)
    (sequential? x) (map edn-safe x)
    :else x))

(defn- canonical [x]
  (cond
    #?@(:cljs [(i64/bigint-value? x) (symbol (.toString x))])
    (map? x) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                   (map (fn [[k v]] [(canonical k) (canonical v)])) x)
    (set? x) (vec (sort-by pr-str (map canonical x)))
    (vector? x) (mapv canonical x)
    (sequential? x) (mapv canonical x)
    :else x))

(defn sha256 [value]
  (let [text (pr-str (canonical value))]
    #?(:clj (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                                  (.getBytes ^String text StandardCharsets/UTF_8))]
             (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest)))
       :cljs (-> (crypto/createHash "sha256") (.update text "utf8") (.digest "hex")))))

(defn canonical-bytes [value]
  (let [text (pr-str (canonical value))]
    #?(:clj (.getBytes ^String text StandardCharsets/UTF_8)
       :cljs (js/Buffer.from text "utf8"))))

(defn seal [artifact]
  (let [payload (dissoc artifact :sha256)]
    (assoc payload :sha256 (sha256 payload))))

;; Constant-time hex-digest comparison -- `MessageDigest/isEqual` on the
;; JVM, `node:crypto`'s `timingSafeEqual` on Node (both fixed-length
;; 64-char sha256 hex, so the length precondition `timingSafeEqual`
;; requires always holds for two genuine digests; a length mismatch means
;; `(:sha256 artifact)` was never a real digest to begin with, so failing
;; that check the same way a mismatch would is the correct outcome, not a
;; bypass).
(defn valid-seal? [artifact]
  (and (string? (:sha256 artifact))
       (let [expected (sha256 (dissoc artifact :sha256))]
         #?(:clj (MessageDigest/isEqual
                  (.getBytes ^String (:sha256 artifact) StandardCharsets/US_ASCII)
                  (.getBytes ^String expected StandardCharsets/US_ASCII))
            :cljs (and (= (count (:sha256 artifact)) (count expected))
                      (crypto/timingSafeEqual
                       (js/Buffer.from (:sha256 artifact) "ascii")
                       (js/Buffer.from expected "ascii")))))))
