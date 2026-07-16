(ns test.nbb.generate-golden
  "One-time authoring tool: compiles every `cases.cljs` fixture via the JVM
  path (`clojure -M:run compile ...`) and writes the result into
  `test/nbb/golden/<name>.wasm`, checked into git. `run.cljs` then compares
  the nbb-native path's OWN output against these checked-in files on every
  future run -- so routine test runs never need `clojure`/the JVM at all;
  only re-authoring the golden files (after a genuine, intentional change to
  compiled output) does. Run from the repo root: `nbb test/nbb/generate-golden.cljs`."
  (:require ["node:child_process" :as child]
            ["node:fs" :as fs]
            [test.nbb.cases :as cases]))

(doseq [{:keys [name source target policy]} cases/cases]
  (let [output (str "test/nbb/golden/" name ".wasm")
        args (cond-> ["-M:run" "compile" source "--target" target "--output" output]
               policy (concat ["--policy" policy]))
        result (.spawnSync child "clojure" (clj->js args) #js {:stdio "inherit"})]
    (if (zero? (or (.-status result) 1))
      (println "generated" output)
      (do (.error js/console "FAILED to generate golden fixture for" name)
          (.exit js/process 1)))))
