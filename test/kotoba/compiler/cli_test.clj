(ns kotoba.compiler.cli-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.cli :as cli]
            [kotoba.compiler.frontend :as frontend])
  (:import [java.io StringWriter]))

(defn- temp-kotoba-source!
  ([contents] (temp-kotoba-source! contents ".kotoba"))
  ([contents extension]
  (let [file (doto (java.io.File/createTempFile "kotoba-cli-test-" extension)
               (.deleteOnExit))]
    (spit file contents)
    (.getPath file))))

(deftest phase-exit-codes-are-stable
  (is (= 64 (cli/exit-code :usage)))
  (is (= 65 (cli/exit-code :decode)))
  (is (= 65 (cli/exit-code :verify)))
  (is (= 77 (cli/exit-code :trust)))
  (is (= 74 (cli/exit-code :output)))
  (is (= 70 (cli/exit-code :unknown))))

(deftest error-envelope-redacts-untrusted-and-internal-data
  (let [report (cli/error-report
                (ex-info "rejected" {:phase :subset :limit 10
                                     :form '(secret payload) :path "/private/path"}))]
    (is (= {:format :kotoba.cli-error/v1 :ok false :error :subset
            :diagnostic {:format :kotoba.diagnostic/v1
                         :code :kotoba/source-rejected :severity :error}
            :message "rejected" :details {:phase :subset :limit 10}}
           report)))
  (is (= {:format :kotoba.cli-error/v1 :ok false :error :internal
          :diagnostic {:format :kotoba.diagnostic/v1
                       :code :kotoba/internal-error :severity :error}
          :message "internal compiler error"}
         (cli/error-report (ex-info "sensitive host failure" {:secret "value"})))))

(deftest structured-diagnostic-has-stable-code-and-bounded-source-span
  (let [error (try
                (frontend/analyze
                 "(defn main []\n  (forbidden-call 1))")
                nil
                (catch clojure.lang.ExceptionInfo error error))
        report (cli/error-report error "program.cljk")]
    (is (= :kotoba/source-rejected (get-in report [:diagnostic :code])))
    (is (= "program.cljk" (get-in report [:diagnostic :source])))
    (is (= {:line 2 :column 3}
           (select-keys (get-in report [:diagnostic :span]) [:line :column])))
    (is (not (contains? report :form)))))

(deftest unknown-command-emits-one-machine-readable-line-without-stack
  (let [status (atom nil)
        writer (StringWriter.)
        _ (binding [cli/*exit* #(reset! status %)
                    *err* writer]
            (cli/-main "unknown-command"))
        stderr (str writer)
        lines (str/split-lines stderr)
        report (edn/read-string (first lines))]
    (is (= 64 @status))
    (is (= 1 (count lines)))
    (is (= :kotoba.cli-error/v1 (:format report)))
    (is (= :usage (:error report)))
    (is (not (re-find #"Exception|\.clj:|Full report" stderr)))))

(deftest compile-and-check-require-kotoba-source-files
  (doseq [command ["compile" "check"]
          path ["program.clj" "program.cljs" "program.KOTOBA" "program"]]
    (let [status (atom nil)
          writer (StringWriter.)]
      (binding [cli/*exit* #(reset! status %)
                *err* writer]
        (cli/-main command path))
      (let [report (edn/read-string (str writer))]
        (is (= 64 @status))
        (is (= :usage (:error report)))
        (is (= "source input must use .kotoba, .cljk, or .cljc" (:message report)))))))

(deftest admitted-extensions-select-the-requested-backend
  (doseq [[extension target expected-format]
          [[".cljk" "js" :javascript/esm]
           [".cljk" "wasm32" :wasm/v1]
           [".cljc" "js" :javascript/esm]
           [".cljc" "wasm32" :wasm/v1]]]
    (let [source (temp-kotoba-source! "(defn main [] 42)" extension)
          output (.getPath (doto (java.io.File/createTempFile "kotoba-target-selection-" ".out")
                             (.deleteOnExit)))
          out (StringWriter.)]
      (binding [*out* out]
        (cli/-main "compile" source "--target" target "--output" output))
      (let [report (edn/read-string (str out))]
        (is (:ok report))
        (is (= output (:output report)))
        (is (= :kotoba.provenance/v1
               (:format (edn/read-string (slurp (:provenance-output report))))))
        (if (= expected-format :wasm/v1)
          (is (= [0 0x61 0x73 0x6d]
                 (mapv #(bit-and % 0xff)
                       (take 4 (java.nio.file.Files/readAllBytes
                                (.toPath (java.io.File. output)))))))
          (is (str/includes? (slurp output) "export")))))))

(deftest compile-source-path-loads-only-a-closed-qualified-project
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "kotoba-cli-project-" (make-array java.nio.file.attribute.FileAttribute 0)))
        source-directory (io/file directory "src")
        dependency (io/file source-directory "example/text.kotoba")
        root (io/file directory "main.kotoba")
        output (io/file directory "app.mjs")
        out (StringWriter.)]
    (.mkdirs (.getParentFile dependency))
    (spit dependency
          "(ns example.text (:export [answer])) (defn answer [] 42)")
    (spit root
          "(ns example.app (:require [example.text :as text]) (:export [main]))
           (defn main [] (text/answer))")
    (binding [*out* out]
      (cli/-main "compile" (.getPath root) "--source-path" (.getPath source-directory)
                 "--target" "js" "--output" (.getPath output)))
    (let [report (edn/read-string (str out))
          manifest (edn/read-string (slurp (str output ".manifest.edn")))]
      (is (:ok report))
      (is (.isFile output))
      (is (= #{'example.app 'example.text}
             (set (keys (:kotoba.artifact/module-source-digests manifest))))))))

(deftest compile-repeated-source-path-links-explicit-package-roots
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "kotoba-cli-multi-root-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        app-root (io/file directory "app-src")
        library-root (io/file directory "library-src")
        dependency (io/file library-root "shared/answer.kotoba")
        root (io/file app-root "app/main.kotoba")
        output (io/file directory "app.mjs")
        out (StringWriter.)]
    (.mkdirs (.getParentFile dependency))
    (.mkdirs (.getParentFile root))
    (spit dependency
          "(ns shared.answer (:export [answer])) (defn answer [] 42)")
    (spit root
          "(ns app.main (:require [shared.answer :as shared]) (:export [main]))
           (defn main [] (shared/answer))")
    (binding [*out* out]
      (cli/-main "compile" (.getPath root)
                 "--source-path" (.getPath app-root)
                 "--source-path" (.getPath library-root)
                 "--target" "js" "--output" (.getPath output)))
    (let [report (edn/read-string (str out))]
      (is (:ok report))
      (is (str/includes? (slurp output) "moduleSourceDigests")))))

(deftest compile-repeated-source-path-rejects-cross-package-shadowing
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "kotoba-cli-ambiguous-root-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        roots (mapv #(io/file directory %) ["one" "two"])
        root (io/file directory "main.kotoba")
        status (atom nil)
        err (StringWriter.)]
    (doseq [source-root roots]
      (let [dependency (io/file source-root "shared/answer.kotoba")]
        (.mkdirs (.getParentFile dependency))
        (spit dependency
              "(ns shared.answer (:export [answer])) (defn answer [] 42)")))
    (spit root
          "(ns app.main (:require [shared.answer :as shared]) (:export [main]))
           (defn main [] (shared/answer))")
    (binding [cli/*exit* #(reset! status %)
              *err* err]
      (cli/-main "compile" (.getPath root)
                 "--source-path" (.getPath (first roots))
                 "--source-path" (.getPath (second roots))
                 "--target" "js"))
    (let [report (edn/read-string (str err))]
      (is (= 65 @status))
      (is (= :project-link (:error report)))
      (is (= "namespace resolves from multiple explicit source paths"
             (:message report))))))

(deftest compile-source-path-rejects-namespace-path-substitution
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "kotoba-cli-project-reject-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        dependency (io/file directory "example/text.kotoba")
        root (io/file directory "example/app.kotoba")
        status (atom nil)
        err (StringWriter.)]
    (.mkdirs (.getParentFile dependency))
    (spit dependency
          "(ns attacker.substitute (:export [answer])) (defn answer [] 42)")
    (spit root
          "(ns example.app (:require [example.text :as text]) (:export [main]))
           (defn main [] (text/answer))")
    (binding [cli/*exit* #(reset! status %)
              *err* err]
      (cli/-main "compile" (.getPath root) "--source-path" (.getPath directory)
                 "--target" "js"))
    (let [report (edn/read-string (str err))]
      (is (= 65 @status))
      (is (= :project-link (:error report)))
      (is (= "resolved path namespace does not match requirement" (:message report))))))

(deftest compile-cljs-target-writes-real-source-not-a-corrupted-nil-artifact
  (testing "regression test for a real bug found live: `compile --target
            cljs-kotoba-v1` reported {:ok true ...} while silently writing
            the literal text \"nil\" to --output, because compile-source's
            :cljs/v1 result carries :source (a string), not :artifact --
            and the old code unconditionally wrote (:artifact result) via
            write-edn! for every non-:wasm/v1 format."
    (let [source (temp-kotoba-source! "(defn main [] (let [x 40 y 2] (+ x y)))")
          output (.getPath (doto (java.io.File/createTempFile "kotoba-cli-cljs-out-" ".cljs")
                             (.deleteOnExit)))
          status (atom nil)
          out (StringWriter.)]
      (binding [cli/*exit* #(reset! status %)
                *out* out]
        (cli/-main "compile" source "--target" "cljs-kotoba-v1" "--output" output))
      (let [report (edn/read-string (str out))
            written (slurp output)]
        (is (nil? @status))
        (is (:ok report))
        (is (= :cljs-kotoba-v1 (:target report)))
        (is (not= "nil" (str/trim written)))
        (is (str/includes? written "(defn main"))
        ;; the written text must be genuinely readable cljs source, not an
        ;; EDN-escaped string literal (what write-edn!'s pr-str would have
        ;; produced instead)
        (is (seq? (reader/read-string {:read-cond :allow :features #{:cljs}}
                                      (str "(" written ")"))))))))

(deftest compile-cljs-target-default-output-extension-is-cljs
  (let [source (temp-kotoba-source! "(defn main [] 42)")
        status (atom nil)
        out (StringWriter.)]
    (binding [cli/*exit* #(reset! status %)
              *out* out]
      (cli/-main "compile" source "--target" "cljs-kotoba-v1"))
    (let [report (edn/read-string (str out))]
      (is (nil? @status))
      (is (str/ends-with? (:output report) ".cljs"))
      (is (str/includes? (slurp (:output report)) "(defn main")))))

(deftest compile-aiueos-user-target-writes-elf-not-kexe-edn
  (let [source (temp-kotoba-source! "(defn main [] (+ 40 2))")
        output (.getPath (doto (java.io.File/createTempFile "kotoba-aiueos-user-" ".elf")
                           (.deleteOnExit)))
        out (StringWriter.)]
    (binding [*out* out]
      (cli/-main "compile" source "--target" "x86_64-aiueos-user-v1"
                 "--output" output))
    (let [bytes (java.nio.file.Files/readAllBytes (.toPath (java.io.File. output)))]
      (is (= [0x7f 0x45 0x4c 0x46]
             (mapv #(bit-and % 0xff) (take 4 bytes)))))))

(deftest compile-aiueos-kernel-image-bypasses-the-object-link-stage
  (let [source (temp-kotoba-source! "(defn main [] (kernel-out-u32 244 16))")
        output (.getPath (doto (java.io.File/createTempFile "kotoba-aiueos-kernel-" ".elf")
                           (.deleteOnExit)))
        out (StringWriter.)]
    (binding [*out* out]
      (cli/-main "compile" source "--target" "x86_64-aiueos-kernel-v1"
                 "--artifact" "image" "--output" output))
    (let [bytes (java.nio.file.Files/readAllBytes (.toPath (java.io.File. output)))]
      (is (= [0x7f 0x45 0x4c 0x46]
             (mapv #(bit-and % 0xff) (take 4 bytes))))
      (is (= 2 (bit-and (aget bytes 16) 0xff)) "ET_EXEC, not ET_REL"))))

(deftest compile-wasm-target-is-unaffected-by-the-cljs-output-fix
  (let [source (temp-kotoba-source! "(defn main [] (let [x 40 y 2] (+ x y)))")
        output (.getPath (doto (java.io.File/createTempFile "kotoba-cli-wasm-out-" ".wasm")
                           (.deleteOnExit)))
        status (atom nil)
        out (StringWriter.)]
    (binding [cli/*exit* #(reset! status %)
              *out* out]
      (cli/-main "compile" source "--target" "wasm32" "--output" output))
    (let [report (edn/read-string (str out))
          bytes (java.nio.file.Files/readAllBytes (.toPath (java.io.File. ^String output)))]
      (is (nil? @status))
      (is (:ok report))
      (is (= [0 97 115 109] (mapv #(bit-and % 0xff) (take 4 bytes)))
          "still a real WASM binary (magic bytes), the write-bytes! path is untouched"))))
