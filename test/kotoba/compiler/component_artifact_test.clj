(ns kotoba.compiler.component-artifact-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.component-artifact :as component]
            [kotoba.compiler.component-core :as component-core]
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
  (let [cap-kir (assoc scalar-kir
                       :exports ['invoke]
                       :functions [{:name 'invoke :params ['request]
                                    :param-types [:i64] :result :i64
                                    :body '(typed-cap-call 4 :i64 :i64 request)}])]
    (is (true? (component/assert-scalar-slice! cap-kir (wit/emit cap-kir))))
    (let [artifact (component/package
                    (component-core/emit cap-kir :wasm32-wasi-kotoba-v1)
                    cap-kir (wit/emit cap-kir))]
      (is (= :scalar-capability-call (:canonical-lowering artifact)))
      (is (= [:http/post] (:imports artifact)))
      (is (= :wasm-component/v1 (:format artifact))))))

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
    ;; A field outside the admitted flat-leaf type set (i64/f32/f64/bool/
    ;; string/keyword) remains fail-closed. Before ADR 0053, this assertion
    ;; used `:string` here; as of ADR 0053 a `:string` field is admitted (via
    ;; the dedicated `:string-field-record-identity` path, not this scalar
    ;; path -- see `string-and-keyword-field-record-identity-is-admitted`),
    ;; so a genuinely still-unadmitted field type (`[:vector :i64]`) is
    ;; needed to keep testing "an out-of-set field type is rejected".
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component/assert-scalar-slice!
                           (assoc-in kir [:schemas :demo/point 2 0 1] [:vector :i64])
                           (wit/emit kir))))))

