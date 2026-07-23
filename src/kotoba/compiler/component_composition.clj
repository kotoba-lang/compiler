(ns kotoba.compiler.component-composition
  "Closed-world composition support for compiler-qualified Component artifacts."
  (:require [clojure.string :as str]
            [kotoba.compiler.canonical-abi :as canonical]
            [kotoba.compiler.component-core :as component-core]
            [kotoba.compiler.component-wit :as component-wit]
            [kotoba.compiler.wasm-tools :as wasm-tools])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- reject [message data]
  (throw (ex-info message (assoc data :phase :component-composition))))

(def wac-version
  "ADR 0056 bumps this from 0.9.0 (ADR 0047-0055's pin) to 0.10.1: 0.9.0
  fails encoding any capability-crossing variant whose case wraps a record
  with `type not valid to be used as import` (ADR 0055's own reproduced
  finding); 0.10.0 fixes exactly that failure mode
  (bytecodealliance/wac#205, 'Alias `use`'d types during composition
  instead of re-encoding them locally' -- the type-aliasing fix a `use`'d
  type crossing an import/export boundary needs), independently confirmed
  here against ADR 0055's own reproduction before this pin moved; 0.10.1 is
  the latest patch release on top of it (bytecodealliance/wac#207, only a
  release-process fix, no further behavior change relevant here). Narrow,
  deliberate bump made only because it directly and verifiably closes a
  reproduced defect this codebase hit -- not a routine or blind toolchain
  refresh."
  "0.10.1")

(defn- assert-wac-version! []
  (let [actual (.trim ^String (wasm-tools/run-command! ["wac" "--version"]))]
    (when-not (= (str "wac-cli " wac-version) actual)
      (reject "wac version is not pinned"
              {:expected wac-version :actual actual}))))

(defn- scalar-wasm-type [descriptor]
  (or ({:i64 "i64" :f32 "f32" :f64 "f64"} descriptor)
      (reject "provider identity requires a Canonical scalar" {:descriptor descriptor})))

