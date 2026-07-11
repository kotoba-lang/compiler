(ns kotoba.compiler.test-runner
  (:require [clojure.test :as t]
            [kotoba.compiler.core-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'kotoba.compiler.core-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
