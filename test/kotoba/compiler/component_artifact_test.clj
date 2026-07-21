(ns kotoba.compiler.component-artifact-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.component-artifact :as component]
            [kotoba.compiler.component-wit :as wit]))

(def scalar-kir
  {:format :kotoba.kir/v4 :exports ['add]
   :schemas {}
   :functions [{:name 'add :params ['left 'right]
                :param-types [:i64 :i64] :result :i64 :body '(+ left right)}]})

(deftest scalar-slice-and-unsupported-boundaries-are-explicit
  (is (true? (component/assert-scalar-slice! scalar-kir (wit/emit scalar-kir))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                        (component/assert-scalar-slice!
                         (assoc-in scalar-kir [:functions 0 :result] :string)
                         (wit/emit (assoc-in scalar-kir [:functions 0 :result] :string)))))
  (let [cap-kir (assoc-in scalar-kir [:functions 0 :body]
                          '(typed-cap-call 4 :i64 :i64 left))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"capability imports"
                          (component/assert-scalar-slice! cap-kir (wit/emit cap-kir))))))

(deftest bounded-string-expression-slice-is-explicit
  (let [identity-kir
        {:format :kotoba.kir/v4 :exports ['echo] :schemas {} :effects #{}
         :functions [{:name 'echo :params ['value] :param-types [:string]
                      :result :string :body 'value}]}]
    (is (true? (component/assert-scalar-slice! identity-kir (wit/emit identity-kir))))
    (let [concat-kir (-> identity-kir
                         (assoc-in [:functions 0 :params] ['left 'right])
                         (assoc-in [:functions 0 :param-types] [:string :string])
                         (assoc-in [:functions 0 :body]
                                   '(string-concat left (string-concat " / " right))))]
      (is (true? (component/assert-scalar-slice! concat-kir (wit/emit concat-kir)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component/assert-scalar-slice!
                           (assoc-in identity-kir [:functions 0 :body]
                                     '(string-replace-all value "a" "b"))
                           (wit/emit identity-kir))))))

(deftest named-scalar-record-identity-is-admitted
  (let [descriptor [:ref :demo/point]
        kir {:format :kotoba.kir/v4 :exports ['echo] :effects #{}
             :schemas {:demo/point
                       [:record :demo/point
                        [[:x :i64] [:weight :f64] [:visible :bool]]]}
             :functions [{:name 'echo :params ['value] :param-types [descriptor]
                          :result descriptor :body 'value}]}]
    (is (true? (component/assert-scalar-slice! kir (wit/emit kir))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component/assert-scalar-slice!
                           (assoc-in kir [:schemas :demo/point 2 0 1] :string)
                           (wit/emit kir))))))

(deftest named-scalar-record-field-projection-is-admitted
  (let [schema [:record :demo/point
                [[:x :i64] [:weight :f64] [:visible :bool]]]
        descriptor [:ref :demo/point]
        kir {:format :kotoba.kir/v4 :exports ['weight] :effects #{}
             :schemas {:demo/point schema}
             :functions [{:name 'weight :params ['value] :param-types [descriptor]
                          :result :f64 :body (list 'record-get schema 'value :weight)}]}]
    (is (true? (component/assert-scalar-slice! kir (wit/emit kir))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component/assert-scalar-slice!
                           (assoc-in kir [:functions 0 :body]
                                     (list 'record-get schema 'value :missing))
                           (wit/emit kir))))))

(defn- binary-text [bytes]
  (String. ^bytes bytes java.nio.charset.StandardCharsets/ISO_8859_1))

(deftest standard32-core-names-are-explicit-and-target-local
  (let [standard32 (binary-text
                    (wasm/emit-component-core scalar-kir :wasm32-wasi-kotoba-v1))
        ordinary (binary-text (wasm/emit scalar-kir :wasm32-wasi-kotoba-v1))]
    (doseq [name ["cm32p2||add" "cm32p2||add_post" "cm32p2_memory"
                  "cm32p2_realloc" "cm32p2_initialize"]]
      (is (str/includes? standard32 name)))
    (is (str/includes? ordinary "add"))
    (is (not (str/includes? ordinary "cm32p2||add")))))
