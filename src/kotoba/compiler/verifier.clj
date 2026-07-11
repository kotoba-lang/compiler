(ns kotoba.compiler.verifier
  (:require [kotoba.compiler.backend.native :as native]))

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

(defn verify-artifact! [{:keys [format target value code] :as artifact}]
  (when-not (= :kotoba.kexe/v1 format)
    (throw (ex-info "unknown artifact format" {:phase :verify})))
  (verify-code! target value (byte-array (map unchecked-byte code)))
  artifact)
