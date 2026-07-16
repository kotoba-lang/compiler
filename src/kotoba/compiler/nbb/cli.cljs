(ns kotoba.compiler.nbb.cli
  "The nbb-native `compile`/`check` fast path for `wasm32*` targets --
  spawned by `bin/kotoba` (see its own comment) with `--classpath src`
  already set, so these `:require`s resolve normally. Every other target
  and every other subcommand (`package-ios`, `sign`, `run`, fleet/native
  packaging, etc.) is NOT covered here and stays on the JVM `clojure -M:run`
  path (`kotoba.compiler.cli`) -- see the compiler README's runtime-priority
  note for what that split is and isn't claiming."
  (:require [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.admission :as admission]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.kotoba-reader :as kr]
            [kotoba.compiler.nbb.io :as io]))

;; Mirrors `kotoba.compiler.cli`'s `parse-target`, restricted to the three
;; wasm32 target names -- the only ones this fast path claims. Every other
;; name in that JVM function's table falls through to the JVM path in
;; `bin/kotoba` before this script is ever spawned.
(def targets
  {"wasm32" :wasm32-kotoba-v1
   "wasm32-browser" :wasm32-browser-kotoba-v1
   "wasm32-wasi" :wasm32-wasi-kotoba-v1})

(defn- option [args flag] (second (drop-while #(not= flag %) args)))

(defn- fail! [message code]
  (.error js/console message)
  (.exit js/process code))

(defn- kotoba-source! [path]
  (when-not (and (string? path) (.endsWith path ".kotoba"))
    (fail! "error: input must be a .kotoba file" 2))
  path)

(defn- read-policy [args]
  (if-let [p (option args "--policy")]
    (first (kr/read-forms (io/read-text-file p)))
    {}))

(defn- run! [args]
  (let [cmd (first args)]
    (case cmd
      "check"
      (let [input (kotoba-source! (second args))
            src (io/read-text-file input)
            hir (frontend/analyze src)
            policy (read-policy args)
            adm (admission/check hir policy)]
        (println (pr-str {:ok true :effects (:effects hir) :admission adm})))

      "compile"
      (let [input (kotoba-source! (second args))
            target-name (or (option args "--target") "wasm32")
            target (get targets target-name)
            _ (when-not target
                (fail! (str "error: nbb-native fast path does not cover target " target-name
                            " -- use `clojure -M:run compile ...` directly") 2))
            output (or (option args "--output") (str input ".wasm"))
            src (io/read-text-file input)
            hir (frontend/analyze src)
            policy (read-policy args)
            _ (admission/check hir policy)
            kir (ir/lower hir)
            bytes (wasm/emit kir target)]
        (io/write-bytes! output bytes)
        (println (pr-str {:ok true :target target :output output})))

      (fail! (str "error: nbb-native fast path does not cover command " cmd) 2))))

(try
  (run! (vec *command-line-args*))
  (catch :default e
    (let [data (ex-data e)]
      (println (pr-str {:format :kotoba.cli-error/v1 :ok false
                        :error (or (:phase data) :internal)
                        :message (.-message e)
                        :details data}))
      (.exit js/process 1))))
