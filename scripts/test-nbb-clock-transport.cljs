(ns scripts.test-nbb-clock-transport
  "Launcher for `test/nbb/clock-transport.cljs` (ADR 0073), mirroring
  `scripts/test-nbb-wasm32.cljs`'s own pattern exactly: `clojure -Spath`
  resolves the full JVM classpath -- including the `kotoba.security.abac`
  git dependency `kotoba.compiler.admission` needs, which a literal
  `--classpath .:src` cannot see -- and that resolved classpath, plus this
  repo's own root, is handed to a child `nbb` process actually running the
  test."
  (:require [scripts.lib :as lib]
            ["node:child_process" :as child]
            ["node:path" :as path]))

(let [resolved (lib/run "clojure" ["-Spath"])
      nbb-cli (lib/join lib/root "node_modules" "nbb" "cli.js")
      classpath (str lib/root (.-delimiter path) (.trim (:stdout resolved)))
      result (.spawnSync child js/process.execPath
                         (clj->js [nbb-cli "--classpath" classpath
                                  (lib/join lib/root "test" "nbb" "clock-transport.cljs")])
                         #js {:cwd lib/root :stdio "inherit" :env js/process.env})]
  (when (.-error result) (throw (.-error result)))
  (.exit js/process (or (.-status result) 70)))
