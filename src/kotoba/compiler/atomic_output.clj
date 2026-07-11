(ns kotoba.compiler.atomic-output
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files OpenOption Path Paths StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn- reject! [message data cause]
  (throw (ex-info message (merge {:phase :output} data) cause)))

(defn- target-path [path]
  (when-not (and (string? path) (seq path))
    (reject! "output path is required" {} nil))
  (let [target (.normalize (.toAbsolutePath (Paths/get path (make-array String 0))))
        parent (.getParent target)]
    (when-not (and parent (Files/isDirectory parent (make-array java.nio.file.LinkOption 0)))
      (reject! "output parent must be a directory" {:path path} nil))
    target))

(defn- set-private-permissions! [^Path path]
  (try
    (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException error
      (reject! "private output permissions are unsupported" {:path (str path)} error))))

(defn- set-executable-permissions! [^Path path]
  (try
    (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rwx------"))
    (catch UnsupportedOperationException error
      (reject! "executable output permissions are unsupported" {:path (str path)} error))))

(defn write-bytes!
  ([path bytes] (write-bytes! path bytes {}))
  ([path bytes {:keys [private? executable?] :or {private? false executable? false}}]
   (when-not (instance? (Class/forName "[B") bytes)
     (reject! "output must be a byte array" {:path path} nil))
   (let [target (target-path path)
         parent (.getParent target)
         temporary (Files/createTempFile parent ".kotoba-" ".tmp"
                                         (make-array FileAttribute 0))]
     (try
       (cond executable? (set-executable-permissions! temporary)
             private? (set-private-permissions! temporary))
       (with-open [channel (FileChannel/open
                            temporary
                            (into-array OpenOption
                                        [StandardOpenOption/WRITE
                                         StandardOpenOption/TRUNCATE_EXISTING]))]
         (let [buffer (ByteBuffer/wrap bytes)]
           (while (.hasRemaining buffer) (.write channel buffer)))
         (.force channel true))
       (Files/move temporary target
                   (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                           StandardCopyOption/REPLACE_EXISTING]))
       (str target)
       (catch clojure.lang.ExceptionInfo error (throw error))
       (catch Exception error
         (reject! "atomic output failed" {:path path} error))
       (finally (Files/deleteIfExists temporary))))))

(defn write-edn!
  ([path value] (write-edn! path value {}))
  ([path value options]
   (write-bytes! path (.getBytes (pr-str value) StandardCharsets/UTF_8) options)))
