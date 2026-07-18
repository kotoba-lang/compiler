(ns kotoba.compiler.project-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.frontend :as frontend]
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

(deftest project-modules-admit-the-same-bounded-namespace-docstrings
  (let [dependency (str/replace text-source "(ns example.text"
                                "(ns example.text \"bounded project documentation\"")
        linked (project/link-source {'example.app app-source 'example.text dependency}
                                    'example.app)]
    (is (= ['example.text 'example.app] (:module-order linked))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"namespace docstring exceeds admission limit"
       (project/link-source
        {'example.text
         (str "(ns example.text "
              (pr-str (apply str (repeat (inc frontend/max-namespace-docstring-chars) "x")))
              " (:export [greet])) (defn greet [] 0)")}
        'example.text))))

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
                                    'example.app :js-kotoba-v1)
        changed (compiler/compile-project
                 (assoc sources 'example.text (str/replace text-source "こんにちは" "こんばんは"))
                 'example.app :js-kotoba-v1)]
    (is (= (:project-digest a) (:project-digest b)))
    (is (= (:project-digest a)
           (get-in a [:manifest :kotoba.artifact/module-graph-digest])))
    (is (= ['example.text 'example.app]
           (get-in a [:project :kotoba.module/order])))
    (is (= #{'example.text 'example.app}
           (set (keys (get-in a [:manifest :kotoba.artifact/module-source-digests])))))
    (is (str/includes? (:source a)
                       (str "moduleGraphDigest:\"" (:project-digest a) "\"")))
    (is (str/includes? (:source a) "moduleSourceDigests:Object.freeze"))
    (is (str/includes? (:source a) "\"example.app\""))
    (is (str/includes? (:source a) "\"example.text\""))
    (is (not= (:project-digest a)
              (:project-digest changed)))
    (is (not= (get-in a [:manifest :kotoba.artifact/output-digest])
              (get-in changed [:manifest :kotoba.artifact/output-digest])))))

(deftest compiler-seals-verified-supply-chain-identity
  (let [digest (fn [character] (apply str (repeat 64 character)))
        supply-chain {:package-lock-digest (digest "a")
                      :trust-policy-digest (digest "b")
                      :package-receipt-digest (digest "c")}
        compiled (compiler/compile-project
                  {'sealed.app "(ns sealed.app (:export [answer])) (defn answer [] 42)"}
                  'sealed.app :js-kotoba-v1 {} supply-chain)]
    (is (= (digest "a")
           (get-in compiled [:manifest :kotoba.artifact/package-lock-digest])))
    (is (= (digest "b")
           (get-in compiled [:manifest :kotoba.artifact/trust-policy-digest])))
    (is (= (digest "c")
           (get-in compiled [:manifest :kotoba.artifact/package-receipt-digest])))
    (is (str/includes? (:source compiled)
                       (str "packageLockDigest:\"" (digest "a") "\"")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid verified supply-chain identity"
                          (compiler/compile-project
                           {'sealed.app "(ns sealed.app (:export [answer])) (defn answer [] 42)"}
                           'sealed.app :js-kotoba-v1 {}
                           {:package-lock-digest (digest "a")})))))

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

(defn- dependency-project [count all-previous?]
  (into {}
        (map (fn [index]
               (let [module (symbol (str "bounds.m" index))
                     dependencies (if all-previous?
                                    (range index)
                                    (when (pos? index) [(dec index)]))
                     specs (mapv (fn [dependency]
                                   [(symbol (str "bounds.m" dependency))
                                    :as (symbol (str "m" dependency))])
                                 dependencies)]
                 [module
                  (str (pr-str (list 'ns module
                                     (list* :require specs)
                                     (list :export ['value])))
                       "\n(defn value [] 0)")]))
        (range count))))

(deftest project-wide-resource-bounds-fail-before-linking
  (testing "aggregate source bytes"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source bytes exceed"
                          (project/link-source
                           {'large.module
                            (apply str (repeat (inc project/max-project-source-bytes) "x"))}
                           'large.module))))
  (testing "dependency depth"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency depth exceeds"
                          (project/link-source (dependency-project 66 false)
                                               'bounds.m65))))
  (testing "dependency edge count"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency edges exceed"
                          (project/link-source (dependency-project 24 true)
                                               'bounds.m23))))
  (testing "aggregate expression nodes"
    (let [body (apply str (repeat 100000 "(f) "))
          source (fn [namespace]
                   (str "(ns " namespace " (:export [f])) "
                        "(defn f [] (do " body "0))"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expression nodes exceed"
                            (project/link-source
                             {'bounds.expr-a (source 'bounds.expr-a)
                              'bounds.expr-b (source 'bounds.expr-b)}
                             'bounds.expr-a)))))
  (testing "aggregate literal count"
    (let [body (apply str (repeat 33000 "0 "))
          source (fn [namespace]
                   (str "(ns " namespace " (:export [f])) "
                        "(defn f [] (do " body "0))"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"project literals exceed"
                            (project/link-source
                             {'bounds.literal-a (source 'bounds.literal-a)
                              'bounds.literal-b (source 'bounds.literal-b)}
                             'bounds.literal-a)))))
  (testing "aggregate string literal bytes"
    (let [literal (pr-str (apply str (repeat 4096 "x")))
          body (str/join " " (repeat 15 literal))
          sources (into {}
                        (map (fn [index]
                               (let [namespace (symbol (str "bounds.str" index))]
                                 [namespace
                                  (str "(ns " namespace " (:export [f])) "
                                       "(defn f [] (do " body "))")]))
                        (range 18)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"string literal bytes exceed"
                            (project/link-source sources 'bounds.str0))))))

(deftest capability-effects-close-across-module-boundaries
  (let [sources
        {'effects.leaf
         "(ns effects.leaf (:export [read]))
          (defn- hidden-audit [value] (cap-call 9 value))
          (defn read [value] (cap-call 7 value))"
         'effects.middle
         "(ns effects.middle (:require [effects.leaf :as leaf]) (:export [forward]))
          (defn forward [value] (leaf/read value))"
         'effects.root
         "(ns effects.root (:require [effects.middle :as middle]) (:export [run]))
          (defn run [value] (middle/forward value))"}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denies required effects"
                          (compiler/compile-project sources 'effects.root :js-kotoba-v1)))
    (let [compiled (compiler/compile-project
                    sources 'effects.root :js-kotoba-v1
                    {:allow #{[:cap/call 7] [:cap/call 9] [:cap/call 10]}})]
      (is (= #{[:cap/call 7] [:cap/call 9]}
             (get-in compiled [:admission :required])))
      (is (= #{[:cap/call 10]} (get-in compiled [:admission :unused-grants])))
      (is (= #{[:cap/call 7] [:cap/call 9]} (get-in compiled [:kir :effects])))
      (is (str/includes? (:source compiled)
                         "requiredCapabilities:Object.freeze([7,9])")))))

(deftest changed-exports-and-dependency-substitution-fail-or-reseal
  (let [root "(ns substitution.root
                (:require [substitution.dep :as dep])
                (:export [run]))
              (defn run [] (dep/value))"
        dependency "(ns substitution.dep (:export [value])) (defn value [] 41)"
        sources {'substitution.root root 'substitution.dep dependency}
        original (compiler/compile-project sources 'substitution.root :js-kotoba-v1)
        substituted (compiler/compile-project
                     (assoc sources 'substitution.dep
                            "(ns substitution.dep (:export [value])) (defn value [] 42)")
                     'substitution.root :js-kotoba-v1)]
    (testing "changing an imported export fails before backend emission"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"not an admitted exported import"
           (compiler/compile-project
            (assoc sources 'substitution.dep
                   "(ns substitution.dep (:export [replacement]))
                    (defn replacement [] 41)")
            'substitution.root :js-kotoba-v1))))
    (testing "same-interface dependency bytes produce a distinct sealed artifact"
      (is (not= (:project-digest original) (:project-digest substituted)))
      (is (not= (get-in original [:manifest :kotoba.artifact/module-source-digests])
                (get-in substituted [:manifest :kotoba.artifact/module-source-digests])))
      (is (not= (get-in original [:manifest :kotoba.artifact/output-digest])
                (get-in substituted [:manifest :kotoba.artifact/output-digest]))))))
