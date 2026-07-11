(ns kotoba.compiler.runtime-identity
  (:require [kotoba.compiler.artifact :as artifact]))

(def loader-source-sha256
  "Pinned identity of the reviewed native loader source."
  "85aab0274e861d3e7f9c5dda6de5dcb92b1a2b21e427c797ea2bf991078c2b18")

(def ^:private fields
  #{:format :loader-source-sha256 :loader-binary-sha256
    :compiler-identity-sha256})

(defn- sha256? [value]
  (and (string? value) (boolean (re-matches #"[0-9a-f]{64}" value))))

(defn validate! [runtime]
  (when-not (and (map? runtime)
                 (= fields (set (keys runtime)))
                 (= :kotoba.native-runtime/v1 (:format runtime))
                 (= loader-source-sha256 (:loader-source-sha256 runtime))
                 (sha256? (:loader-binary-sha256 runtime))
                 (sha256? (:compiler-identity-sha256 runtime)))
    (throw (ex-info "native runtime identity rejected"
                    {:phase :runtime-identity})))
  runtime)

(defn identity-sha256 [runtime]
  (artifact/sha256 (validate! runtime)))

(defn validate-measurement! [measurement]
  (when-not (and (map? measurement)
                 (= #{:format :runtime} (set (keys measurement)))
                 (= :kotoba.runtime-measurement/v1 (:format measurement)))
    (throw (ex-info "runtime measurement schema mismatch"
                    {:phase :runtime-identity})))
  (validate! (:runtime measurement))
  measurement)

(defn admit! [runtime trust]
  (let [identity (identity-sha256 runtime)
        trusted (:trusted-runtime-sha256 trust)
        revoked (:revoked-runtime-sha256 trust #{})]
    (when (contains? revoked identity)
      (throw (ex-info "native runtime identity is revoked"
                      {:phase :trust :runtime-sha256 identity})))
    ;; Native execution is never a trust-on-first-use operation. A measured
    ;; runtime must be reviewed and explicitly provisioned before guest entry.
    (when-not (and (set? trusted) (contains? trusted identity))
      (throw (ex-info "native runtime identity is not trusted"
                      {:phase :trust :runtime-sha256 identity})))
    {:runtime-sha256 identity :trusted? true}))
