(ns test.nbb.project
  (:require [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.project :as project]))

(def sources
  {'example.text
   "(ns example.text (:export [greet]))
    (defn greet [name :string] :string (string-concat \"こんにちは、\" name))"
   'example.app
   "(ns example.app (:require [example.text :as text]) (:export [welcome]))
    (defn welcome [name :string] :string (text/greet name))"})

(try
  (let [{:keys [source module-order]} (project/link-source sources 'example.app)
        kir (ir/lower (frontend/analyze source))]
    (assert (= ['example.text 'example.app] module-order))
    (assert (= "こんにちは、言葉" (ir/execute kir 'welcome ["言葉"])))
    (println "PASS safe-project-link"))
  (catch :default error
    (println "FAIL safe-project-link" (.-message error))
    (.exit js/process 1)))
