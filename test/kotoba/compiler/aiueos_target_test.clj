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

(deftest freestanding-aiueos-profiles-have-no-host-runtime
  (doseq [[name expected]
          [[:x86_64-aiueos-uefi-v1
            {:execution :firmware :artifact :pe32+ :subsystem :efi-application
             :entry :efi_main :abi :microsoft-x64}]
           [:x86_64-aiueos-kernel-v1
            {:execution :kernel :artifact :elf64
             :entry :aiueos_kernel_entry :abi :aiueos-kernel-v1}]]]
    (testing (str name)
      (let [profile (target/profile name)]
        (is (= :aiueos (:os profile)))
        (is (= :none (:runtime profile)))
        (is (false? (:ambient-syscalls profile)))
        (is (= expected (select-keys profile (keys expected))))))))

(deftest aiueos-targets-bind-profile-identity-into-artifacts
  (let [source "(defn main [] (+ 40 2))"]
    (doseq [name [:x86_64-aiueos-uefi-v1 :x86_64-aiueos-kernel-v1]]
      (let [artifact (:artifact (compiler/compile-source source name))]
        (is (= name (:target artifact)))
        (is (= (target/profile name) (:target-profile artifact)))
        (is (= :none (get-in artifact [:target-profile :runtime])))))))

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

(deftest elf64-packaging-is-not-applied-to-firmware-or-host-targets
  (is (nil? (:binary (compiler/compile-source "(defn main [] 0)"
                                              :x86_64-aiueos-uefi-v1))))
  (is (nil? (:binary (compiler/compile-source "(defn main [] 0)"
                                              :x86_64-linux-kotoba-v1)))))
