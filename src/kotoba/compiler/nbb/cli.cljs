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
            [kotoba.compiler.nbb.io :as io]
            [kotoba.compiler.source-path :as source-path]))

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

(defn- kotoba-source! [path] (source-path/admit! path))

;; Mirrors `kotoba.compiler.bounded-edn`'s `max-depth`/`max-token-chars`/
;; `max-nodes`/`max-string-chars` exactly -- applied ONLY to `--policy`
;; (via read-edn-form! below), the one untrusted-ish EDN side-channel this
;; fast path reads, same scoping the JVM path's `bounded-edn/read-file`
;; has (`.kotoba` SOURCE itself goes through `kr/read-forms` with no depth
;; limit either, matching `clojure.tools.reader`'s own unbounded treatment
;; of source in `kotoba.compiler.frontend`).
(def ^:private max-policy-depth 128)
(def ^:private max-policy-token-chars 4096)
(def ^:private max-policy-nodes 200000)
(def ^:private max-policy-string-chars (* 1024 1024))

(defn- validate-edn-shape!
  "Mirrors `kotoba.compiler.bounded-edn/validate-shape!`'s post-parse walk:
  bounds total node count and individual string length. Bracket nesting
  depth AND raw token length (including NUMBER tokens -- a huge digit run
  becomes a `js/BigInt`, which has no post-parse 'name' to measure) are
  ALREADY bounded during parsing itself (see
  `kotoba.compiler.kotoba-reader/read-token`/`read-delimited`'s docstrings
  for why those two specifically can't wait until after the full value is
  built, or be checked only on keyword/symbol names).

  Note: the string-length check here is real defense-in-depth (matches
  bounded-edn's own belt-and-suspenders structure) but is currently
  UNREACHABLE via this CLI's actual `--policy` path: `nbb.io/read-text-file`
  enforces its own 1MiB overall file-byte cap first, numerically identical
  to `max-policy-string-chars`, so any policy file with a string anywhere
  near that length already fails the coarser file-size check before this
  function ever runs (see `scripts/conformance.cljs`'s own comment on this,
  where it was confirmed live). Kept anyway for structural parity with
  bounded-edn and in case a future caller feeds this function a value from
  somewhere other than the byte-capped `--policy` file path."
  [value]
  (let [nodes (volatile! 0)]
    (letfn [(walk [x]
              (when (> (vswap! nodes inc) max-policy-nodes)
                (throw (ex-info "EDN value contains too many nodes"
                                {:phase :decode :limit max-policy-nodes})))
              (when (and (string? x) (> (count x) max-policy-string-chars))
                (throw (ex-info "EDN string exceeds limit"
                                {:phase :decode :limit max-policy-string-chars})))
              (cond
                (map? x) (doseq [[k v] x] (walk k) (walk v))
                (coll? x) (doseq [item x] (walk item))))]
      (walk value)
      value)))

(defn- read-edn-form!
  "Reads exactly one top-level EDN form from TEXT, same contract
  `kotoba.compiler.bounded-edn/read-string` enforces on the JVM path (empty
  input and trailing forms are both rejected, `:phase :decode` so
  `exit-code` maps it to 65 same as that path) -- `--policy` files go
  through this, not a bare `(first (kr/read-forms ...))` that would
  silently ignore everything after the first form. Also enforces the same
  depth/node/token/string bounds `bounded-edn/read-string` does for
  exactly this reason: `--policy` is the untrusted-ish EDN input those
  limits exist to harden against structural resource attacks (previously
  UNENFORCED here -- only the byte-size limit in `nbb.io/read-text-file`
  and the trailing-form check above ran, confirmed live: a `--policy` file
  with 500+ levels of `[` nesting, or a single 4097-digit number literal,
  both parsed here without error, whereas the JVM path's
  `bounded-edn/read-file` rejects both)."
  [text]
  (let [forms (kr/read-forms text {:max-depth max-policy-depth
                                   :max-token-chars max-policy-token-chars})]
    (when (empty? forms)
      (throw (ex-info "EDN input is empty" {:phase :decode})))
    (when (> (count forms) 1)
      (throw (ex-info "EDN input contains trailing forms" {:phase :decode})))
    (validate-edn-shape! (first forms))))

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
