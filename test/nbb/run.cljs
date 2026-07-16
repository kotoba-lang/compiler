(ns test.nbb.run
  "Repeatable, JVM-free regression test for the nbb-native wasm32
  compile/check path (`kotoba.compiler.nbb.cli`, spawned by `bin/kotoba` for
  `wasm32*` targets -- see its own comment). For every case in `cases.cljs`,
  compiles the fixture through the SAME frontend/admission/ir/wasm-backend
  pipeline `kotoba.compiler.nbb.cli` uses, and asserts the bytes are
  IDENTICAL to the checked-in golden file `generate-golden.cljs` authored
  once via the JVM path -- the strongest verification available for a
  compiler with reproducible-build gates: not 'does it look right', but
  'is it the exact same artifact the reference implementation produces'.
  Run from the repo root: `nbb test/nbb/run.cljs`."
  (:require ["node:fs" :as fs]
            [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.admission :as admission]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.kotoba-reader :as kr]
            [test.nbb.cases :as cases]))

(defn- compile-case [{:keys [source target policy]}]
  (let [src (.readFileSync fs source "utf8")
        hir (frontend/analyze src)
        policy-value (if policy (first (kr/read-forms (.readFileSync fs policy "utf8"))) {})
        _ (admission/check hir policy-value)
        kir (ir/lower hir)]
    (wasm/emit kir (get cases/target-keyword target))))

(defn- bytes= [^js a ^js b]
  (and (= (.-length a) (.-length b))
       (every? true? (map = (js/Array.from a) (js/Array.from b)))))

(let [results
      (for [{:keys [name] :as case} cases/cases]
        (let [golden-path (str "test/nbb/golden/" name ".wasm")]
          (try
            (let [actual (compile-case case)
                  golden (js/Uint8Array. (fs/readFileSync golden-path))
                  ok? (bytes= actual golden)]
              {:name name :ok? ok?
               :detail (when-not ok? (str "length " (.-length actual) " vs golden " (.-length golden)))})
            (catch :default e
              {:name name :ok? false :detail (str "threw: " (.-message e))}))))
      failures (remove :ok? results)]
  (doseq [{:keys [name ok? detail]} results]
    (println (if ok? "PASS" "FAIL") name (or detail "")))
  (println (count results) "cases," (count failures) "failed")
  (when (seq failures) (.exit js/process 1)))
