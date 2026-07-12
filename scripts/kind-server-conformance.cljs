#!/usr/bin/env nbb
(ns kind-server-conformance
  (:require [scripts.lib :as lib]
            [clojure.string :as str]
            ["node:child_process" :as child]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def cluster "kotoba-wasi-ci")
(def node-image "kindest/node:v1.35.0@sha256:4613778f3cfcd10e615029370f5786704559103cf27bef934597ba562b269661")
(def service-image "kotoba-wasi-service:ci")
(defn sleep! [milliseconds]
  (js/Atomics.wait (js/Int32Array. (js/SharedArrayBuffer. 4)) 0 0 milliseconds))
(defn run!
  ([command args] (run! command args false))
  ([command args allow-failure?]
   (let [result (.spawnSync child command (clj->js args)
                            #js {:cwd lib/root :encoding "utf8" :maxBuffer 8388608})]
     (when (.-error result) (throw (.-error result)))
     (when (and (not allow-failure?) (not= 0 (or (.-status result) 70)))
       (throw (js/Error. (str "kind-server: command failed: " command "\n" (.-stderr result)))))
     result)))
(defn apply-json! [value]
  (let [result (.spawnSync child "kubectl" #js ["apply" "-f" "-"]
                           #js {:cwd lib/root :encoding "utf8" :maxBuffer 1048576
                                :input (js/JSON.stringify (clj->js value))})]
    (lib/ensure! (zero? (or (.-status result) 70))
                 (str "kind-server: kubectl apply failed: " (.-stderr result)))))
(defn curl! [method path body]
  (let [args (cond-> ["--silent" "--show-error" "--fail-with-body"
                      "--max-time" "3" "--request" method]
               body (into ["--header" "content-type: application/json" "--data" body])
               true (conj (str "http://127.0.0.1:18084" path)))]
    (run! "curl" args)))

(let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "kotoba-kind-server-"))
      context (.join path tmp "context")
      wasm (.join path context "program.wasm")
      nbb-cli (.join path lib/root "node_modules" "nbb" "cli.js")
      kotoba (.join path lib/root "bin" "kotoba")]
  (try
    (lib/ensure! (str/starts-with? (str/trim (.-stdout (run! "kind" ["version"]))) "kind v0.32.0")
                 "kind-server: pinned Kind 0.32.0 is required")
    (.mkdirSync fs context #js {:recursive true})
    (run! js/process.execPath [nbb-cli kotoba "-M" "compile"
                               (.join path lib/root "examples" "structured.kotoba")
                               "--target" "wasm32-wasi" "--output" wasm])
    (doseq [[source target]
            [[(.join path lib/root "runtime" "browser-host.mjs") (.join path context "browser-host.mjs")]
             [(.join path lib/root "runtime" "wasi-host.mjs") (.join path context "wasi-host.mjs")]
             [(.join path lib/root "runtime" "wasi-service.mjs") (.join path context "wasi-service.mjs")]
             [(.join path lib/root "runtime" "wasi-service-worker.mjs") (.join path context "wasi-service-worker.mjs")]
             [(.join path lib/root "deploy" "server" "Dockerfile") (.join path context "Dockerfile")]]]
      (.copyFileSync fs source target))
    (let [digest (lib/sha256 wasm)
          labels {:app "kotoba-wasi-service"}
          pod-spec {:automountServiceAccountToken false
                    :securityContext {:runAsNonRoot true :runAsUser 1000 :runAsGroup 1000
                                      :seccompProfile {:type "RuntimeDefault"}}
                    :containers [{:name "service" :image service-image :imagePullPolicy "Never"
                                  :env [{:name "KOTOBA_WASM_SHA256" :value digest}]
                                  :ports [{:name "http" :containerPort 8080}]
                                  :securityContext {:allowPrivilegeEscalation false
                                                    :readOnlyRootFilesystem true
                                                    :capabilities {:drop ["ALL"]}}
                                  :resources {:requests {:cpu "50m" :memory "32Mi"}
                                              :limits {:cpu "500m" :memory "128Mi"}}
                                  :readinessProbe {:httpGet {:path "/healthz" :port "http"}
                                                   :initialDelaySeconds 1 :periodSeconds 1}
                                  :livenessProbe {:httpGet {:path "/healthz" :port "http"}
                                                  :initialDelaySeconds 2 :periodSeconds 2}}]}
          deployment {:apiVersion "apps/v1" :kind "Deployment"
                      :metadata {:name "kotoba-wasi-service"}
                      :spec {:replicas 2 :revisionHistoryLimit 1
                             :strategy {:type "RollingUpdate"
                                        :rollingUpdate {:maxUnavailable 0 :maxSurge 1}}
                             :selector {:matchLabels labels}
                             :template {:metadata {:labels labels} :spec pod-spec}}}
          service {:apiVersion "v1" :kind "Service"
                   :metadata {:name "kotoba-wasi-service"}
                   :spec {:selector labels :ports [{:name "http" :port 8080 :targetPort "http"}]}}]
      (run! "docker" ["build" "--pull" "--platform" "linux/amd64"
                       "--tag" service-image context])
      (run! "kind" ["create" "cluster" "--name" cluster "--image" node-image "--wait" "120s"])
      (run! "kind" ["load" "docker-image" service-image "--name" cluster])
      (apply-json! deployment)
      (apply-json! service)
      (run! "kubectl" ["rollout" "status" "deployment/kotoba-wasi-service" "--timeout=120s"])
      (let [pods (js->clj (js/JSON.parse (.-stdout (run! "kubectl" ["get" "pods" "-l"
                                                                      "app=kotoba-wasi-service"
                                                                      "-o" "json"])))
                           :keywordize-keys true)]
        (lib/ensure! (= 2 (count (:items pods))) "kind-server: expected two service replicas")
        (doseq [pod (:items pods)]
          (lib/ensure! (every? :ready (get-in pod [:status :containerStatuses]))
                       "kind-server: pod is not ready")))
      (let [forward (.spawn child "kubectl" #js ["port-forward" "service/kotoba-wasi-service"
                                                  "18084:8080"]
                            #js {:cwd lib/root :stdio #js ["ignore" "pipe" "pipe"]})]
        (try
          (loop [attempt 0]
            (let [health (run! "curl" ["--silent" "--fail" "--max-time" "1"
                                        "http://127.0.0.1:18084/healthz"] true)]
              (when-not (zero? (or (.-status health) 70))
                (lib/ensure! (< attempt 50) "kind-server: port-forward did not become ready")
                (sleep! 100)
                (recur (inc attempt)))))
          (let [health (.-stdout (curl! "GET" "/healthz" nil))
                result (.-stdout (curl! "POST" "/v1/run"
                                        "{\"format\":\"kotoba.service-request/v1\",\"entry\":\"main\",\"args\":[]}"))]
            (lib/ensure! (and (.includes health digest) (.includes health "wasm32-wasi-kotoba-v1"))
                         "kind-server: health identity mismatch")
            (lib/ensure! (.includes result "\"result\":\"42\"")
                         "kind-server: execution result mismatch")
            (let [metrics (.-stdout (curl! "GET" "/metrics" nil))]
              (lib/ensure! (and (.includes metrics "kotoba_service_success_total ")
                                (.includes metrics "kotoba_service_active_workers 0")
                                (.includes metrics digest))
                           "kind-server: service metrics mismatch")))
          (let [pod-name (str/trim (.-stdout (run! "kubectl" ["get" "pods" "-l"
                                                               "app=kotoba-wasi-service"
                                                               "-o" "jsonpath={.items[-1:].metadata.name}"])))]
            (run! "kubectl" ["delete" "pod" pod-name "--wait=false"])
            (run! "kubectl" ["rollout" "status" "deployment/kotoba-wasi-service" "--timeout=120s"])
            (lib/ensure! (.includes (.-stdout (curl! "POST" "/v1/run"
                                                     "{\"format\":\"kotoba.service-request/v1\",\"entry\":\"main\",\"args\":[]}"))
                                    "\"result\":\"42\"")
                         "kind-server: service failed after pod replacement"))
          (finally (.kill forward "SIGTERM"))))
      (println "kind-server: hardened deployment, two replicas, execution, and pod replacement passed"))
    (finally
      (run! "kind" ["delete" "cluster" "--name" cluster] true)
      (.rmSync fs tmp #js {:recursive true :force true}))))
