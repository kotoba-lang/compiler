(ns kotoba.compiler.canonical-abi-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.canonical-abi :as canonical]))

(deftest scalar-and-bounded-string-layouts-are-closed
  (is (= {:descriptor :i64 :size 8 :alignment 8 :flat [:i64]}
         (canonical/layout :i64)))
  (is (= {:descriptor :string :size 8 :alignment 4 :flat [:i32 :i32]
          :encoding :utf8 :max-bytes 65536
          :validation [:checked-pointer-range :valid-utf8]}
         (canonical/layout :string)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no qualified Canonical ABI layout"
                        (canonical/layout [:vector :i64]))))

(deftest string-export-uses-standard32-indirect-result
  (is (= {:profile :component-model/standard32-v1
          :function 'echo
          :parameters [{:name 'value
                        :layout {:descriptor :string :size 8 :alignment 4
                                 :flat [:i32 :i32] :encoding :utf8
                                 :max-bytes 65536
                                 :validation [:checked-pointer-range :valid-utf8]}}]
          :core-params [:i32 :i32]
          :result-layout {:descriptor :string :size 8 :alignment 4
                          :flat [:i32 :i32] :encoding :utf8 :max-bytes 65536
                          :validation [:checked-pointer-range :valid-utf8]}
          :indirect-result? true
          :core-results [:i32]
          :post-return-params [:i32]}
         (canonical/export-plan
          {:name 'echo :params ['value] :param-types [:string] :result :string}))))

(deftest bounded-keyword-layout-is-closed
  (is (= {:descriptor :keyword :size 8 :alignment 4 :flat [:i32 :i32]
          :encoding :utf8 :max-bytes 512
          :validation [:checked-pointer-range :valid-utf8]}
         (canonical/layout :keyword))))

