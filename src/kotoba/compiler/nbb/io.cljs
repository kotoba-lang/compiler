(ns kotoba.compiler.nbb.io
  "Minimal Node-`fs`-based file I/O for the nbb-native compile/check path
  (`bin/kotoba`'s `wasm32-*` fast path -- see its own comment). Deliberately
  NOT a port of `kotoba.compiler.atomic-output`/`kotoba.compiler.bounded-edn`:
  those cover the full JVM release/signing surface (owner-only POSIX
  permissions, Windows ACL fallback, `fsync`-before-rename durability,
  bounded/hardened EDN parsing with node-count limits) that a wasm32
  DEVELOPMENT compile of an ordinary, non-secret output file doesn't need.
  This intentionally covers less: plain UTF-8 read with strict malformed-
  input rejection, and a temp-file-then-rename write for atomicity without
  the permission/fsync hardening. Revisit if/when a signing or release
  artifact ever needs to flow through the nbb path -- out of scope for now."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

;; Matches `kotoba.compiler.bounded-edn/max-source-bytes` exactly (the
;; stricter of its two limits -- `.kotoba` source and `--policy` EDN both
;; go through this same function, so both get the tighter cap). Checked
;; against the RAW byte count before UTF-8 decoding, same order the JVM
;; path's `read-bytes` uses -- confirmed live: without this, a >1MiB
;; `.kotoba` source silently reached `frontend/analyze`'s own (differently
;; worded) size check instead of failing here with the exact message
;; `scripts/conformance.cljs` asserts on.
(def ^:private max-bytes (* 1024 1024))

(defn read-text-file [p]
  (when-not (and (string? p) (seq p))
    (throw (ex-info "input path is required" {:phase :decode})))
  (let [buf (try
              (.readFileSync fs p)
              (catch :default error
                (throw (ex-info "input could not be read" {:phase :decode :path p} error))))]
    (when (> (.-length buf) max-bytes)
      (throw (ex-info "input exceeds byte limit" {:phase :decode :path p})))
    (let [decoder (js/TextDecoder. "utf-8" #js {:fatal true})]
      (try
        (.decode decoder buf)
        (catch :default error
          (throw (ex-info "input is not valid UTF-8" {:phase :decode :path p} error)))))))

(defn- write-atomic! [output-path write-tmp!]
  (when-not (and (string? output-path) (seq output-path))
    (throw (ex-info "output path is required" {:phase :output})))
  (let [dir (.dirname path output-path)]
    (when-not (try (.isDirectory (.statSync fs dir)) (catch :default _ false))
      (throw (ex-info "output parent must be a directory" {:phase :output :path output-path})))
    (let [tmp (path/join dir (str "." (path/basename output-path) "."
                                  (.toString (js/Math.random) 36) ".tmp"))]
      (try
        (write-tmp! tmp)
        (fs/renameSync tmp output-path)
        output-path
        (catch :default error
          (try (fs/unlinkSync tmp) (catch :default _ nil))
          (throw (ex-info "atomic output failed" {:phase :output :path output-path} error)))))))

(defn write-bytes! [output-path ^js bytes]
  (write-atomic! output-path #(fs/writeFileSync % bytes)))

;; Same tmp-file-then-rename atomicity as `write-bytes!`, for the native
;; `compile` path's `.kexe`/`.provenance.edn` output -- a `pr-str`'d EDN
;; string, not raw bytes (mirrors `kotoba.compiler.atomic-output/write-edn!`
;; on the JVM path, minus the fsync/permission hardening this namespace's
;; own docstring already explains is out of scope here).
(defn write-text! [output-path ^string text]
  (write-atomic! output-path #(fs/writeFileSync % text "utf8")))
