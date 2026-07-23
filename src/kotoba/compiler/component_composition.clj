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

(def wac-version "0.9.0")

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

(defn- variant-wit
  "Deterministic WIT package/world text for one sealed variant provider
  whose every case's payload is a bare Canonical scalar (ADR 0055) -- the
  provider-side counterpart to `record-wit`. Deliberately does not admit a
  case wrapping a record (unlike the identity-export variant path, ADR
  0052/0054): a record-referencing-variant provider built this same way was
  tried against `wac plug` during this ADR's own implementation and fails
  encoding with `type not valid to be used as import` for every shape tried,
  independent of case count, case mix, and `types`-interface declaration
  order -- see `kotoba.compiler.component-core/scalar-only-variant-case?`'s
  docstring for the full reproduction. Every case's `types`-interface
  footprint here is therefore exactly one exported type (the variant
  itself), matching `provider-wit`'s own single-scalar-type shape and
  `record-wit`'s own single-record-type shape, both already proven to
  `wac plug` correctly."
  [entry descriptor schemas]
  (let [schema (get schemas (second descriptor))
        [_ identity cases] schema
        variant-name (wit-name identity)
        interface (:interface entry)]
    (when-not (and (= :ref (first descriptor))
                   (= :variant (first schema))
                   (= identity (second descriptor))
                   (seq cases)
                   (every? (comp variant-case-wit-type second) cases))
      (reject "provider variant requires scalar-only cases"
              {:descriptor descriptor :schema schema}))
    (str "package kotoba:application@1.0.0;\n\n"
         "interface types {\n"
         "  variant " variant-name " {\n"
         (apply str
                (map (fn [[tag payload-type]]
                       (str "    " (wit-name tag) "(" (get variant-case-wit-type payload-type) "),\n"))
                     cases))
         "  }\n}\n\n"
         "interface " interface " {\n"
         "  use types.{" variant-name "};\n"
         "  " (:function entry) ": func(request: " variant-name ") -> " variant-name ";\n"
         "}\n\n"
         "world " interface "-provider {\n  export " interface ";\n}\n")))

(defn package-variant-identity-provider
  "Build a wiring-only provider for one sealed scalar-only-case variant
  identity (ADR 0055), the variant-crossing counterpart to
  `package-record-identity-provider`. The provider core module itself is
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
