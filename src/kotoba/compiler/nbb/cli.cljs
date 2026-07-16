(ns kotoba.compiler.nbb.cli
  "The nbb-native `compile`/`check` fast path for `wasm32*` targets --
  spawned by `bin/kotoba` (see its own comment) with `--classpath src`
  already set, so these `:require`s resolve normally. Every other target
  and every other subcommand (`package-ios`, `sign`, `run`, fleet/native
  packaging, etc.) is NOT covered here and stays on the JVM `clojure -M:run`
  path (`kotoba.compiler.cli`) -- see the compiler README's runtime-priority
  note for what that split is and isn't claiming.

  Error reporting mirrors `kotoba.compiler.cli`'s contract exactly (a
  single `pr-str` line on STDERR, `{:format :kotoba.cli-error/v1 :ok false
  :error <phase> :message ...}`, `exit-code`'s phase->status mapping) --
  `scripts/conformance.cljs` exercises this same contract against whichever
  binary `bin/kotoba` dispatches to, and does not distinguish 'JVM path' vs
  'nbb fast path'. Confirmed live: an earlier version of this file threw
  away the phase/message/exit-code entirely (always printed to stdout,
  always exited 1), and separately never validated `--policy` for
  malformed/trailing EDN at all (silently used only the FIRST top-level
  form) -- both are fixed here, not just the one conformance check that
  happened to catch it."
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

(defn- usage-error! [message]
  (throw (ex-info message {:phase :usage})))

(defn- kotoba-source! [path]
  (when-not (and (string? path) (.endsWith path ".kotoba"))
    (usage-error! "error: input must be a .kotoba file"))
  path)

(defn- read-edn-form!
  "Reads exactly one top-level EDN form from TEXT, same contract
  `kotoba.compiler.bounded-edn/read-string` enforces on the JVM path (empty
  input and trailing forms are both rejected, `:phase :decode` so
  `exit-code` maps it to 65 same as that path) -- `--policy` files go
  through this, not a bare `(first (kr/read-forms ...))` that would
  silently ignore everything after the first form."
  [text]
  (let [forms (kr/read-forms text)]
    (when (empty? forms)
      (throw (ex-info "EDN input is empty" {:phase :decode})))
    (when (> (count forms) 1)
      (throw (ex-info "EDN input contains trailing forms" {:phase :decode})))
    (first forms)))

(defn- read-policy [args]
  (if-let [p (option args "--policy")]
    (read-edn-form! (io/read-text-file p))
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
                (usage-error! (str "error: nbb-native fast path does not cover target " target-name
                                   " -- use `clojure -M:run compile ...` directly")))
            output (or (option args "--output") (str input ".wasm"))
            src (io/read-text-file input)
            hir (frontend/analyze src)
            policy (read-policy args)
            _ (admission/check hir policy)
            kir (ir/lower hir)
            bytes (wasm/emit kir target)]
        (io/write-bytes! output bytes)
        (println (pr-str {:ok true :target target :output output})))

      (usage-error! (str "error: nbb-native fast path does not cover command " cmd)))))

;; Mirrors `kotoba.compiler.cli/exit-code` -- only the phases this fast
;; path's own namespaces (`frontend`/`admission`/`ir`/`backend.wasm`/
;; `nbb.io`) actually throw need a case here (:usage, :decode, :read,
;; :subset, :admission); every other phase in the JVM table belongs to
;; commands/backends this fast path doesn't implement.
(defn- exit-code [phase]
  (case phase
    :usage 64
    (:decode :read :subset :admission) 65
    :output 74
    70))

(defn- error-report [e]
  (let [data (ex-data e)
        phase (or (:phase data) :internal)]
    {:format :kotoba.cli-error/v1
     :ok false
     :error phase
     :message (if (= phase :internal) "internal compiler error" (.-message e))}))

(try
  (run! (vec *command-line-args*))
  (catch :default e
    (let [report (error-report e)]
      (.error js/console (pr-str report))
      (.exit js/process (exit-code (:error report))))))
