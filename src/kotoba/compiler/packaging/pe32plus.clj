(ns kotoba.compiler.packaging.pe32plus
  (:require [kotoba.compiler.artifact :as artifact]))

(def ^:private firmware-target :x86_64-aiueos-uefi-v1)
(def ^:private file-alignment 0x200)
(def ^:private section-alignment 0x1000)
(def ^:private image-base 0x400000)
(def ^:private text-rva 0x1000)
(def ^:private data-rva 0x2000)
(def ^:private reloc-rva 0x3000)
(def ^:private text-offset 0x200)
(def ^:private context-size 80)

(defn- le [n width]
  (mapv #(bit-and (unsigned-bit-shift-right (long n) (* 8 %)) 0xff)
        (range width)))

(defn- align [n alignment]
  (* alignment (quot (+ n (dec alignment)) alignment)))

(defn- pad-to [bytes size]
  (when (> (count bytes) size)
    (throw (ex-info "PE32+ region exceeds its allocation"
                    {:size size :actual (count bytes)})))
  (into (vec bytes) (repeat (- size (count bytes)) 0)))

(defn- section-name [name]
  (pad-to (mapv int (.getBytes name "US-ASCII")) 8))

(defn- section-header [name virtual-size rva raw-size raw-offset characteristics]
  (vec (concat (section-name name) (le virtual-size 4) (le rva 4)
               (le raw-size 4) (le raw-offset 4)
               (repeat 12 0) (le characteristics 4))))

(defn- entry-shim [source-rva context-rva]
  ;; UEFI invokes this boundary with the Microsoft x64 ABI. Reserve its 32-byte
  ;; shadow space plus alignment, initialize Kotoba's hidden r9 context, call a
  ;; zero-arity internal entry, and return its rax as EFI_STATUS. This is an ABI
  ;; adapter, not a claim that the internal Kotoba lowering is Microsoft x64.
  (let [after-lea (+ text-rva 11)
        after-call (+ text-rva 16)]
    (vec (concat [0x48 0x83 0xec 0x28
                  0x4c 0x8d 0x0d]
                 (le (- context-rva after-lea) 4)
                 [0xe8] (le (- source-rva after-call) 4)
                 [0x48 0x83 0xc4 0x28 0xc3]))))

(defn- optional-header [entry-rva text-size initialized-size image-size reloc-size]
  (let [fixed (vec (concat
                    (le 0x20b 2) [0 0]                         ; PE32+, linker version
                    (le (align text-size file-alignment) 4)
                    (le initialized-size 4) (le 0 4)
                    (le entry-rva 4) (le text-rva 4)
                    (le image-base 8)
                    (le section-alignment 4) (le file-alignment 4)
                    (le 2 2) (le 0 2) (le 0 2) (le 0 2)       ; OS/image versions
                    (le 2 2) (le 0 2)                         ; subsystem version
                    (le 0 4) (le image-size 4) (le 0x200 4)
                    (le 0 4)                                  ; checksum
                    (le 10 2)                                 ; EFI application
                    (le 0x160 2)                              ; ASLR, NX, high entropy VA
                    (le 0x100000 8) (le 0x1000 8)             ; stack
                    (le 0x100000 8) (le 0x1000 8)             ; heap
                    (le 0 4) (le 16 4)))                      ; loader flags, directories
        directories (mapcat identity
                            (map-indexed
                             (fn [index _]
                               (if (= index 5)
                                 (concat (le reloc-rva 4) (le reloc-size 4))
                                 (repeat 8 0)))
                             (range 16)))]
    (vec (concat fixed directories))))

(defn package-efi
  "Package a sealed aiueos firmware artifact as an import-free PE32+ EFI image.
  The Microsoft x64 boundary supports a zero-arity Kotoba entry returning an
  EFI_STATUS-sized integer; internal functions retain the compiler context ABI."
  [artifact]
  (when-not (artifact/valid-seal? artifact)
    (throw (ex-info "PE32+ EFI packaging requires a sealed artifact" {})))
  (when-not (= firmware-target (:target artifact))
    (throw (ex-info "PE32+ EFI packaging requires the aiueos UEFI target"
                    {:target (:target artifact)})))
  (when-not (and (= :none (get-in artifact [:target-profile :runtime]))
                 (false? (get-in artifact [:target-profile :ambient-syscalls])))
    (throw (ex-info "PE32+ EFI packaging requires a freestanding profile"
                    {:target-profile (:target-profile artifact)})))
  (let [source-entry (get-in artifact [:program :entry])
        export (get-in artifact [:exports source-entry])]
    (when-not export
      (throw (ex-info "Kotoba firmware entry is not exported" {:entry source-entry})))
    (when-not (zero? (:arity export))
      (throw (ex-info "UEFI boundary requires a zero-arity Kotoba entry"
                      {:entry source-entry :arity (:arity export)})))
    (let [shim-size 21
          source-rva (+ text-rva shim-size (:offset export))
          shim (entry-shim source-rva data-rva)
          text (into shim (:code artifact))
          context (into (vec (repeat 8 0))
                        (concat (le 256 8) (repeat (- context-size 16) 0)))
          text-raw-size (align (count text) file-alignment)
          data-offset (+ text-offset text-raw-size)
          data-raw-size (align context-size file-alignment)
          reloc-offset (+ data-offset data-raw-size)
          ;; A legal relocation directory containing two IMAGE_REL_BASED_ABSOLUTE
          ;; padding entries. All image references are relative, so no fixups exist.
          reloc (vec (concat (le 0 4) (le 12 4) (le 0 2) (le 0 2)))
          reloc-raw-size (align (count reloc) file-alignment)
          optional (optional-header text-rva (count text)
                                    (+ data-raw-size reloc-raw-size) 0x4000 (count reloc))
          coff (vec (concat (le 0x8664 2) (le 3 2) (repeat 12 0)
                            (le 0xf0 2) (le 0x22 2)))
          sections (concat
                    (section-header ".text" (count text) text-rva text-raw-size
                                    text-offset 0x60000020)
                    (section-header ".data" context-size data-rva data-raw-size
                                    data-offset 0xc0000040)
                    (section-header ".reloc" (count reloc) reloc-rva reloc-raw-size
                                    reloc-offset 0x42000040))
          dos (assoc (vec (repeat 0x80 0)) 0 0x4d 1 0x5a
                     0x3c 0x80)
          headers (pad-to (concat dos [0x50 0x45 0 0] coff optional sections) text-offset)
          bytes (vec (concat headers (pad-to text text-raw-size)
                             (pad-to context data-raw-size)
                             (pad-to reloc reloc-raw-size)))]
      {:format :pe32+/v1
       :target firmware-target
       :entry :efi_main
       :source-entry source-entry
       :entry-rva text-rva
       :entry-contract :microsoft-x64-zero-arity-efi-status-v1
       :sections [:text :data :reloc]
       :imports []
       :relocations {:format :pe-base-relocation/v1 :fixups 0 :position-independent true}
       :bytes bytes})))
