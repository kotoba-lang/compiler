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
  (:require ["node:path" :as node-path]
            [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.admission :as admission]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.backend.aarch64 :as aarch64]
            [kotoba.compiler.backend.x86-64 :as x86-64]
            [kotoba.compiler.compatibility :as compatibility]
            [kotoba.compiler.diagnostic :as diagnostic]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.kotoba-reader :as kr]
            [kotoba.compiler.nbb.io :as io]
            [kotoba.compiler.provenance :as provenance]
            [kotoba.compiler.source-path :as source-path]
            [kotoba.compiler.target :as target-profile]
            [kotoba.compiler.verifier :as verifier]))

;; Mirrors `kotoba.compiler.cli`'s `parse-target`, restricted to the wasm32
;; and ORDINARY (non-aiueos) native target names -- everything this fast
;; path claims. The `x86_64-aiueos-*`/`aarch64-aiueos-*` firmware/kernel
;; sub-targets (ELF64/PE32+ packaging via `kotoba.compiler.packaging.*`,
;; only reachable through `clojure -M:run compile` directly, not this
;; usage-documented list) stay JVM-only -- out of scope here, same
;; boundary `kotoba.compiler.core/compile-source*`'s own `cond->` draws
;; between the sealed `:kexe/v1` artifact (every ordinary native target)
;; and the extra `:binary`/`:object` packaging (aiueos targets only).
;; Every other name in the JVM `parse-target` table falls through to the
;; JVM path in `bin/kotoba` before this script is ever spawned.
(def targets
  {"wasm32" :wasm32-kotoba-v1
   "wasm32-browser" :wasm32-browser-kotoba-v1
   "wasm32-wasi" :wasm32-wasi-kotoba-v1
   "x86_64" :x86_64-kotoba-v1
   "x86_64-linux" :x86_64-linux-kotoba-v1
   "x86_64-macos" :x86_64-macos-kotoba-v1
   "x86_64-windows" :x86_64-windows-kotoba-v1
   "aarch64" :aarch64-kotoba-v1
   "aarch64-linux" :aarch64-linux-kotoba-v1
   "aarch64-macos" :aarch64-macos-kotoba-v1
   "aarch64-windows" :aarch64-windows-kotoba-v1
   "aarch64-android" :aarch64-android-kotoba-v1
   "aarch64-ios" :aarch64-ios-kotoba-v1})

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

