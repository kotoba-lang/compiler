(ns kotoba.compiler.component-artifact
  "Validated Component Model packaging for the scalar Canonical ABI slice."
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

(def tool-version "1.243.0")
(def target :wasm-component-kotoba-v1)

(defn- reject [message data]
  (throw (ex-info message (assoc data :phase :component-artifact))))

(defn- sha256 [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- run-process! [args]
  (let [process (.start (doto (ProcessBuilder. ^java.util.List (vec args))
                          (.redirectErrorStream true)))
        output (String. (.readAllBytes (.getInputStream process)) StandardCharsets/UTF_8)
        exit (.waitFor process)]
    (when-not (zero? exit)
      (reject "wasm-tools component packaging failed"
              {:command (vec args) :exit exit :output output}))
    output))

(defn- assert-tool! []
  (let [output (.trim ^String (run-process! ["wasm-tools" "--version"]))]
    (when-not (= (str "wasm-tools " tool-version) output)
      (reject "wasm-tools version is not pinned"
              {:expected tool-version :actual output}))))

(defn- scalar? [type]
  (contains? #{:i64 :f32 :f64} type))

(defn assert-scalar-slice! [kir wit]
  (when (seq (:imports wit))
    (reject "component capability imports require Canonical provider lowering"
            {:imports (:imports wit)}))
  (doseq [{:keys [name param-types result]} (:functions kir)
          :when (contains? (set (:exports kir)) name)]
    (when-not (and (every? scalar? param-types) (scalar? result))
      (reject "structured component signatures require Canonical memory lowering"
              {:function name :param-types param-types :result result})))
  true)

(defn package
  "Embed WIT metadata into a compatible core module and produce a validated
  Component Model binary with the pinned official toolchain."
  [core-bytes kir wit]
  (assert-scalar-slice! kir wit)
  (assert-tool!)
  (let [dir (Files/createTempDirectory "kotoba-component-" (make-array FileAttribute 0))
        world (.resolve dir "world.wit")
        core (.resolve dir "core.wasm")
        embedded (.resolve dir "embedded.wasm")
        component (.resolve dir "component.wasm")]
    (try
      (Files/writeString world ^String (:source wit) (make-array java.nio.file.OpenOption 0))
      (Files/write core ^bytes core-bytes (make-array java.nio.file.OpenOption 0))
      (run-process! ["wasm-tools" "component" "embed" (str world) (str core)
                     "--encoding" "utf8" "-o" (str embedded)])
      (run-process! ["wasm-tools" "component" "new" (str embedded)
                     "-o" (str component)])
      (let [bytes (Files/readAllBytes component)]
        (when-not (= [0 97 115 109 13 0 1 0]
                     (mapv #(bit-and (int %) 0xff) (take 8 bytes)))
          (reject "component binary preamble is invalid" {}))
        {:format :wasm-component/v1 :target target :wasi-version "0.3.0"
         :bytes bytes :sha256 (sha256 bytes) :wit-sha256 (:sha256 wit)
         :imports (:imports wit) :exports (:exports wit)
         :canonical-name-encoding :wit-component/legacy
         :tool {:name :wasm-tools :version tool-version}})
      (finally
        (doseq [path [component embedded core world]] (Files/deleteIfExists path))
        (Files/deleteIfExists dir)))))