(defn- capability [name]
  (or (some #(when (= name (:name %)) %) (:capabilities component-wit/contract))
      (reject "provider capability is not present in the pinned contract" {:capability name})))

(defn- wit-name [value]
  (-> (if (keyword? value) (subs (str value) 1) (str value))
      str/lower-case
      (str/replace #"[^a-z0-9-]+" "-")
      (str/replace #"-+" "-")
      (str/replace #"(^-|-$)" "")))

(defn- provider-wit [entry descriptor]
  (let [type-name (or ({:i64 "s64" :f32 "f32" :f64 "f64"} descriptor)
                      (reject "provider identity requires a Canonical scalar"
                              {:descriptor descriptor}))
        interface (:interface entry)
        function (:function entry)]
    (str "package kotoba:application@1.0.0;\n\n"
         "interface " interface " {\n"
         "  " function ": func(request: " type-name ") -> " type-name ";\n"
         "}\n\n"
         "world " interface "-provider {\n"
         "  export " interface ";\n"
         "}\n")))

(defn- provider-wat [entry descriptor]
  (let [wasm-type (scalar-wasm-type descriptor)
        export (str "cm32p2|kotoba:application/" (:interface entry) "@1|" (:function entry))]
    (str "(module\n"
         "  (memory (export \"cm32p2_memory\") 1 1)\n"
         "  (func (export \"" export "\") (param $request " wasm-type ") (result " wasm-type ")\n"
         "    local.get $request)\n"
         "  (func (export \"" export "_post\") (param " wasm-type "))\n"
         "  (func (export \"cm32p2_realloc\") (param i32 i32 i32 i32) (result i32) i32.const 0)\n"
         "  (func (export \"cm32p2_initialize\")))\n")))

(defn package-scalar-identity-provider
  "Build a validation-only provider that preserves one scalar value. This
  proves interface wiring; it is not semantic evidence for a production kit."
  [capability-name descriptor]
  (let [entry (capability capability-name)
        wit (provider-wit entry descriptor)
        dir (Files/createTempDirectory "kotoba-provider-" (make-array FileAttribute 0))
        world (.resolve dir "provider.wit")
        core (.resolve dir "provider.wasm")
        embedded (.resolve dir "embedded.wasm")
        component (.resolve dir "provider.component.wasm")]
    (try
      (Files/writeString world wit (make-array java.nio.file.OpenOption 0))
      (Files/write core (wasm-tools/parse-wat (provider-wat entry descriptor))
                   (make-array java.nio.file.OpenOption 0))
      (wasm-tools/run-command! ["wasm-tools" "component" "embed" (str world) (str core)
                                "--encoding" "utf8" "-o" (str embedded)])
      (wasm-tools/run-command! ["wasm-tools" "component" "new" (str embedded)
                                "--reject-legacy-names" "-o" (str component)])
      {:format :wasm-component-provider/v1
       :capability capability-name
       :descriptor descriptor
       :bytes (Files/readAllBytes component)}
      (finally
        (doseq [path [component embedded core world]] (Files/deleteIfExists path))
        (Files/deleteIfExists dir)))))

(defn- record-wit [entry descriptor schemas]
  (let [schema (get schemas (second descriptor))
        [_ identity fields] schema
        wit-type {:i64 "s64" :f32 "f32" :f64 "f64" :bool "bool"}
        record-name (wit-name identity)
        interface (:interface entry)]
    (when-not (and (= :ref (first descriptor))
                   (= :record (first schema))
                   (= identity (second descriptor))
                   (seq fields)
                   (every? (comp wit-type second) fields))
      (reject "provider record requires one sealed scalar schema"
              {:descriptor descriptor :schema schema}))
    (str "package kotoba:application@1.0.0;\n\n"
         "interface types {\n"
         "  record " record-name " {\n"
         (apply str (map (fn [[field type]]
                           (str "    " (wit-name field) ": " (wit-type type) ",\n")) fields))
         "  }\n}\n\n"
         "interface " interface " {\n"
         "  use types.{" record-name "};\n"
         "  " (:function entry) ": func(request: " record-name ") -> " record-name ";\n"
         "}\n\n"
         "world " interface "-provider {\n  export " interface ";\n}\n")))

(defn- record-provider-wat [entry descriptor schemas]
  (let [layout (canonical/layout descriptor schemas)
        wasm-type {:i64 "i64" :f32 "f32" :f64 "f64" :bool "i32"}
        wasm-store {:i64 "i64.store" :f32 "f32.store" :f64 "f64.store" :bool "i32.store8"}
        fields (:fields layout)
        export (str "cm32p2|kotoba:application/" (:interface entry) "@1|" (:function entry))
        params (apply str (map-indexed
                           (fn [index field]
                             (str " (param $f" index " "
                                  (wasm-type (get-in field [:layout :descriptor])) ")")) fields))
        stores (apply str (map-indexed
                           (fn [index field]
                             (let [type (get-in field [:layout :descriptor])]
                               (str "    local.get $ret local.get $f" index " "
                                    (wasm-store type) " offset=" (:offset field) "\n"))) fields))]
    (str "(module\n"
         "  (memory (export \"cm32p2_memory\") 1 1)\n"
         "  (func (export \"" export "\")" params " (result i32)\n"
         "    (local $ret i32) i32.const 8 local.set $ret\n" stores "    local.get $ret)\n"
         "  (func (export \"" export "_post\") (param i32))\n"
         "  (func (export \"cm32p2_realloc\") (param i32 i32 i32 i32) (result i32) i32.const 8)\n"
         "  (func (export \"cm32p2_initialize\")))\n")))

(defn package-record-identity-provider
  "Build a wiring-only provider for one sealed scalar record identity."
  [capability-name descriptor schemas]
  (let [entry (capability capability-name)
        wit (record-wit entry descriptor schemas)
        dir (Files/createTempDirectory "kotoba-record-provider-" (make-array FileAttribute 0))
        world (.resolve dir "provider.wit") core (.resolve dir "provider.wasm")
        embedded (.resolve dir "embedded.wasm") component (.resolve dir "provider.component.wasm")]
    (try
      (Files/writeString world wit (make-array java.nio.file.OpenOption 0))
      (Files/write core (wasm-tools/parse-wat (record-provider-wat entry descriptor schemas))
                   (make-array java.nio.file.OpenOption 0))
      (wasm-tools/run-command! ["wasm-tools" "component" "embed" (str world) (str core)
                                "--encoding" "utf8" "-o" (str embedded)])
      (wasm-tools/run-command! ["wasm-tools" "component" "new" (str embedded)
                                "--reject-legacy-names" "-o" (str component)])
      {:format :wasm-component-provider/v1 :capability capability-name
       :descriptor descriptor :schemas schemas :bytes (Files/readAllBytes component)}
      (finally
        (doseq [path [component embedded core world]] (Files/deleteIfExists path))
        (Files/deleteIfExists dir)))))

(def ^:private variant-case-wit-type
  {:i64 "s64" :f32 "f32" :f64 "f64" :bool "bool"})

(def ^:private record-field-wit-type
  "WIT spelling for one record *field* type admitted inside a variant
  provider's referenced record -- `variant-case-wit-type` widened by
  `:string`/`:keyword`, both spelled WIT `string` (ADR 0057, mirroring
  `kotoba.compiler.component-wit/type-text`'s own long-standing `:string`/
  `:keyword` -> `string` mapping and `kotoba.compiler.component-core/
  string-field-record-schema`'s field-type set). Kept distinct from
  `variant-case-wit-type` because a *bare* case payload (the case itself,
  not a field inside a record case) still admits only a Canonical scalar in
  this slice -- no case kind's own payload type is directly `:string`/
  `:keyword`, only a record field nested inside a record case may be."
  (assoc variant-case-wit-type :string "string" :keyword "string"))

(defn- variant-record-case-schema
  "Schema of `payload-type` when it is `[:ref name]` to a sealed record whose
  fields are each a bare Canonical scalar (the ADR 0052 shape) or a bounded
  `string`/`keyword` leaf (the ADR 0053 shape, admitted as a capability-call
  variant case's record payload for the first time in ADR 0057) -- the
  provider-side admission twin of
  `kotoba.compiler.component-core/string-field-record-schema` (private
  there; duplicated here narrowly rather than reaching across the namespace
  boundary, matching this file's own established precedent --
  `record-provider-wat` already duplicates `record-capability-wat`'s store
  shape locally for the same reason)."
  [payload-type schemas]
  (when (and (vector? payload-type) (= :ref (first payload-type)))
    (let [schema (get schemas (second payload-type))]
      (when (and (vector? schema) (= :record (first schema))
                 (= (second payload-type) (second schema))
                 (seq (nth schema 2))
                 (every? (comp record-field-wit-type second) (nth schema 2)))
        schema))))

(defn- variant-referenced-record-schemas
  "Every distinct record schema `cases` reference, in stable sorted order (so
  the generated WIT text is deterministic)."
  [cases schemas]
  (->> cases
       (keep (fn [[_ payload-type]] (variant-record-case-schema payload-type schemas)))
       distinct
       (sort-by (comp str second))))

(defn- variant-case-payload-wit
  "WIT type text for one variant case's payload: a bare scalar's WIT spelling,
  or a sealed all-scalar or string/keyword-bearing record case's own WIT
  type name."
  [payload-type schemas]
  (or (get variant-case-wit-type payload-type)
      (when-let [schema (variant-record-case-schema payload-type schemas)]
        (wit-name (second schema)))
      (reject "provider variant case is not scalar or a sealed admitted record"
              {:payload-type payload-type})))

(defn- variant-wit
  "Deterministic WIT package/world text for one sealed variant provider whose
  every case's payload is a bare Canonical scalar (ADR 0055), a sealed
  all-scalar record (ADR 0056, the ADR 0052 record shape), or -- new in ADR
  0057 -- a sealed flat string/keyword-bearing record (the ADR 0053 shape)
  -- the provider-side counterpart to `record-wit`. ADR 0055 deliberately
  did not admit a case wrapping a record (unlike the identity-export
  variant path, ADR 0052/0054): a record-referencing-variant provider built
  this same way was tried against `wac plug` (pinned 0.9.0 at the time) and
  failed encoding with `type not valid to be used as import` for every
  shape tried, independent of case count, case mix, and `types`-interface
  declaration order. ADR 0056 confirmed `wac` 0.10.0
  (bytecodealliance/wac#205) fixes exactly that failure mode and widened
  this function to declare the referenced record type(s) inside the same
  `interface types {...}` block as the variant, mirroring `record-wit`'s
  own record-declaration style, rather than the single-type-only footprint
  ADR 0055 scoped down to. ADR 0056 still left a case wrapping a sealed
  *string/keyword-bearing* record (ADR 0053's shape) unadmitted, recording
  it as a separate, still-unattempted gap: string/keyword data crossing a
  capability-call boundary at all, independent of the `wac plug` defect ADR
  0056 fixed. ADR 0057 closes exactly that gap for a record *case*'s own
  field (not a bare case payload -- see `record-field-wit-type`'s
  docstring)."
  [entry descriptor schemas]
  (let [schema (get schemas (second descriptor))
        [_ identity cases] schema
        variant-name (wit-name identity)
        interface (:interface entry)
        record-schemas (variant-referenced-record-schemas cases schemas)]
    (when-not (and (= :ref (first descriptor))
                   (= :variant (first schema))
                   (= identity (second descriptor))
                   (seq cases)
                   (every? (fn [[_ payload-type]]
                             (or (contains? variant-case-wit-type payload-type)
                                 (variant-record-case-schema payload-type schemas)))
                           cases))
      (reject "provider variant requires scalar, sealed all-scalar record, or sealed string/keyword-bearing record cases"
              {:descriptor descriptor :schema schema}))
    (str "package kotoba:application@1.0.0;\n\n"
         "interface types {\n"
         (apply str
                (map (fn [[_ record-identity fields]]
                       (str "  record " (wit-name record-identity) " {\n"
                            (apply str
                                   (map (fn [[field type]]
                                          (str "    " (wit-name field) ": "
                                               (get record-field-wit-type type) ",\n"))
                                        fields))
                            "  }\n"))
                     record-schemas))
         "  variant " variant-name " {\n"
         (apply str
                (map (fn [[tag payload-type]]
                       (str "    " (wit-name tag) "("
                            (variant-case-payload-wit payload-type schemas) "),\n"))
                     cases))
         "  }\n}\n\n"
         "interface " interface " {\n"
         "  use types.{" variant-name "};\n"
         "  " (:function entry) ": func(request: " variant-name ") -> " variant-name ";\n"
         "}\n\n"
         "world " interface "-provider {\n  export " interface ";\n}\n")))

(defn package-variant-identity-provider
  "Build a wiring-only provider for one sealed variant identity whose cases
  are each a bare scalar or a sealed all-scalar record (ADR 0055/0056), the
  variant-crossing counterpart to `package-record-identity-provider`. The
  provider core module itself is
  `kotoba.compiler.component-core/variant-capability-provider-wat`, which
  reuses that namespace's own `variant-case-chain` (disc range check plus
  in-branch bool validation and store) rather than duplicating it here."
  [capability-name descriptor schemas]
  (let [entry (capability capability-name)
        wit (variant-wit entry descriptor schemas)
        dir (Files/createTempDirectory "kotoba-variant-provider-" (make-array FileAttribute 0))
        world (.resolve dir "provider.wit") core (.resolve dir "provider.wasm")
        embedded (.resolve dir "embedded.wasm") component (.resolve dir "provider.component.wasm")]
    (try
      (Files/writeString world wit (make-array java.nio.file.OpenOption 0))
      (Files/write core (wasm-tools/parse-wat
                         (component-core/variant-capability-provider-wat entry descriptor schemas))
                   (make-array java.nio.file.OpenOption 0))
      (wasm-tools/run-command! ["wasm-tools" "component" "embed" (str world) (str core)
                                "--encoding" "utf8" "-o" (str embedded)])
      (wasm-tools/run-command! ["wasm-tools" "component" "new" (str embedded)
                                "--reject-legacy-names" "-o" (str component)])
      {:format :wasm-component-provider/v1 :capability capability-name
       :descriptor descriptor :schemas schemas :bytes (Files/readAllBytes component)}
      (finally
        (doseq [path [component embedded core world]] (Files/deleteIfExists path))
        (Files/deleteIfExists dir)))))

(defn compose-closed
  "Compose one application with provider definitions and reject any remaining
  instance import. `wasm-tools compose --no-imports` is the closure gate."
  [application providers]
  (when-not (= :wasm-component/v1 (:format application))
    (reject "composition requires a compiler component artifact"
            {:format (:format application)}))
  (let [required (frequencies (:imports application))
        supplied (frequencies (map :capability providers))]
    (when-not (= required supplied)
      (reject "provider definitions do not exactly close application imports"
              {:required required :supplied supplied})))
  (assert-wac-version!)
  (let [dir (Files/createTempDirectory "kotoba-compose-" (make-array FileAttribute 0))
        app (.resolve dir "application.wasm")
        output (.resolve dir "closed.wasm")
        definitions (mapv #(.resolve dir (str "provider-" % ".wasm"))
                          (range (count providers)))]
    (try
      (Files/write app ^bytes (:bytes application) (make-array java.nio.file.OpenOption 0))
      (doseq [[path provider] (map vector definitions providers)]
        (when-not (= :wasm-component-provider/v1 (:format provider))
          (reject "definition is not a compiler provider artifact" {:format (:format provider)}))
        (Files/write path ^bytes (:bytes provider) (make-array java.nio.file.OpenOption 0)))
      (wasm-tools/run-command!
       (into ["wac" "plug" (str app) "-o" (str output)]
             (mapcat #(vector "--plug" (str %)) definitions)))
      (wasm-tools/run-command! ["wasm-tools" "validate" (str output)])
      {:format :wasm-component-closed/v1
       :bytes (Files/readAllBytes output)
       :application-imports (:imports application)
       :providers (mapv #(select-keys % [:capability :descriptor]) providers)}
      (finally
        (doseq [path (concat [output app] definitions)] (Files/deleteIfExists path))
        (Files/deleteIfExists dir)))))
