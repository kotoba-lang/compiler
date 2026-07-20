(ns kotoba.compiler.backend-qualification
  "CI gate binding backend qualification claims to the provider manifest."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [kotoba.compiler.artifact :as artifact]))

(def manifest-resource "kotoba/lang/provider-conformance-v1.edn")
(def qualification-resource "kotoba/lang/backend-provider-qualification-v1.edn")
(def registry-resource "kotoba/compiler/capability-registry.edn")
(def backend-keys #{:wasmtime :native :cljs})
(def qualification-keys #{:manifest-gate :execution-status :gaps :evidence})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :phase :backend-provider-qualification))))

(defn- read-resource [path]
  (when-let [resource (io/resource path)]
    (edn/read-string (slurp resource))))

(defn verify-data!
  "Verifies one backend claim against supplied decoded resources. A pending
  backend must name concrete gaps and carry no qualification evidence. A
  qualified backend must close all gaps and supply runtime boundary plus
  semantic-vector evidence."
  [manifest registry qualification backend]
  (when-not (= :v1 (:kotoba.provider-conformance/format manifest))
    (fail! "provider conformance manifest format is unsupported" {}))
  (when-not (= :v1 (:kotoba.backend-provider-qualification/format qualification))
    (fail! "backend qualification format is unsupported" {}))
  (when-not (= backend-keys (set (keys (:backends qualification))))
    (fail! "backend qualification inventory is not exact" {}))
  (let [manifest-hash (artifact/sha256 manifest)
        manifest-claim (:provider-manifest qualification)
        capabilities (->> (:kits manifest) (mapcat :capabilities) vec)
        claim (get-in qualification [:backends backend])]
    (when-not (and (= :kotoba.provider-conformance/v1 (:format manifest-claim))
                   (= manifest-hash (:sha256 manifest-claim))
                   (= (count capabilities) (:capability-count manifest-claim)))
      (fail! "backend gate is not bound to this provider manifest"
             {:backend backend :manifest-sha256 manifest-hash}))
    (doseq [{:keys [name id]} capabilities]
      (when-not (= id (get registry name))
        (fail! "provider manifest disagrees with capability registry"
               {:backend backend :name name :id id :registered (get registry name)})))
    (when-not (and claim (= qualification-keys (set (keys claim)))
                   (= :required (:manifest-gate claim)))
      (fail! "backend manifest gate claim is not exact" {:backend backend}))
    (case (:execution-status claim)
      :pending
      (when-not (and (seq (:gaps claim)) (empty? (:evidence claim)))
        (fail! "pending backend must name gaps and must not claim evidence"
               {:backend backend}))

      :qualified
      (when-not (and (empty? (:gaps claim))
                     (= #{:runtime-boundary :semantic-vectors}
                        (set (keys (:evidence claim))))
                     (every? string? (vals (:evidence claim))))
        (fail! "qualified backend lacks closed semantic evidence"
               {:backend backend}))

      (fail! "backend execution status is invalid" {:backend backend}))
    {:format :kotoba.backend-provider-qualification/receipt-v1
     :backend backend
     :provider-manifest-sha256 manifest-hash
     :capability-count (count capabilities)
     :manifest-gate :passed
     :execution-status (:execution-status claim)
     :gaps (:gaps claim)}))

(defn verify! [backend]
  (verify-data! (read-resource manifest-resource)
                (read-resource registry-resource)
                (read-resource qualification-resource)
                backend))

(defn -main [& [command backend-name]]
  (when-not (= "verify" command)
    (fail! "usage: verify <wasmtime|native|cljs>" {}))
  (let [backend (keyword backend-name)
        receipt (verify! backend)]
    (println (pr-str receipt))))
