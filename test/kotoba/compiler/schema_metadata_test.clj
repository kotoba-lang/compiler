(ns kotoba.compiler.schema-metadata-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.backend.wasm-typed :as typed]
            [kotoba.compiler.core :as compiler]))

(def source
  "(ns app.tree
     (:export [main])
     (:schemas {:app/node
                [:variant :app/node
                 [[:leaf :i64]
                  [:branch [:vector [[:ref :app/node] [:ref :app/node]]]]]]}))
   (defn main [] :i64 0)")

(deftest closed-schema-table-crosses-hir-kir-and-wasm-metadata
  (let [checked (compiler/check-source source)
        compiled (compiler/compile-source source :wasm32-browser-kotoba-v1)
        kir (:kir compiled)]
    (is (= (:schemas (:hir checked)) (:schemas kir)))
    (is (= 64 (count (get-in kir [:schema-identities :app/node]))))
    (is (= typed/schema-abi-version (first (typed/metadata-bytes kir))))
    (is (bytes? (:bytes compiled)))))

(deftest malformed-source-schema-fails-before-lowering
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not declared"
                        (compiler/check-source
                         "(ns bad (:schemas {:app/a [:option [:ref :app/missing]]}))
                          (defn main [] 0)"))))