(deftest one-level-nested-scalar-record-identity-is-admitted
  (let [descriptor [:ref :demo/outer]
        schemas {:demo/inner [:record :demo/inner [[:code :i64] [:ratio :f64]]]
                 :demo/outer [:record :demo/outer
                              [[:id :i64] [:inner [:ref :demo/inner]] [:active :bool]]]}
        kir {:format :kotoba.kir/v4 :exports ['echo] :effects #{}
             :schemas schemas
             :functions [{:name 'echo :params ['value] :param-types [descriptor]
                          :result descriptor :body 'value}]}]
    (is (true? (component/assert-scalar-slice! kir (wit/emit kir))))
    (let [artifact (component/package
                    (component-core/emit kir :wasm32-wasi-kotoba-v1)
                    kir (wit/emit kir))]
      (is (= :nested-record-identity (:canonical-lowering artifact)))
      (is (= :wasm-component/v1 (:format artifact)))
      (is (= [0 97 115 109 13 0 1 0]
             (mapv #(bit-and (int %) 0xff) (take 8 (:bytes artifact))))))
    ;; Two levels of nesting remain fail-closed: a nested field's own field
    ;; may not itself be a nested record.
    (let [two-level-schemas
          (assoc schemas
                 :demo/middle [:record :demo/middle [[:leaf [:ref :demo/inner]]]]
                 :demo/deep [:record :demo/deep [[:mid [:ref :demo/middle]]]])
          deep-kir (-> kir
                      (assoc :schemas two-level-schemas)
                      (assoc-in [:functions 0 :param-types] [[:ref :demo/deep]])
                      (assoc-in [:functions 0 :result] [:ref :demo/deep]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                            (component/assert-scalar-slice! deep-kir (wit/emit deep-kir)))))
    ;; A nested field with a non-scalar (string) leaf remains fail-closed.
    (let [string-leaf-schemas
          {:demo/inner-str [:record :demo/inner-str [[:label :string]]]
           :demo/outer-str [:record :demo/outer-str
                            [[:id :i64] [:inner [:ref :demo/inner-str]]]]}
          string-kir (-> kir
                        (assoc :schemas string-leaf-schemas)
                        (assoc-in [:functions 0 :param-types] [[:ref :demo/outer-str]])
                        (assoc-in [:functions 0 :result] [:ref :demo/outer-str]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                            (component/assert-scalar-slice! string-kir (wit/emit string-kir)))))
    ;; A nested field descriptor whose sealed schema identity has drifted
    ;; remains fail-closed.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component/assert-scalar-slice!
                           (assoc-in kir [:schemas :demo/inner 1] :demo/renamed)
                           (wit/emit kir))))))

(deftest variant-with-record-and-scalar-cases-identity-is-admitted
  (let [descriptor [:ref :demo/outcome]
        schemas {:demo/entry [:record :demo/entry
                              [[:code :i64] [:ratio :f64] [:present :bool]]]
                 :demo/short [:record :demo/short [[:code :i64] [:flag :bool]]]
                 :demo/outcome [:variant :demo/outcome
                                [[:found [:ref :demo/entry]]
                                 [:missing :bool]
                                 [:failed :f32]
                                 [:short [:ref :demo/short]]]]}
        kir {:format :kotoba.kir/v4 :exports ['echo] :effects #{}
             :schemas schemas
             :functions [{:name 'echo :params ['value] :param-types [descriptor]
                          :result descriptor :body 'value}]}]
    (is (true? (component/assert-scalar-slice! kir (wit/emit kir))))
    (let [artifact (component/package
                    (component-core/emit kir :wasm32-wasi-kotoba-v1)
                    kir (wit/emit kir))]
      (is (= :variant-identity (:canonical-lowering artifact)))
      (is (= :wasm-component/v1 (:format artifact)))
      (is (= [0 97 115 109 13 0 1 0]
             (mapv #(bit-and (int %) 0xff) (take 8 (:bytes artifact))))))
    ;; A case wrapping a record with a non-scalar (string) field remains
    ;; fail-closed, exactly like a record identity's non-scalar leaf.
    (let [string-case-schemas
          {:demo/label [:record :demo/label [[:text :string]]]
           :demo/status [:variant :demo/status
                         [[:ready :bool] [:labeled [:ref :demo/label]]]]}
          string-kir (-> kir
                        (assoc :schemas string-case-schemas)
                        (assoc-in [:functions 0 :param-types] [[:ref :demo/status]])
                        (assoc-in [:functions 0 :result] [:ref :demo/status]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                            (component/assert-scalar-slice! string-kir (wit/emit string-kir)))))
    ;; A case wrapping a record that is itself one level nested (the ADR
    ;; 0051 shape) remains fail-closed: a variant case payload is bounded to
    ;; the flat ADR 0043 record shape only in this slice.
    (let [nested-case-schemas
          {:demo/inner [:record :demo/inner [[:code :i64]]]
           :demo/wrapper [:record :demo/wrapper [[:inner [:ref :demo/inner]]]]
           :demo/status [:variant :demo/status
                         [[:ready :bool] [:wrapped [:ref :demo/wrapper]]]]}
          nested-kir (-> kir
                        (assoc :schemas nested-case-schemas)
                        (assoc-in [:functions 0 :param-types] [[:ref :demo/status]])
                        (assoc-in [:functions 0 :result] [:ref :demo/status]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                            (component/assert-scalar-slice! nested-kir (wit/emit nested-kir)))))
    ;; A case wrapping another variant remains fail-closed: only scalars and
    ;; sealed all-scalar records are admitted case payloads in this slice.
    (let [variant-case-schemas
          {:demo/inner-variant [:variant :demo/inner-variant [[:a :bool]]]
           :demo/status [:variant :demo/status
                         [[:ready :bool] [:nested [:ref :demo/inner-variant]]]]}
          variant-case-kir (-> kir
                               (assoc :schemas variant-case-schemas)
                               (assoc-in [:functions 0 :param-types] [[:ref :demo/status]])
                               (assoc-in [:functions 0 :result] [:ref :demo/status]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                            (component/assert-scalar-slice! variant-case-kir
                                                            (wit/emit variant-case-kir)))))
    ;; A case schema whose sealed identity has drifted remains fail-closed.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component/assert-scalar-slice!
                           (assoc-in kir [:schemas :demo/entry 1] :demo/renamed)
                           (wit/emit kir))))
    ;; A computed variant body (anything other than a bare parameter
    ;; passthrough) remains fail-closed.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component/assert-scalar-slice!
                           (assoc-in kir [:functions 0 :body] '(record-get schemas value :found))
                           (wit/emit kir))))))

(deftest variant-with-only-bool-and-f32-cases-identity-is-admitted
  ;; Dedicated to the Component Model spec's other join outcome (the
  ;; i32/f32 special case): neither case ever touches i64/f64, so the
  ;; shared payload position stays i32 instead of widening to i64, and the
  ;; f32 case needs the single-instruction `f32.reinterpret_i32` coercion
  ;; that the record-and-scalar-cases test above never exercises (every
  ;; payload position there is i64-dominated).
  (let [descriptor [:ref :demo/flag-or-ratio]
        schemas {:demo/flag-or-ratio [:variant :demo/flag-or-ratio
                                      [[:urgent :bool] [:weight :f32]]]}
        kir {:format :kotoba.kir/v4 :exports ['echo] :effects #{}
             :schemas schemas
             :functions [{:name 'echo :params ['value] :param-types [descriptor]
                          :result descriptor :body 'value}]}]
    (is (true? (component/assert-scalar-slice! kir (wit/emit kir))))
    (let [artifact (component/package
                    (component-core/emit kir :wasm32-wasi-kotoba-v1)
                    kir (wit/emit kir))]
      (is (= :variant-identity (:canonical-lowering artifact)))
      (is (= :wasm-component/v1 (:format artifact))))))

(deftest string-and-keyword-field-record-identity-is-admitted
  ;; `demo/state-entry` mirrors `state-v1.edn`'s actual `entry` record
  ;; shape (`key: keyword, value: string, version: i64`) -- the concrete
  ;; motivating target ADR 0051's own remaining gaps named.
  (let [descriptor [:ref :demo/state-entry]
        schema [:record :demo/state-entry
                [[:key :keyword] [:value :string] [:version :i64]]]
        schemas {:demo/state-entry schema}
        kir {:format :kotoba.kir/v4 :exports ['echo] :effects #{}
             :schemas schemas
             :functions [{:name 'echo :params ['value] :param-types [descriptor]
                          :result descriptor :body 'value}]}]
    (is (true? (component/assert-scalar-slice! kir (wit/emit kir))))
    (let [artifact (component/package
                    (component-core/emit kir :wasm32-wasi-kotoba-v1)
                    kir (wit/emit kir))]
      (is (= :string-field-record-identity (:canonical-lowering artifact)))
      (is (= :wasm-component/v1 (:format artifact)))
      (is (= [0 97 115 109 13 0 1 0]
             (mapv #(bit-and (int %) 0xff) (take 8 (:bytes artifact))))))
    ;; A record with a bare string field but no scalar field at all remains
    ;; admitted too -- the identity path does not require a scalar leaf,
    ;; only "no field outside i64/f32/f64/bool/string/keyword".
    (let [label-schema [:record :demo/label [[:text :string]]]
          label-kir (-> kir
                       (assoc :schemas {:demo/label label-schema})
                       (assoc-in [:functions 0 :param-types] [[:ref :demo/label]])
                       (assoc-in [:functions 0 :result] [:ref :demo/label]))]
      (is (true? (component/assert-scalar-slice! label-kir (wit/emit label-kir))))
      (let [artifact (component/package
                      (component-core/emit label-kir :wasm32-wasi-kotoba-v1)
                      label-kir (wit/emit label-kir))]
        (is (= :string-field-record-identity (:canonical-lowering artifact)))))
    ;; A field that is itself a nested record (the ADR 0051 shape) remains
    ;; fail-closed: string/keyword leaves are only admitted at the top
    ;; level in this slice, never inside a nested field.
    (let [nested-schemas
          {:demo/inner [:record :demo/inner [[:text :string]]]
           :demo/wrapper [:record :demo/wrapper
                          [[:key :keyword] [:inner [:ref :demo/inner]]]]}
          nested-kir (-> kir
                        (assoc :schemas nested-schemas)
                        (assoc-in [:functions 0 :param-types] [[:ref :demo/wrapper]])
                        (assoc-in [:functions 0 :result] [:ref :demo/wrapper]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                            (component/assert-scalar-slice! nested-kir (wit/emit nested-kir)))))
    ;; A record schema whose sealed identity has drifted remains fail-closed.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component/assert-scalar-slice!
                           (assoc-in kir [:schemas :demo/state-entry 1] :demo/renamed)
                           (wit/emit kir))))
    ;; A computed body (anything other than a bare parameter passthrough)
    ;; remains fail-closed.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component/assert-scalar-slice!
                           (assoc-in kir [:functions 0 :body]
                                     (list 'record-get schema 'value :key))
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

(deftest scalar-record-construction-and-update-are-admitted
  (let [schema [:record :demo/point [[:x :i64] [:weight :f64] [:visible :bool]]]
        base {:format :kotoba.kir/v4 :exports ['make] :effects #{}
              :schemas {:demo/point schema}}
        construction
        (assoc base :functions
               [{:name 'make :params ['x 'weight 'visible]
                 :param-types [:i64 :f64 :bool] :result schema
                 :body (list 'record-new schema 'x 'weight 'visible)}])
        update
        (assoc base :exports ['set-weight]
               :functions
               [{:name 'set-weight :params ['point 'weight]
                 :param-types [schema :f64] :result schema
                 :body (list 'record-assoc schema 'point :weight 'weight)}])]
    (is (true? (component/assert-scalar-slice! construction (wit/emit construction))))
    (is (true? (component/assert-scalar-slice! update (wit/emit update))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical lowering"
                          (component/assert-scalar-slice!
                           (assoc-in update [:functions 0 :body]
                                     (list 'record-assoc schema 'point :weight 1))
                           (wit/emit update))))))

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
