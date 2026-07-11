(ns kotoba.compiler.verifier
  (:require [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.backend.native :as native]))

(defn- unsigned [bs] (mapv #(bit-and (int %) 0xff) bs))

(defn verify-code! [target value code]
  (let [actual (unsigned code)
        expected (unsigned
                  (case target
                    :x86_64-kotoba-v1 (native/emit-x86-64 value)
                    :aarch64-kotoba-v1 (native/emit-aarch64 value)
                    (throw (ex-info "not a native verifier target" {:target target}))))]
    (when-not (= expected actual)
      (throw (ex-info "native instruction stream rejected" {:phase :verify :target target})))
    {:verified? true :instruction-bytes (count actual) :target target}))

(defn verify-artifact! [{:keys [format target value code effects limits kir-sha256] :as kexe}]
  (when-not (= :kotoba.kexe/v1 format)
    (throw (ex-info "unknown artifact format" {:phase :verify})))
  (when-not (and (string? kir-sha256) (re-matches #"[0-9a-f]{64}" kir-sha256))
    (throw (ex-info "missing or malformed KIR identity" {:phase :verify})))
  (when-not (= #{} effects)
    (throw (ex-info "bootstrap native target admits no effects" {:phase :verify})))
  (when-not (= {:memory-bytes 0 :fuel 1 :stack-bytes 0} limits)
    (throw (ex-info "resource limits are not admitted" {:phase :verify :limits limits})))
  (when-not (and (vector? code) (<= 1 (count code) 64)
                 (every? #(and (integer? %) (<= 0 % 255)) code))
    (throw (ex-info "malformed code bytes" {:phase :verify})))
  (when-not (artifact/valid-seal? kexe)
    (throw (ex-info "artifact integrity mismatch" {:phase :verify})))
  (verify-code! target value (byte-array (map unchecked-byte code)))
  kexe)
