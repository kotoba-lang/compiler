(ns kotoba.compiler.native-executor
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kotoba.compiler.admission :as admission]
            [kotoba.compiler.signing :as signing])
  (:import [java.nio.file Files]
           [java.nio.charset StandardCharsets]
           [java.nio.file.attribute FileAttribute]
           [java.security MessageDigest]))

(def loader-source-sha256
  "Pinned identity of the reviewed native loader source used by this executor."
  "931a3ae345035ccdc3da1db1f5d6471e0bd49027a04f905e21895e76a4be522a")

(defn- raw-sha256 [bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- file-sha256 [file]
  (raw-sha256 (Files/readAllBytes (.toPath ^java.io.File file))))

(defn- host-target []
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (str/lower-case (System/getProperty "os.arch"))]
    (when-not (or (str/includes? os "linux") (str/includes? os "mac"))
      (throw (ex-info "native execution is unsupported on this OS"
                      {:phase :execute :os os})))
    (cond
      (contains? #{"amd64" "x86_64"} arch) :x86_64-kotoba-v1
      (contains? #{"aarch64" "arm64"} arch) :aarch64-kotoba-v1
      :else (throw (ex-info "native execution is unsupported on this architecture"
                            {:phase :execute :arch arch})))))

(defn- deterministic-linker-flags []
  (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
    []
    ["-Wl,--build-id=none"]))

(defn- run-process [command env]
  (let [builder (ProcessBuilder. ^java.util.List (mapv str command))
        _ (.putAll (.environment builder) env)
        process (.start builder)
        stdout (atom nil)
        stderr (atom nil)
        stdout-reader (doto (Thread. #(reset! stdout (slurp (.getInputStream process))))
                        (.setDaemon true) (.start))
        stderr-reader (doto (Thread. #(reset! stderr (slurp (.getErrorStream process))))
                        (.setDaemon true) (.start))
        exit (.waitFor process)]
    (.join stdout-reader)
    (.join stderr-reader)
    {:exit exit :stdout @stdout :stderr @stderr}))

(defn- delete-tree! [file]
  (when (.exists ^java.io.File file)
    (doseq [child (reverse (file-seq file))] (io/delete-file child true))))

(defn- allowed-capabilities [policy]
  (->> (:allow policy #{})
       (keep (fn [effect]
               (when (and (vector? effect) (= :cap/call (first effect))
                          (integer? (second effect)))
                 (second effect))))
       sort
       (str/join ",")))

(defn- trap-value [stderr]
  (when-let [[_ value] (re-find #"(?m)^KEXE_TRAP (\{.*\})$" stderr)]
    (edn/read-string value)))

(defn execute
  "Verify and execute a signed native artifact. Returns measured supervisor evidence."
  [envelope trust policy input {:keys [now entry]}]
  (let [{artifact :artifact signer :signer} (signing/verify envelope trust now)
        target (host-target)
        entry (or entry 'main)
        export (get (:exports artifact) entry)
        args (:args input)]
    (admission/check {:effects (:effects artifact)} policy)
    (when-not (= target (:target artifact))
      (throw (ex-info "artifact target does not match execution host"
                      {:phase :execute :artifact-target (:target artifact)
                       :host-target target})))
    (when-not export
      (throw (ex-info "unknown native entry" {:phase :execute :entry entry})))
    (when-not (and (map? input) (vector? args) (every? integer? args)
                   (every? #(<= Long/MIN_VALUE % Long/MAX_VALUE) args)
                   (= (:arity export) (count args)) (<= (count args) 5))
      (throw (ex-info "execution input does not match entry arity"
                      {:phase :execute :entry entry :arity (:arity export)})))
    (let [directory (.toFile (Files/createTempDirectory
                              "kotoba-native-" (make-array FileAttribute 0)))
          code-file (io/file directory "program.bin")
          loader (io/file directory "kexe-loader")
          loader-source (io/file "tools/kexe_loader.c")]
      (try
        (let [actual-source-sha (file-sha256 loader-source)]
          (when-not (= loader-source-sha256 actual-source-sha)
            (throw (ex-info "native loader source identity mismatch"
                            {:phase :execute :expected loader-source-sha256
                             :actual actual-source-sha}))))
        (with-open [out (io/output-stream code-file)]
          (.write out ^bytes (byte-array (map unchecked-byte (:code artifact)))))
        (let [compiler (run-process ["cc" "--version"] {})
              _ (when-not (zero? (:exit compiler))
                  (throw (ex-info "native C compiler identity query failed"
                                  {:phase :execute :stderr (:stderr compiler)})))
              compiler-text (str (:stdout compiler) (:stderr compiler))
              build-command (fn [output]
                              (vec (concat
                                    ["cc" "-std=c11" "-O2" "-Wall" "-Wextra" "-Werror"]
                                    (deterministic-linker-flags)
                                    [(.getPath loader-source) "-o" (.getPath output)])))
              build (run-process (build-command loader) {})
              first-loader-sha (when (zero? (:exit build)) (file-sha256 loader))
              reproduced-build (run-process (build-command loader) {})]
          (when-not (and (zero? (:exit build)) (zero? (:exit reproduced-build)))
            (throw (ex-info "native loader build failed"
                            {:phase :execute :stderr (str (:stderr build)
                                                         (:stderr reproduced-build))})))
          (let [loader-sha (file-sha256 loader)
                _ (when-not (= first-loader-sha loader-sha)
                    (throw (ex-info "native loader build is not reproducible"
                                    {:phase :execute :first first-loader-sha
                                     :second loader-sha})))
                runtime {:format :kotoba.native-runtime/v1
                         :loader-source-sha256 loader-source-sha256
                         :loader-binary-sha256 loader-sha
                         :compiler-identity-sha256
                         (raw-sha256 (.getBytes compiler-text StandardCharsets/UTF_8))}
                isa (if (= target :x86_64-kotoba-v1) "x86_64" "aarch64")
              allow (let [ids (allowed-capabilities policy)] (if (empty? ids) "-" ids))
              command (into [(.getPath loader) (.getPath code-file)
                             (str (:offset export)) (str (:arity export)) isa allow]
                            (map str args))
              started-at (quot (System/currentTimeMillis) 1000)
              process (run-process command {"KEXE_STRUCTURED_REPORT" "1"})
              finished-at (quot (System/currentTimeMillis) 1000)
              report (edn/read-string (str/trim (:stdout process)))
              trap (trap-value (:stderr process))
              status (:status report)
              evidence (cond-> {:status status :runtime runtime}
                         (= status :ok) (assoc :result (:result report))
                         trap (assoc :trap trap))]
          (when-not (and (#{:ok :trap} status)
                         (= (zero? (:exit process)) (= status :ok))
                         (integer? (get-in report [:fuel :initial]))
                         (integer? (get-in report [:fuel :remaining])))
            (throw (ex-info "malformed native supervisor evidence"
                            {:phase :execute :exit (:exit process)
                             :stdout (:stdout process) :stderr (:stderr process)})))
          {:artifact artifact :signer signer :target target :entry entry
           :input input :evidence evidence :report report
           :started-at started-at :finished-at finished-at}))
        (finally (delete-tree! directory))))))
