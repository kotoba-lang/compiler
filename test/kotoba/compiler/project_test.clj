(ns kotoba.compiler.project-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.project :as project]))

(def text-source
  "(ns example.text (:export [greet]))
   (defn- prefix [name :string] :string (string-concat \"こんにちは、\" name))
   (defn greet [name :string] :string (prefix name))")

(def app-source
  "(ns example.app
     (:require [example.text :as text])
     (:export [welcome]))
   (defn welcome [name :string] :string (text/greet name))")

(deftest closed-project-links-exported-functions
  (let [{:keys [source module-order modules]}
        (project/link-source {'example.app app-source 'example.text text-source} 'example.app)
        compiled (compiler/compile-source source :js-kotoba-v1)]
    (is (= ['example.text 'example.app] module-order))
    (is (= #{'example.text 'example.app} modules))
    (is (= "こんにちは、言葉" (ir/execute (:kir compiled) 'welcome ["言葉"])))
    (is (= ['welcome] (get-in compiled [:kir :exports])))
    (is (not-any? #{'greet 'prefix} (get-in compiled [:kir :exports])))))

(deftest project-linking-is-deterministic
  (let [a (project/link-source (array-map 'example.app app-source
                                           'example.text text-source)
                               'example.app)
        b (project/link-source (array-map 'example.text text-source
                                           'example.app app-source)
                               'example.app)]
    (is (= a b))))

(deftest compiler-seals-the-closed-module-graph
  (let [sources {'example.app app-source 'example.text text-source}
        a (compiler/compile-project sources 'example.app :js-kotoba-v1)
        b (compiler/compile-project (into (array-map) (reverse sources))
                                    'example.app :js-kotoba-v1)]
    (is (= (:project-digest a) (:project-digest b)))
    (is (= (:project-digest a)
           (get-in a [:manifest :kotoba.artifact/module-graph-digest])))
    (is (= ['example.text 'example.app]
           (get-in a [:project :kotoba.module/order])))
    (is (= #{'example.text 'example.app}
           (set (keys (get-in a [:manifest :kotoba.artifact/module-source-digests])))))
    (is (not= (:project-digest a)
              (:project-digest
               (compiler/compile-project
                (assoc sources 'example.text (str/replace text-source "こんにちは" "こんばんは"))
                'example.app :js-kotoba-v1))))))

(deftest project-imports-fail-closed
  (testing "missing source"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside the closed project"
                          (project/link-source {'example.app app-source} 'example.app))))
  (testing "private function"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an admitted exported import"
                          (project/link-source
                           {'example.text text-source
                            'example.app (str/replace app-source "text/greet" "text/prefix")}
                           'example.app))))
  (testing "cycle"
    (let [a "(ns cycle.a (:require [cycle.b :as b]) (:export [a])) (defn a [] (b/b))"
          b "(ns cycle.b (:require [cycle.a :as a]) (:export [b])) (defn b [] (a/a))"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cyclic module dependency"
                            (project/link-source {'cycle.a a 'cycle.b b} 'cycle.a)))))
  (testing "non alias import"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"imports require"
                          (project/link-source
                           {'example.app (str/replace app-source
                                                      "[example.text :as text]"
                                                      "[example.text :refer [greet]]")
                            'example.text text-source}
                           'example.app)))))
