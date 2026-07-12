(ns scripts.lib
  (:require [clojure.string :as str]
            ["node:child_process" :as child]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def root (.resolve path (.dirname path *file*) ".."))
(defn ensure! [condition message] (when-not condition (throw (js/Error. message))))
(defn temp-dir [prefix] (.mkdtempSync fs (.join path (.tmpdir os) prefix)))
(defn remove-tree! [directory] (.rmSync fs directory #js {:recursive true :force true}))
(defn join [& values] (.apply (.-join path) path (clj->js values)))
(defn read-text [file] (.readFileSync fs file "utf8"))
(defn write-text! [file value] (.writeFileSync fs file value))
(defn sha256 [file]
  (let [hash (.createHash crypto "sha256")]
    (.update hash (.readFileSync fs file))
    (.digest hash "hex")))

(defn run
  ([command args] (run command args {}))
  ([command args {:keys [cwd env allow-failure? max-buffer]
                  :or {cwd root max-buffer (* 4 1024 1024)}}]
   (let [result (.spawnSync child command (clj->js args)
                            #js {:cwd cwd :encoding "utf8" :maxBuffer max-buffer
                                 :env (js/Object.assign #js {} js/process.env (clj->js env))})
         status (or (.-status result) 70)
         value {:status status :stdout (or (.-stdout result) "")
                :stderr (or (.-stderr result) "")}]
     (when (and (.-error result) (not allow-failure?)) (throw (.-error result)))
     (when (and (not allow-failure?) (not= 0 status))
       (throw (js/Error. (str "command failed: " command " " (str/join " " args)
                              "\n" (:stdout value) (:stderr value)))))
     value)))
