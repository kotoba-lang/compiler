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

(defn- optional-header [entry-rva text-size initialized-size image-size reloc-address reloc-size]
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
                                 (concat (le reloc-address 4) (le reloc-size 4))
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
                                    (+ data-raw-size reloc-raw-size) 0x4000 reloc-rva (count reloc))
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

(defn- read-le [bytes offset width]
  (reduce (fn [value index]
            (+ value (bit-shift-left (long (nth bytes (+ offset index))) (* 8 index))))
          0 (range width)))

(defn- code-size [tokens]
  (reduce (fn [size token]
            (+ size (cond
                      (and (map? token) (:label token)) 0
                      (and (map? token) (:rel32 token)) 4
                      :else 1)))
          0 tokens))

(defn- finalize-loader [tokens external-labels]
  (let [labels (loop [remaining tokens position text-rva out external-labels]
                 (if-let [token (first remaining)]
                   (if-let [label (and (map? token) (:label token))]
                     (recur (next remaining) position (assoc out label position))
                     (recur (next remaining) (inc position) out))
                   out))]
    (loop [remaining tokens position text-rva out []]
      (if-let [token (first remaining)]
        (cond
          (and (map? token) (:label token))
          (recur (next remaining) position out)

          (and (map? token) (:rel32 token))
          (let [target (get labels (:rel32 token))]
            (when-not target (throw (ex-info "unknown UEFI loader label" {:label (:rel32 token)})))
            (recur (next remaining) (+ position 4)
                   (into out (le (- target (+ position 4)) 4))))

          :else (recur (next remaining) (inc position) (conj out token)))
        (vec out)))))

(defn- rip [label] {:rel32 label})
(defn- label [name] {:label name})

(defn- allocate-segment [address-label pages source-label size]
  (concat
   [0xb9 2 0 0 0 0xba 2 0 0 0 0x41 0xb8] (le pages 4)
   [0x4c 0x8d 0x0d] [(rip address-label)]
   [0x41 0xff 0x56 0x28 0x48 0x85 0xc0 0x0f 0x85] [(rip :fail)]
   [0x48 0x8b 0x0d] [(rip address-label)]
   [0x48 0x8d 0x15] [(rip source-label)]
   [0x41 0xb8] (le size 4)
   [0x41 0xff 0x96 0x60 0x01 0x00 0x00]))