(deftest string-and-keyword-record-fields-flatten-to-pointer-length-leaves
  (let [descriptor [:ref :demo/state-entry]
        schemas {:demo/state-entry
                 [:record :demo/state-entry
                  [[:key :keyword] [:value :string] [:version :i64]]]}
        value (canonical/layout descriptor schemas)]
    (is (= 24 (:size value)))
    (is (= 8 (:alignment value)))
    (is (= [:i32 :i32 :i32 :i32 :i64] (:flat value)))
    (is (= [0 8 16] (mapv :offset (:fields value))))
    (is (= [{:offset 0 :descriptor :keyword :max-bytes 512}
            {:offset 8 :descriptor :string :max-bytes 65536}
            {:offset 16 :descriptor :i64}]
           (canonical/layout-leaves value)))
    (is (= [:i32]
           (:core-results
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))
    (is (= [:i32 :i32 :i32 :i32 :i64]
           (:core-params
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))))

(deftest named-scalar-record-layout-preserves-identity-offsets-and-flattening
  (let [descriptor [:ref :demo/point]
        schemas {:demo/point
                 [:record :demo/point [[:x :i64] [:weight :f64] [:visible :bool]]]}
        value (canonical/layout descriptor schemas)]
    (is (= 24 (:size value)))
    (is (= 8 (:alignment value)))
    (is (= [:i64 :f64 :i32] (:flat value)))
    (is (= [0 8 16] (mapv :offset (:fields value))))
    (is (= [:i32]
           (:core-results
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matching schema identity"
                          (canonical/layout descriptor
                                            {:demo/point [:record :demo/other [[:x :i64]]]})))))

(deftest one-level-nested-record-layout-flattens-recursively-with-absolute-offsets
  (let [descriptor [:ref :demo/outer]
        schemas {:demo/inner [:record :demo/inner [[:code :i64] [:ratio :f64]]]
                 :demo/outer [:record :demo/outer
                              [[:id :i64] [:inner [:ref :demo/inner]] [:active :bool]]]}
        outer-layout (canonical/layout descriptor schemas)
        inner-layout (canonical/layout [:ref :demo/inner] schemas)]
    (is (= 32 (:size outer-layout)))
    (is (= 8 (:alignment outer-layout)))
    (is (= [:i64 :i64 :f64 :i32] (:flat outer-layout)))
    (is (= [0 8 24] (mapv :offset (:fields outer-layout))))
    (is (= inner-layout (get-in outer-layout [:fields 1 :layout])))
    (is (= [{:offset 0 :descriptor :i64}
            {:offset 8 :descriptor :i64}
            {:offset 16 :descriptor :f64}
            {:offset 24 :descriptor :bool}]
           (canonical/layout-leaves outer-layout)))
    (is (= [:i32]
           (:core-results
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))
    (is (= [:i64 :i64 :f64 :i32]
           (:core-params
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"recursive schema has no bounded"
                          (canonical/layout [:ref :demo/self]
                                            {:demo/self [:record :demo/self
                                                         [[:child [:ref :demo/self]]]]})))))

(deftest variant-with-record-and-scalar-cases-flattens-with-joined-core-types
  (let [descriptor [:ref :demo/outcome]
        schemas {:demo/entry [:record :demo/entry
                              [[:code :i64] [:ratio :f64] [:present :bool]]]
                 :demo/short [:record :demo/short [[:code :i64] [:flag :bool]]]
                 :demo/outcome [:variant :demo/outcome
                                [[:found [:ref :demo/entry]]
                                 [:missing :bool]
                                 [:failed :f32]
                                 [:short [:ref :demo/short]]]]}
        value (canonical/layout descriptor schemas)]
    (is (= :variant (:kind value)))
    (is (= 1 (:discriminant-size value)))
    (is (= 8 (:payload-offset value)))
    (is (= 8 (:alignment value)))
    (is (= 32 (:size value)))
    (is (= [:found :missing :failed :short] (mapv :tag (:cases value))))
    ;; Position 0 is dominated by :found's and :short's i64 leading field;
    ;; position 1, touched only by :found's f64 field until :short's trailing
    ;; bool field also reaches it, is forced from f64 to i64 by that mixed
    ;; join; position 2 is touched only by :found's own bool field, so it
    ;; stays i32 with no other case to force a join at all.
    (is (= [:i32 :i64 :i64 :i32] (:flat value)))
    (is (= [:i32]
           (:core-results
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))
    (is (= [:i32 :i64 :i64 :i32]
           (:core-params
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))))

(deftest variant-with-only-bool-and-f32-cases-joins-to-i32
  (let [descriptor [:ref :demo/flag-or-ratio]
        schemas {:demo/flag-or-ratio [:variant :demo/flag-or-ratio
                                      [[:urgent :bool] [:weight :f32]]]}
        value (canonical/layout descriptor schemas)]
    ;; Neither case ever touches i64/f64, so the Component Model spec's
    ;; special-cased i32/f32 join keeps the shared payload position at i32
    ;; instead of widening to i64 -- the only join outcome besides "both
    ;; sides already agree" and "widen to i64".
    (is (= [:i32 :i32] (:flat value)))
    (is (= 1 (:discriminant-size value)))
    ;; Payload area is aligned/sized to the wider case (f32, 4 bytes), so
    ;; the 1-byte discriminant is padded up to offset 4 before it.
    (is (= 4 (:payload-offset value)))
    (is (= 8 (:size value)))
    (is (= 4 (:alignment value)))))

(deftest variant-with-string-keyword-record-case-flattens-payload-fields-generically
  ;; `layout*`/`variant-layout`/`variant-flatten-payload` needed no code
  ;; changes for ADR 0054: a case's own `layout*` call already resolves a
  ;; record-with-string/keyword-fields payload the same way a top-level
  ;; string-field record already does (ADR 0053), so its own `:flat`
  ;; already carries the correct `[:i32 :i32]` pointer+length pair per
  ;; string-like field before this test is even written -- this test exists
  ;; to make that generic behavior explicit and pinned, not to exercise new
  ;; canonical-abi.cljc logic. `demo/entry` mirrors `state-v1`'s own `entry`
  ;; shape exactly (`key: keyword, value: string, version: i64`).
  (let [descriptor [:ref :demo/state-result]
        schemas {:demo/entry [:record :demo/entry
                              [[:key :keyword] [:value :string] [:version :i64]]]
                 :demo/state-result [:variant :demo/state-result
                                     [[:found [:ref :demo/entry]] [:missing :bool]]]}
        value (canonical/layout descriptor schemas)]
    (is (= :variant (:kind value)))
    (is (= [:found :missing] (mapv :tag (:cases value))))
    ;; found's own flat is [i32 i32 i32 i32 i64] (key ptr/len, value
    ;; ptr/len, version); missing's own flat is [i32] and only ever touches
    ;; position 0, joining i32/i32 -> i32 there and leaving positions 1-4
    ;; exactly as found's own record layout already produced them. The
    ;; leading discriminant i32 is prepended on top of this joined sequence.
    (is (= [:i32 :i32 :i32 :i32 :i32 :i64] (:flat value)))
    (is (= 1 (:discriminant-size value)))
    (is (= 8 (:payload-offset value)))
    (is (= 8 (:alignment value)))
    (is (= 32 (:size value)))
    (is (= [{:offset 0 :descriptor :keyword :max-bytes 512}
            {:offset 8 :descriptor :string :max-bytes 65536}
            {:offset 16 :descriptor :i64}]
           (canonical/layout-leaves (:layout (first (:cases value))))))
    (is (= [:i32 :i32 :i32 :i32 :i32 :i64]
           (:core-params
            (canonical/export-plan
             {:name 'echo :params ['value] :param-types [descriptor] :result descriptor}
             schemas))))))

(deftest variant-reference-requires-matching-sealed-schema-identity
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"variant reference has no matching schema identity"
                        (canonical/layout [:ref :demo/outcome]
                                          {:demo/outcome [:variant :demo/other
                                                          [[:a :bool]]]}))))

(deftest variant-schema-recursion-through-a-case-payload-is-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"recursive schema has no bounded"
                        (canonical/layout [:ref :demo/self]
                                          {:demo/self [:variant :demo/self
                                                       [[:child [:ref :demo/self]]]]}))))