;; Mirrors `kotoba.compiler.core/compile-source*`'s `:else` (native) branch
;; byte-for-byte -- same sealed `:kotoba.kexe/v1` shape, same
;; `fuel-abi`/`context-abi`/`limits` constants, same pre-checks -- so a
;; `.kexe` produced here and one produced by `clojure -M:run compile` for
;; the identical source/target/policy verify identically against either
;; artifact's own `:sha256` seal and against `verifier/verify-artifact!`.
;; Deliberately does NOT replicate the `x86_64-aiueos-*`/`aarch64-aiueos-*`
;; `:binary`/`:object` packaging step (see `targets`' own comment above for
;; why that stays out of scope).
(defn- compile-native! [hir target backend policy]
  (when (and (= :kotoba.hir/v3 (:format hir))
             (not (and (contains? #{:x86_64-kotoba-v1 :aarch64-kotoba-v1} backend)
                       (ir/only-string-and-scalar-record-typed-features? hir))))
    (throw (ex-info "typed values currently require the kotoba-script web target, typed Wasm target, or qualified native string/scalar-record/option-i64/result-i64 features"
                    {:phase :target :target target :backend backend
                     :value-profile :kotoba.value/typed-v1})))
  (when (nil? (:entry hir))
    (throw (ex-info "entryless libraries currently require the kotoba-script web target"
                    {:phase :target :target target :backend backend})))
  (let [admission (admission/check hir policy)
        kir (ir/lower hir)
        value (:oracle-value kir)
        profile (target-profile/profile target)
        typed-values? (= :kotoba.kir/v4 (:format kir))
        value-abi (if typed-values? :kotoba.typed/externref-v1 :kotoba.i64/direct-v1)
        compat (compatibility/descriptor
                {:hir-format (:format hir) :kir-format (:format kir)
                 :target target :target-profile profile :value-abi value-abi})
        emitted ((case backend
                   :x86_64-kotoba-v1 x86-64/emit-program
                   :aarch64-kotoba-v1 aarch64/emit-program) kir)
        code (:code emitted)
        program (select-keys kir [:format :entry :exports :signature :effects :functions])
        artifact-map
        (artifact/seal
         {:format :kotoba.kexe/v1 :target target :target-profile profile :value value
          :kir-sha256 (artifact/sha256 program)
          :lowering (case backend
                      :x86_64-kotoba-v1 :runtime-sysv-v1
                      :aarch64-kotoba-v1 :runtime-aapcs64-v1)
          :fuel-abi (case backend
                      :x86_64-kotoba-v1 {:mode :hidden-context-r9 :initial 512}
                      :aarch64-kotoba-v1 {:mode :hidden-context-x7 :initial 512})
          :context-abi {:version 2 :fuel-offset 8 :allow-bitmap-offset 16
                        :allow-bitmap-bytes 32 :cap-call-offset 48
                        :pair-new-offset 56 :pair-first-offset 64
                        :pair-second-offset 72 :pair-capacity 4096
                        :kgraph-assert-offset 80 :kgraph-get-offset 88
                        :kgraph-count-offset 96 :kgraph-entity-at-offset 104
                        :kgraph-capacity 4096
                        :string-equal-offset 112 :string-concat-offset 120
                        :typed-cap-call-offset 128
                        :string-pool-capacity 65536}
          :effects (:effects hir)
          :compatibility compat
          :limits {:memory-bytes 65536 :fuel 512 :stack-bytes 4096}
          :code (mapv #(bit-and (int %) 0xff) code)
          :program program :exports (:exports emitted)})]
    (verifier/verify-artifact! artifact-map)
    {:format :kexe/v1 :target target :hir hir :kir kir
     :admission admission :artifact artifact-map :compatibility compat}))

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
            backend (target-profile/backend target)
            output (or (option args "--output")
                       (str input (if (= backend :wasm32-kotoba-v1) ".wasm" ".kexe")))
            src (io/read-text-file input)
            hir (frontend/analyze src)
            policy (read-policy args)]
        (if (= backend :wasm32-kotoba-v1)
          (let [_ (admission/check hir policy)
                kir (ir/lower hir)
                bytes (wasm/emit kir target)]
            (io/write-bytes! output bytes)
            (println (pr-str {:ok true :target target :output output})))
          (let [result (compile-native! hir target backend policy)
                provenance-result (provenance/attach src policy {} result)
                provenance-output (str output ".provenance.edn")]
            ;; `artifact/edn-safe` -- see its own doc comment -- turns any
            ;; `bigint` KIR literal in the artifact/provenance tree back
            ;; into plain digit text before `pr-str`, matching the JVM
            ;; path's output byte-for-byte instead of `#object[BigInt N]`.
            (io/write-text! output (pr-str (artifact/edn-safe (:artifact provenance-result))))
            (io/write-text! provenance-output (pr-str (artifact/edn-safe (:provenance provenance-result))))
            (println (pr-str {:ok true :target target :output output
                              :provenance-output provenance-output})))))

      (usage-error! (str "error: nbb-native fast path does not cover command " cmd)))))

;; Mirrors `kotoba.compiler.cli/exit-code` -- only the phases this fast
;; path's own namespaces (`frontend`/`admission`/`ir`/`backend.wasm`/
;; `backend.x86-64`/`backend.aarch64`/`verifier`/`nbb.io`) actually throw
;; need a case here (:usage, :decode, :read, :subset, :admission, :verify);
;; :target (the native pre-checks in `compile-native!`) is NOT in this set,
;; matching the JVM table exactly -- it falls through to the same default
;; 70 there too. Every other phase in the JVM table belongs to
;; commands/backends this fast path doesn't implement.
(defn- exit-code [phase]
  (case phase
    :usage 64
    (:decode :read :subset :admission :verify) 65
    :output 74
    70))

(defn- error-report [e source-name]
  (let [data (ex-data e)
        phase (or (:phase data) :internal)]
    {:format :kotoba.cli-error/v1
     :ok false
     :error phase
     :diagnostic (diagnostic/from-error e source-name)
     :message (if (= phase :internal) "internal compiler error" (.-message e))}))

(try
  (run! (vec *command-line-args*))
  (catch :default e
    (let [source (second *command-line-args*)
          source-name (when (source-path/source-kind source) (.basename node-path source))
          report (error-report e source-name)]
      (.error js/console (pr-str report))
      (.exit js/process (exit-code (:error report))))))
