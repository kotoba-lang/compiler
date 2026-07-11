(ns kotoba.compiler.cli-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.cli :as cli])
  (:import [java.io StringWriter]))

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
            :message "rejected" :details {:phase :subset :limit 10}}
           report)))
  (is (= {:format :kotoba.cli-error/v1 :ok false :error :internal
          :message "internal compiler error"}
         (cli/error-report (ex-info "sensitive host failure" {:secret "value"})))))

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
