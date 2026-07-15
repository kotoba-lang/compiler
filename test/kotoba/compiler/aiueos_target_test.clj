(ns kotoba.compiler.aiueos-target-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.target :as target]))

(defn- unsigned [n] (bit-and (int n) 0xff))
(defn- read-le [bytes offset width]
  (reduce (fn [n index]
            (+ n (bit-shift-left (long (unsigned (nth bytes (+ offset index))))
                                 (* 8 index))))
          0 (range width)))

(defn- c-string [bytes offset]
  (apply str (map char (take-while pos? (drop offset bytes)))))

(deftest freestanding-aiueos-profiles-have-no-host-runtime
  (doseq [[name expected]
          [[:x86_64-aiueos-uefi-v1
           {:execution :firmware :artifact :pe32+ :subsystem :efi-application
             :entry :efi_main :abi :microsoft-x64
             :entry-contract :microsoft-x64-zero-arity-efi-status-v1}]
           [:x86_64-aiueos-kernel-v1
            {:execution :kernel :artifact :elf64
             :entry :aiueos_kernel_entry :abi :aiueos-kernel-v1}]
           [:x86_64-aiueos-user-v1
            {:execution :process :artifact :elf64
             :entry :aiueos_process_entry :abi :aiueos-user-v1}]]]
    (testing (str name)
      (let [profile (target/profile name)]
        (is (= :aiueos (:os profile)))
        (is (= (if (= name :x86_64-aiueos-user-v1)
                 :kotoba-aiueos-user-v1 :none)
               (:runtime profile)))
        (is (false? (:ambient-syscalls profile)))
        (is (= expected (select-keys profile (keys expected))))))))

(deftest aiueos-targets-bind-profile-identity-into-artifacts
  (let [source "(defn main [] (+ 40 2))"]
    (doseq [name [:x86_64-aiueos-uefi-v1 :x86_64-aiueos-kernel-v1
                  :x86_64-aiueos-user-v1]]
      (let [artifact (:artifact (compiler/compile-source source name))]
        (is (= name (:target artifact)))
        (is (= (target/profile name) (:target-profile artifact)))
        (is (= (if (= name :x86_64-aiueos-user-v1)
                 :kotoba-aiueos-user-v1 :none)
               (get-in artifact [:target-profile :runtime])))))))

(deftest kernel-target-emits-a-real-freestanding-elf64-image
  (let [{:keys [binary]} (compiler/compile-source "(defn main [] (+ 40 2))"
                                                  :x86_64-aiueos-kernel-v1)
        bytes (:bytes binary)
        section-offset (read-le bytes 40 8)
        section-count (read-le bytes 60 2)]
    (is (= [0x7f 0x45 0x4c 0x46] (subvec bytes 0 4)))
    (is (= 2 (nth bytes 4)) "ELFCLASS64")
    (is (= 2 (read-le bytes 16 2)) "ET_EXEC, not a host-linked object")
    (is (= 0x3e (read-le bytes 18 2)) "EM_X86_64")
    (is (= (:entry-address binary) (read-le bytes 24 8)))
    (is (= 2 (read-le bytes 56 2)) "RX text and RW context PT_LOAD segments")
    (is (= 4 section-count))
    (is (= (* 4 64) (- (count bytes) section-offset)))
    (is (= [:text :data :shstrtab] (:sections binary)))
    (is (empty? (:imports binary)))
    (is (nil? (:interpreter binary)))
    (is (= :aiueos_kernel_entry (:entry binary)))
    ;; Entry shim initializes r9 from a RIP-relative static context before CALL.
    (is (= [0x4c 0x8d 0x0d] (subvec bytes 0x1000 0x1003)))
    ;; Context fuel is initialized to 256; no host process populates it.
    (is (= 256 (read-le bytes (+ 0x2000 8) 8)))))

