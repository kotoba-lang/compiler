(ns kotoba.compiler.packaging.elf64
  (:require [kotoba.compiler.artifact :as artifact]))

(def ^:private kernel-target :x86_64-aiueos-kernel-v1)
(def ^:private page-size 0x1000)
(def ^:private image-base 0x100000)
(def ^:private text-offset page-size)
(def ^:private data-offset (* 2 page-size))
(def ^:private context-size 80)

(defn- le [n width]
  (mapv #(bit-and (unsigned-bit-shift-right (long n) (* 8 %)) 0xff)
        (range width)))

(defn- padded [bytes size]
  (when (> (count bytes) size)
    (throw (ex-info "ELF64 region exceeds its allocation"
                    {:size size :actual (count bytes)})))
  (into (vec bytes) (repeat (- size (count bytes)) 0)))

(defn- elf-header [entry program-header-count section-offset section-count]
  (vec (concat
        [0x7f 0x45 0x4c 0x46 2 1 1 0] (repeat 8 0)
        (le 2 2)                         ; ET_EXEC
        (le 0x3e 2)                      ; EM_X86_64
        (le 1 4)
        (le entry 8)
        (le 64 8)                        ; program headers follow ELF header
        (le section-offset 8)
        (le 0 4)
        (le 64 2) (le 56 2) (le program-header-count 2)
        (le 64 2) (le section-count 2) (le 3 2)))) ; .shstrtab index

(defn- program-header [flags offset address file-size memory-size]
  (vec (concat (le 1 4) (le flags 4)     ; PT_LOAD
               (le offset 8) (le address 8) (le address 8)
               (le file-size 8) (le memory-size 8) (le page-size 8))))

(defn- section-header [name type flags address offset size alignment]
  (vec (concat (le name 4) (le type 4) (le flags 8) (le address 8)
               (le offset 8) (le size 8) (le 0 4) (le 0 4)
               (le alignment 8) (le 0 8))))

(defn- entry-shim [main-address context-address]
  ;; lea r9,[rip+context]; call Kotoba entry; cli; hlt forever. The static
  ;; context supplies fuel and an empty capability bitmap without a host ABI.
  (let [shim-address (+ image-base text-offset)
        after-lea (+ shim-address 7)
        after-call (+ shim-address 12)]
    (vec (concat [0x4c 0x8d 0x0d] (le (- context-address after-lea) 4)
                 [0xe8] (le (- main-address after-call) 4)
                 [0xfa 0xf4 0xeb 0xfd]))))

(defn package-kernel
  "Package a sealed aiueos kernel artifact as a freestanding ELF64 ET_EXEC.
  The returned byte vector has no interpreter, dynamic section, or host imports."
  [artifact]
  (when-not (artifact/valid-seal? artifact)
    (throw (ex-info "ELF64 kernel packaging requires a sealed artifact" {})))
  (when-not (= kernel-target (:target artifact))
    (throw (ex-info "ELF64 kernel packaging requires the aiueos kernel target"
                    {:target (:target artifact)})))
  (when-not (and (= :none (get-in artifact [:target-profile :runtime]))
                 (false? (get-in artifact [:target-profile :ambient-syscalls])))
    (throw (ex-info "ELF64 kernel packaging requires a freestanding profile"
                    {:target-profile (:target-profile artifact)})))
  (let [source-entry (get-in artifact [:program :entry])
        export (get-in artifact [:exports source-entry])]
    (when-not export
      (throw (ex-info "Kotoba kernel entry is not exported" {:entry source-entry})))
    (let [entry-address (+ image-base text-offset)
          context-address (+ image-base data-offset)
          shim (entry-shim (+ entry-address 16 (:offset export)) context-address)
          text (into shim (:code artifact))
          context (into (vec (repeat 8 0)) (concat (le 256 8) (repeat (- context-size 16) 0)))
          names (mapv int (.getBytes "\u0000.text\u0000.data\u0000.shstrtab\u0000" "UTF-8"))
          names-offset (+ data-offset context-size)
          section-offset (+ names-offset (count names)
                            (mod (- 8 (mod (+ names-offset (count names)) 8)) 8))
          sections [(vec (repeat 64 0))
                    (section-header 1 1 0x6 entry-address text-offset (count text) 16)
                    (section-header 7 1 0x3 context-address data-offset context-size 8)
                    (section-header 13 3 0 0 names-offset (count names) 1)]
          header (elf-header entry-address 2 section-offset (count sections))
          phdrs (concat (program-header 0x5 text-offset entry-address (count text) (count text))
                        (program-header 0x6 data-offset context-address context-size context-size))
          before-text (padded (concat header phdrs) text-offset)
          before-data (padded (concat before-text text) data-offset)
          before-sections (padded (concat before-data context names) section-offset)
          bytes (vec (concat before-sections (mapcat identity sections)))]
      {:format :elf64/v1
       :target kernel-target
       :entry :aiueos_kernel_entry
       :source-entry source-entry
       :entry-address entry-address
       :sections [:text :data :shstrtab]
       :imports []
       :interpreter nil
       :bytes bytes})))