(defn package-embedded-kernel
  "Generate a position-independent PE32+ UEFI transition loader around a
  compiler-produced aiueos kernel ELF. No C object, CRT, import, or linker is
  involved. The current hard-flip contract admits exactly two bounded PT_LOAD
  segments and transfers control after ExitBootServices."
  [kernel]
  (let [kernel (vec kernel)]
    (when-not (and (= [0x7f 0x45 0x4c 0x46] (subvec kernel 0 4))
                   (= 2 (read-le kernel 16 2)) (= 0x3e (read-le kernel 18 2)))
      (throw (ex-info "embedded kernel must be x86-64 ET_EXEC" {})))
    (let [entry (read-le kernel 24 8)
          phoff (read-le kernel 32 8)
          phentsize (read-le kernel 54 2)
          phnum (read-le kernel 56 2)
          segments (mapv (fn [index]
                           (let [offset (+ phoff (* index phentsize))]
                             {:type (read-le kernel offset 4)
                              :flags (read-le kernel (+ offset 4) 4)
                              :offset (read-le kernel (+ offset 8) 8)
                              :paddr (read-le kernel (+ offset 24) 8)
                              :filesz (read-le kernel (+ offset 32) 8)
                              :memsz (read-le kernel (+ offset 40) 8)}))
                         (range phnum))]
      (when-not (and (= 2 phnum) (= 56 phentsize)
                     (every? #(and (= 1 (:type %)) (pos? (:filesz %))
                                   (= (:filesz %) (:memsz %))
                                   (zero? (mod (:paddr %) 4096))
                                   (<= (+ (:offset %) (:filesz %)) (count kernel))) segments)
                     (= [5 6] (mapv :flags segments)))
        (throw (ex-info "embedded kernel PT_LOAD contract rejected" {:segments segments})))
      (let [data-addresses [0 8]
            variables-size 56
            embedded-offset (align variables-size 16)
            ;; Build once with provisional external RVAs; instruction length is
            ;; independent of displacement values.
            segment-tokens (mapcat (fn [index segment]
                                     (allocate-segment (keyword (str "address" index))
                                      (quot (+ (:memsz segment) 4095) 4096)
                                      (keyword (str "segment" index)) (:filesz segment)))
                                   (range) segments)
            tokens (vec (concat
                    [0x41 0x54 0x41 0x55 0x41 0x56 0x41 0x57
                     0x48 0x83 0xec 0x28 0x49 0x89 0xcc 0x49 0x89 0xd5
                     0x4c 0x8b 0x72 0x60]
                    segment-tokens
                    ;; AllocatePool(EfiLoaderData,128 KiB,&map-pointer).
                    [0xb9 2 0 0 0 0xba 0 0 2 0 0x4c 0x8d 0x05] [(rip :map-pointer)]
                    [0x41 0xff 0x56 0x40 0x48 0x85 0xc0 0x0f 0x85] [(rip :fail)]
                    [(label :get-map)]
                    [0x48 0xc7 0x05] [(rip :map-size)] [0 0 2 0]
                    [0x48 0x8d 0x0d] [(rip :map-size)]
                    [0x48 0x8b 0x15] [(rip :map-pointer)]
                    [0x4c 0x8d 0x05] [(rip :map-key)]
                    [0x4c 0x8d 0x0d] [(rip :descriptor-size)]
                    [0x48 0x8d 0x05] [(rip :descriptor-version)]
                    [0x48 0x89 0x44 0x24 0x20 0x41 0xff 0x56 0x38
                     0x48 0x85 0xc0 0x0f 0x85] [(rip :fail)]
                    [0x4c 0x89 0xe1 0x48 0x8b 0x15] [(rip :map-key)]
                    [0x41 0xff 0x96 0xe8 0x00 0x00 0x00 0x48 0x85 0xc0
                     0x0f 0x85] [(rip :get-map)]
                    [0x31 0xff 0x48 0xb8] (le entry 8) [0xff 0xd0]
                    [(label :fail)]
                    [0x66 0xba 0xe9 0x00 0xb0 0x46 0xee
                     0x66 0xba 0xf4 0x00 0xb8 0x7f 0x00 0x00 0x00 0xef
                     0xfa 0xf4 0xeb 0xfc]))
            text-size (code-size tokens)
            data-address (align (+ text-rva text-size) section-alignment)
            data (vec (concat (mapcat #(le (:paddr %) 8) segments)
                              (repeat (- variables-size 16) 0)
                              (repeat (- embedded-offset variables-size) 0)
                              kernel))
            data-raw-size (align (count data) file-alignment)
            reloc-address (align (+ data-address (count data)) section-alignment)
            labels (merge {:address0 (+ data-address (nth data-addresses 0))
                           :address1 (+ data-address (nth data-addresses 1))
                           :map-pointer (+ data-address 16)
                           :map-size (+ data-address 24)
                           :map-key (+ data-address 32)
                           :descriptor-size (+ data-address 40)
                           :descriptor-version (+ data-address 48)}
                          (into {} (map-indexed
                                    (fn [index segment]
                                      [(keyword (str "segment" index))
                                       (+ data-address embedded-offset (:offset segment))])
                                    segments)))
            text (finalize-loader tokens labels)
            text-raw-size (align (count text) file-alignment)
            data-offset (+ text-offset text-raw-size)
            reloc-offset (+ data-offset data-raw-size)
            reloc (vec (concat (le 0 4) (le 12 4) (le 0 2) (le 0 2)))
            reloc-raw-size (align (count reloc) file-alignment)
            image-size (align (+ reloc-address (count reloc)) section-alignment)
            optional (optional-header text-rva (count text)
                                      (+ data-raw-size reloc-raw-size) image-size
                                      reloc-address (count reloc))
            coff (vec (concat (le 0x8664 2) (le 3 2) (repeat 12 0)
                              (le 0xf0 2) (le 0x22 2)))
            sections (concat
                      (section-header ".text" (count text) text-rva text-raw-size
                                      text-offset 0x60000020)
                      (section-header ".data" (count data) data-address data-raw-size
                                      data-offset 0xc0000040)
                      (section-header ".reloc" (count reloc) reloc-address reloc-raw-size
                                      reloc-offset 0x42000040))
            dos (assoc (vec (repeat 0x80 0)) 0 0x4d 1 0x5a 0x3c 0x80)
            headers (pad-to (concat dos [0x50 0x45 0 0] coff optional sections) text-offset)
            bytes (vec (concat headers (pad-to text text-raw-size)
                               (pad-to data data-raw-size)
                               (pad-to reloc reloc-raw-size)))]
        {:format :pe32+-embedded-kernel/v1 :target firmware-target
         :entry :efi_main :entry-rva text-rva :sections [:text :data :reloc]
         :imports [] :embedded-kernel-sha256 (artifact/sha256 kernel)
         :bytes bytes}))))