(deftest kernel-target-emits-linkable-relocatable-probe-object
  (let [{:keys [object]} (compiler/compile-source "(defn main [] 42)"
                                                  :x86_64-aiueos-kernel-v1)
        bytes (:bytes object)
        section-offset (read-le bytes 40 8)
        section #(-> section-offset (+ (* % 64)))
        text (section 1)
        data (section 2)
        rela (section 3)
        symtab (section 4)
        strtab (section 5)
        strtab-offset (read-le bytes (+ strtab 24) 8)
        probe-symbol (+ (read-le bytes (+ symtab 24) 8) (* 4 24))]
    (is (= [0x7f 0x45 0x4c 0x46] (subvec bytes 0 4)))
    (is (= 1 (read-le bytes 16 2)) "ET_REL")
    (is (= 0x3e (read-le bytes 18 2)) "EM_X86_64")
    (is (zero? (read-le bytes 24 8)) "relocatable objects have no entry")
    (is (zero? (read-le bytes 32 8)) "no program headers/dynamic loader")
    (is (= 7 (read-le bytes 60 2)))
    (is (= (* 7 64) (- (count bytes) section-offset)))
    (is (= 1 (read-le bytes (+ text 4) 4)) "SHT_PROGBITS .text")
    (is (= 0x6 (read-le bytes (+ text 8) 8)) "ALLOC|EXEC")
    (is (= 0x3 (read-le bytes (+ data 8) 8)) "ALLOC|WRITE")
    (is (= 4 (read-le bytes (+ rela 4) 4)) "SHT_RELA")
    (is (= 4 (read-le bytes (+ rela 40) 4)) "relocation links .symtab")
    (is (= 1 (read-le bytes (+ rela 44) 4)) "relocation applies to .text")
    (is (= 2 (read-le bytes (+ (read-le bytes (+ rela 24) 8) 8) 4))
        "R_X86_64_PC32")
    (is (= 2 (unsigned-bit-shift-right
              (read-le bytes (+ (read-le bytes (+ rela 24) 8) 8) 8) 32))
        "relocation selects local .data section symbol")
    (is (= -4 (unchecked-long
               (read-le bytes (+ (read-le bytes (+ rela 24) 8) 16) 8))))
    (is (= 2 (read-le bytes (+ symtab 4) 4)) "SHT_SYMTAB")
    (is (= 4 (read-le bytes (+ symtab 44) 4)) "first global symbol index")
    (is (= "kotoba_aiueos_probe"
           (c-string bytes (+ strtab-offset (read-le bytes probe-symbol 4)))))
    (is (= 0x12 (nth bytes (+ probe-symbol 4))) "STB_GLOBAL|STT_FUNC")
    (is (= 1 (read-le bytes (+ probe-symbol 6) 2)) "defined in .text")
    (is (= [:text :data :rela.text :symtab :strtab :shstrtab]
           (:sections object)))
    (is (= "kotoba_aiueos_probe" (:export object)))
    (is (= :sysv (:abi object)))
    (is (= [{:section :text :offset 3 :type :r-x86-64-pc32
             :symbol :data :addend -4}]
           (:relocations object)))
    (is (empty? (:imports object)))
    (is (nil? (:interpreter object)))
    ;; Public wrapper begins with LEA r9,[RIP+disp32], whose immediate is
    ;; resolved by the single .rela.text record at link time.
    (is (= [0x4c 0x8d 0x0d 0 0 0 0] (subvec bytes 64 71)))))

(deftest elf64-packaging-is-not-applied-to-firmware-or-host-targets
  (is (nil? (:binary (compiler/compile-source "(defn main [] 0)"
                                              :x86_64-linux-kotoba-v1)))))

(deftest user-target-emits-loadable-cpl3-elf64-image
  (let [{:keys [binary]} (compiler/compile-source "(defn main [] (+ 40 2))"
                                                  :x86_64-aiueos-user-v1)
        bytes (:bytes binary)]
    (is (= 2 (read-le bytes 16 2)))
    (is (= 0x1e1000 (:entry-address binary)))
    (is (= 0x1e2000 (:result-address binary)))
    (is (= 2 (read-le bytes 56 2)))
    (is (= [0x4c 0x8d 0x0d] (subvec bytes 0x1000 0x1003)))
    (is (= [0x48 0x89 0x05] (subvec bytes 0x100c 0x100f)))
    (is (= :kotoba-sysv-context-r9-aiueos-runtime-v2 (:entry-contract binary)))
    (is (= 80 (:runtime-handle-offset binary)))
    (is (empty? (:imports binary)))))

