(ns kotoba.compiler.test-runner
  (:require [clojure.test :as t]
            [kotoba.compiler.core-test]
            [kotoba.compiler.bounded-edn-test]
            [kotoba.compiler.frontend-fuzz-test]
            [kotoba.compiler.frontend-limits-test]
            [kotoba.compiler.native-executor-test]
            [kotoba.compiler.admission-test]
            [kotoba.compiler.property-test]
            [kotoba.compiler.receipt-test]
            [kotoba.compiler.security-fuzz-test]
            [kotoba.compiler.signing-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'kotoba.compiler.core-test
                                          'kotoba.compiler.bounded-edn-test
                                          'kotoba.compiler.frontend-fuzz-test
                                          'kotoba.compiler.frontend-limits-test
                                          'kotoba.compiler.admission-test
                                          'kotoba.compiler.signing-test
                                          'kotoba.compiler.native-executor-test
                                          'kotoba.compiler.receipt-test
                                          'kotoba.compiler.security-fuzz-test
                                          'kotoba.compiler.property-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
