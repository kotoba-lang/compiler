#!/usr/bin/env nbb
(ns browser-fixture-server
  (:require ["node:fs" :as fs]
            ["node:http" :as http]
            ["node:path" :as path]
            ["node:url" :refer [URL]]))

(def root (.resolve path (.dirname path *file*) ".."))
(def artifact-dir (or js/process.env.KOTOBA_BROWSER_ARTIFACTS
                      (throw (js/Error. "KOTOBA_BROWSER_ARTIFACTS is required"))))
(def port (js/Number (or js/process.env.KOTOBA_BROWSER_PORT "4173")))
(def csp "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
(def blocked-csp "default-src 'none'; script-src 'self'; worker-src 'none'; connect-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")

(def routes
  {"/" [(path/join root "tests/browser/index.html") "text/html; charset=utf-8"]
   "/index.html" [(path/join root "tests/browser/index.html") "text/html; charset=utf-8"]
   "/tests/browser/fixture.mjs" [(path/join root "tests/browser/fixture.mjs") "text/javascript; charset=utf-8"]
   "/tests/browser/worker-capability.mjs" [(path/join root "tests/browser/worker-capability.mjs") "text/javascript; charset=utf-8"]
   "/tests/browser/csp-blocked.html" [(path/join root "tests/browser/csp-blocked.html") "text/html; charset=utf-8"]
   "/tests/browser/csp-blocked.mjs" [(path/join root "tests/browser/csp-blocked.mjs") "text/javascript; charset=utf-8"]
   "/runtime/browser-host.mjs" [(path/join root "runtime/browser-host.mjs") "text/javascript; charset=utf-8"]
   "/runtime/worker-host.mjs" [(path/join root "runtime/worker-host.mjs") "text/javascript; charset=utf-8"]
   "/runtime/worker-entry.mjs" [(path/join root "runtime/worker-entry.mjs") "text/javascript; charset=utf-8"]
   "/artifacts/program.wasm" [(path/join artifact-dir "program.wasm") "application/wasm"]
   "/artifacts/capability.wasm" [(path/join artifact-dir "capability.wasm") "application/wasm"]
   "/artifacts/heap.wasm" [(path/join artifact-dir "heap.wasm") "application/wasm"]})

(defn respond! [response status headers body]
  (.writeHead response status (clj->js headers))
  (.end response body))

(def server
  (.createServer http
    (fn [request response]
      (let [pathname (.-pathname (URL. (.-url request) (str "http://127.0.0.1:" port)))]
        (cond
          (not= "GET" (.-method request))
          (respond! response 405 {"Content-Type" "text/plain"} "method not allowed")

          (= pathname "/health")
          (respond! response 200 {"Content-Type" "text/plain" "Cache-Control" "no-store"} "ok")

          (contains? routes pathname)
          (let [[file content-type] (get routes pathname)
                policy (if (= pathname "/tests/browser/csp-blocked.html") blocked-csp csp)]
            (respond! response 200
                      {"Content-Type" content-type
                       "Content-Security-Policy" policy
                       "Cross-Origin-Resource-Policy" "same-origin"
                       "Referrer-Policy" "no-referrer"
                       "X-Content-Type-Options" "nosniff"
                       "Cache-Control" "no-store"}
                      (.readFileSync fs file)))

          :else (respond! response 404 {"Content-Type" "text/plain"} "not found"))))))

(.listen server port "127.0.0.1"
         #(println (str "browser-fixture: listening on http://127.0.0.1:" port)))