(deftest user-target-lowers-admitted-capability-to-aiueos-runtime-syscall
  (let [{:keys [binary]}
        (compiler/compile-source "(defn main [] (cap-call 2 0))"
                                 :x86_64-aiueos-user-v1
                                 {:allow #{[:cap/call 2]}})
        bytes (:bytes binary)]
    (is (= 4 (read-le bytes (+ 0x2000 16) 1)) "only capability 2 is admitted")
    (is (= 0x1e1020 (read-le bytes (+ 0x2000 48) 8)))
    (is (= [0xb8 5 0 0 0 0x48 0x8b 0x7f 0x50 0x0f 5 0xc3]
           (subvec bytes 0x1020 0x102c)))
    (is (zero? (read-le bytes (+ 0x2000 80) 8))
        "the loader, never the compiler, installs the domain-owned handle")))

(deftest kernel-target-exports-four-argument-journal-planner
  (let [{:keys [object]}
        (compiler/compile-source
         "(defn aiueos-journal-plan [valid0 sequence0 valid1 sequence1] (if valid0 sequence0 sequence1)) (defn main [] 0)"
         :x86_64-aiueos-kernel-v1)]
    (is (= "kotoba_aiueos_journal_plan" (:export object)))
    (is (= :sysv (:abi object)))
    (is (empty? (:imports object)))))

(deftest kernel-target-lowers-bounded-byte-load-without-imports
  (let [{:keys [object]}
        (compiler/compile-source
         "(defn aiueos-journal-plan [base length index unused] (kernel-load-u8 base length index)) (defn main [] 0)"
         :x86_64-aiueos-kernel-v1)
        bytes (:bytes object)]
    (is (= "kotoba_aiueos_journal_plan" (:export object)))
    (is (empty? (:imports object)))
    (is (some #(= [0x0f 0xb6 0x04 0x02] %)
              (partition 4 1 bytes)))
    (is (some #(= [0x0f 0x0b] %) (partition 2 1 bytes)))))

(deftest bounded-byte-load-requires-base-length-index
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"kernel memory operation arity mismatch"
       (compiler/check-source "(defn main [] (kernel-load-u8 1 2))"))))

(deftest kernel-target-exports-bounded-fnv-function
  (let [source "(defn aiueos-fnv1a [base length] (bit-xor (kernel-load-u8 base length 0) 7)) (defn main [] 0)"
        {:keys [object]} (compiler/compile-source source :x86_64-aiueos-kernel-v1)]
    (is (= "kotoba_aiueos_fnv1a" (:export object)))
    (is (empty? (:imports object)))
    (is (some #(= [0x49 0xc7 0x41 0x08 0x00 0x04 0x00 0x00] %)
              (partition 8 1 (:bytes object))))))

(deftest bounded-kernel-memory-is-rejected-for-host-targets
  (let [source "(defn read-byte [base length index] (kernel-load-u8 base length index)) (defn main [] 0)"]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"requires the aiueos kernel target"
         (compiler/compile-source source :x86_64-linux-kotoba-v1)))))

(deftest kernel-target-exports-record-validators
  (doseq [[entry expected]
          [['aiueos-journal-record-valid "kotoba_aiueos_journal_record_valid"]
           ['aiueos-object-transaction-valid "kotoba_aiueos_object_transaction_valid"]]]
    (let [source (str "(defn " entry " [base length] (bit-and (kernel-load-u8 base length 0) 255)) (defn main [] 0)")
          {:keys [object]} (compiler/compile-source source :x86_64-aiueos-kernel-v1)]
      (is (= expected (:export object)))
      (is (empty? (:imports object))))))

(deftest kernel-target-exports-storage-read-validators
  (doseq [[entry params expected]
          [['aiueos-mutable-object-valid '[object object-length sequence transaction transaction-length]
            "kotoba_aiueos_mutable_object_valid"]
           ['aiueos-superblock-valid '[base length] "kotoba_aiueos_superblock_valid"]]]
    (let [source (str "(defn " entry " " params " 1) (defn main [] 0)")
          {:keys [object]} (compiler/compile-source source :x86_64-aiueos-kernel-v1)]
      (is (= expected (:export object)))
      (is (empty? (:imports object))))))

(deftest kernel-target-lowers-bounded-byte-store
  (let [source "(defn aiueos-journal-record-build [base length value] (kernel-store-u8 base length 0 value)) (defn main [] 0)"
        {:keys [object]} (compiler/compile-source source :x86_64-aiueos-kernel-v1)]
    (is (= "kotoba_aiueos_journal_record_build" (:export object)))
    (is (empty? (:imports object)))
    (is (some #(= [0x88 0x04 0x3a] %) (partition 3 1 (:bytes object))))
    (is (some #(= [0x0f 0x0b] %) (partition 2 1 (:bytes object))))))

(deftest bounded-byte-store-requires-four-operands
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"kernel memory operation arity mismatch"
       (compiler/check-source "(defn main [] (kernel-store-u8 1 2 3))"))))

(deftest kernel-target-exports-pci-planners
  (doseq [[entry params expected]
          [['aiueos-virtio-cap-valid '[pointer cap-length bar offset length]
            "kotoba_aiueos_virtio_cap_valid"]
           ['aiueos-pci-extent-valid '[value size] "kotoba_aiueos_pci_extent_valid"]
           ['aiueos-pci-region-valid '[offset bytes bar-length]
            "kotoba_aiueos_pci_region_valid"]
           ['aiueos-syscall-range-valid '[pointer length lower upper]
            "kotoba_aiueos_syscall_range_valid"]
           ['aiueos-copy-in '[source source-length destination destination-length count]
            "kotoba_aiueos_copy_in"]
           ['aiueos-capability-plan '[slot generation type state-rights request]
            "kotoba_aiueos_capability_plan"]
           ['aiueos-service-lifecycle '[generation restarts event budget]
            "kotoba_aiueos_service_lifecycle"]
           ['aiueos-service-registry-build '[base length sequence state0 state1]
            "kotoba_aiueos_service_registry_build"]]]
    (let [source (str "(defn " entry " " params " 1) (defn main [] 0)")
          {:keys [object]} (compiler/compile-source source :x86_64-aiueos-kernel-v1)]
      (is (= expected (:export object)))
      (is (empty? (:imports object))))))

(deftest kernel-target-copy-in-retains-bounded-load-store-and-fuel
  (let [source "(defn aiueos-copy-in [source source-length destination destination-length count] (kernel-store-u8 destination destination-length 0 (kernel-load-u8 source source-length 0))) (defn main [] 0)"
        {:keys [object]} (compiler/compile-source source :x86_64-aiueos-kernel-v1)
        bytes (:bytes object)]
    (is (= "kotoba_aiueos_copy_in" (:export object)))
    (is (some #(= [0x0f 0xb6 0x04 0x02] %) (partition 4 1 bytes)))
    (is (some #(= [0x88 0x04 0x3a] %) (partition 3 1 bytes)))
    (is (some #(= [0x49 0xc7 0x41 0x08 0x00 0x04 0x00 0x00] %)
              (partition 8 1 bytes)))))


(deftest firmware-target-emits-a-real-import-free-pe32+-efi-image
  (let [{:keys [binary]} (compiler/compile-source "(defn main [] 0)"
                                                  :x86_64-aiueos-uefi-v1)
        bytes (:bytes binary)
        pe-offset (read-le bytes 0x3c 4)
        coff (+ pe-offset 4)
        optional (+ coff 20)
        directories (+ optional 112)
        section-table (+ optional (read-le bytes (+ coff 16) 2))]
    (is (= [0x4d 0x5a] (subvec bytes 0 2)) "DOS MZ identity")
    (is (= [0x50 0x45 0 0] (subvec bytes pe-offset (+ pe-offset 4))))
    (is (= 0x8664 (read-le bytes coff 2)) "IMAGE_FILE_MACHINE_AMD64")
    (is (= 3 (read-le bytes (+ coff 2) 2)))
    (is (= 0x20b (read-le bytes optional 2)) "PE32+")
    (is (= 10 (read-le bytes (+ optional 68) 2)) "EFI application subsystem")
    (is (= 0x1000 (read-le bytes (+ optional 16) 4)) "entry RVA")
    (is (= 0 (read-le bytes (+ directories 8) 4)) "import directory RVA")
    (is (= 0 (read-le bytes (+ directories 12) 4)) "import directory size")
    (is (= 0x3000 (read-le bytes (+ directories (* 5 8)) 4)) "relocation RVA")
    (is (= 12 (read-le bytes (+ directories (* 5 8) 4) 4)))
    (is (= [:text :data :reloc] (:sections binary)))
    (is (empty? (:imports binary)))
    (is (= {:format :pe-base-relocation/v1 :fixups 0 :position-independent true}
           (:relocations binary)))
    (is (= :microsoft-x64-zero-arity-efi-status-v1 (:entry-contract binary)))
    ;; sub rsp,40 reserves Microsoft shadow space and aligns before the call.
    (is (= [0x48 0x83 0xec 0x28] (subvec bytes 0x200 0x204)))
    ;; Three complete 40-byte section headers fit before SizeOfHeaders.
    (is (<= (+ section-table (* 3 40)) 0x200))))

(deftest efi-packaging-rejects-an-entry-that-cannot-satisfy-its-boundary-contract
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"zero (arguments|arity)"
                        (compiler/compile-source "(defn main [image] image)"
                                                 :x86_64-aiueos-uefi-v1))))
