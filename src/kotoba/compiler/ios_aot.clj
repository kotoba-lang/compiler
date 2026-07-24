(ns kotoba.compiler.ios-aot
  (:require [clojure.string :as str]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.verifier :as verifier])
  (:import [java.nio.charset StandardCharsets]))

(def ^:private ios-target :aarch64-ios-kotoba-v1)

(defn- reject! [message data]
  (throw (ex-info message (merge {:phase :verify} data))))

(defn- byte-lines [code]
  (->> code
       (partition-all 16)
       (map (fn [line]
              (str "  .byte "
                   (str/join "," (map #(format "0x%02x" (bit-and (int %) 0xff)) line)))))
       (str/join "\n")))

(defn package
  "Verify an iOS KEXE and return canonical static Mach-O assembly plus manifest."
  [kexe entry]
  (verifier/verify-artifact! kexe)
  (when-not (= ios-target (:target kexe))
    (reject! "iOS AOT packaging requires the explicit iOS target"
             {:target (:target kexe)}))
  (when-not (symbol? entry)
    (reject! "iOS AOT entry must be a symbol" {:entry entry}))
  (let [export (get (:exports kexe) entry)]
    (when-not export
      (reject! "iOS AOT entry is not exported" {:entry entry}))
    (let [code (:code kexe)
          offset (:offset export)
          assembly (str ".section __TEXT,__text,regular,pure_instructions\n"
                        ".p2align 2\n"
                        ".globl _kotoba_ios_code_start\n"
                        ".globl _kotoba_ios_code_end\n"
                        ".globl _kotoba_ios_entry\n"
                        "_kotoba_ios_code_start:\n"
                        (byte-lines code) "\n"
                        "_kotoba_ios_code_end:\n"
                        ".set _kotoba_ios_entry, _kotoba_ios_code_start + " offset "\n"
                        ".no_dead_strip _kotoba_ios_code_start\n"
                        ".no_dead_strip _kotoba_ios_code_end\n"
                        ".no_dead_strip _kotoba_ios_entry\n"
                        ".section __TEXT,__const\n"
                        ".globl _kotoba_ios_target_profile\n"
                        "_kotoba_ios_target_profile:\n"
                        ".asciz \"aarch64-ios-kotoba-v1\"\n")
          bytes (.getBytes assembly StandardCharsets/UTF_8)
          manifest {:format :kotoba.ios-aot/v1
                    :target ios-target
                    :target-profile (:target-profile kexe)
                    :artifact-sha256 (:sha256 kexe)
                    :code-sha256 (artifact/sha256 code)
                    :entry {:name entry :offset offset :arity (:arity export)}
                    :assembly-sha256 (artifact/sha256 assembly)}]
      {:assembly bytes :manifest manifest})))
