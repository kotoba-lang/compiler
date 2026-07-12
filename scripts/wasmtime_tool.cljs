(ns scripts.wasmtime-tool
  (:require [scripts.lib :as lib]
            ["node:child_process" :as child]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def version "42.0.1")
(def releases
  {"linux-x64" {:archive "wasmtime-v42.0.1-x86_64-linux.tar.xz"
                 :sha256 "dd5253f3cb521bb094f9951c3d2c45c746b31e5723b07ce56f162ec9bab44d59"}
   "linux-arm64" {:archive "wasmtime-v42.0.1-aarch64-linux.tar.xz"
                   :sha256 "fa9b7e09f49f75c17acf2c018a4286cdbeffb4c1f3ee9e72c48b6a42c1deceda"}
   "darwin-arm64" {:archive "wasmtime-v42.0.1-aarch64-macos.tar.xz"
                    :sha256 "69c56932453483f31cac7636f850bbd3bf884eaa7315b2c3b92857a2b0c6762e"}
   "darwin-x64" {:archive "wasmtime-v42.0.1-x86_64-macos.tar.xz"
                  :sha256 "13465d6c3f35b2872f9168df19b74af6140b4f1a3a11d8a397950777ecfae858"}})

(def root (.join path lib/root ".tools" "wasmtime"))
(def executable (.join path root "wasmtime"))

(defn- verify-tool! []
  (when (.existsSync fs executable)
    (let [result (.spawnSync child executable #js ["--version"]
                             #js {:encoding "utf8" :maxBuffer 65536})]
      (when (and (zero? (or (.-status result) 70))
                 (.startsWith (or (.-stdout result) "") (str "wasmtime " version " ")))
        executable))))

(defn ensure! []
  (if-let [installed (verify-tool!)]
    (js/Promise.resolve installed)
    (let [key (str (.-platform js/process) "-" (.-arch js/process))
          {:keys [archive sha256]} (get releases key)]
      (when-not archive
        (throw (js/Error. (str "unsupported Wasmtime tool platform: " key))))
      (let [url (str "https://github.com/bytecodealliance/wasmtime/releases/download/v"
                     version "/" archive)
            staging (str root ".tmp")
            archive-path (.join path staging archive)]
        (-> (js/fetch url #js {:redirect "follow"})
            (.then (fn [response]
                     (when-not (.-ok response)
                       (throw (js/Error. (str "Wasmtime download failed: " (.-status response)))))
                     (.arrayBuffer response)))
            (.then (fn [array-buffer]
                     (let [bytes (.from js/Buffer array-buffer)
                           actual (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex"))]
                       (when-not (= sha256 actual)
                         (throw (js/Error. "Wasmtime archive digest mismatch")))
                       (.rmSync fs staging #js {:recursive true :force true})
                       (.mkdirSync fs staging #js {:recursive true})
                       (.writeFileSync fs archive-path bytes)
                       (let [result (.spawnSync child "tar" #js ["-xJf" archive-path "-C" staging]
                                                #js {:encoding "utf8" :maxBuffer 1048576})]
                         (when-not (zero? (or (.-status result) 70))
                           (throw (js/Error. (str "Wasmtime extraction failed: " (.-stderr result)))))
                         (let [directory (first (filter #(and (not= % archive)
                                                              (.isDirectory (.statSync fs (.join path staging %))))
                                                        (.readdirSync fs staging)))
                               source (.join path staging directory "wasmtime")]
                           (when-not (and directory (.isFile (.statSync fs source)))
                             (throw (js/Error. "Wasmtime archive layout rejected")))
                           (.rmSync fs root #js {:recursive true :force true})
                           (.mkdirSync fs (.dirname path root) #js {:recursive true})
                           (.renameSync fs (.join path staging directory) root)
                           (.rmSync fs staging #js {:recursive true :force true})
                           (or (verify-tool!)
                               (throw (js/Error. "installed Wasmtime identity mismatch")))))))))))))
