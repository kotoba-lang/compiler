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

(defn admit! [runtime trust]
  (let [identity (identity-sha256 runtime)
        trusted (:trusted-runtime-sha256 trust)
        revoked (:revoked-runtime-sha256 trust #{})]
    (when (contains? revoked identity)
      (throw (ex-info "native runtime identity is revoked"
                      {:phase :trust :runtime-sha256 identity})))
    ;; Absence retains v1 trust-file compatibility. Once the field is present,
    ;; it is an explicit allowlist and an empty set denies every native runtime.
    (when (and (contains? trust :trusted-runtime-sha256)
               (not (contains? trusted identity)))
      (throw (ex-info "native runtime identity is not trusted"
                      {:phase :trust :runtime-sha256 identity})))
    {:runtime-sha256 identity :trusted? (contains? trusted identity)}))
