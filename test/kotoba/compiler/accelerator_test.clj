(ns kotoba.compiler.accelerator-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.accelerator :as accelerator]
            [kotoba.compiler.artifact :as artifact]))

(deftest typed-kir-lowers-deterministically-to-wgsl-and-cuda
  (let [kir (accelerator/kernel "ewise_add_f32" :ewise :add {:workgroup-size 256})
        wgsl (accelerator/compile-kernel kir :wgsl-v1)
        cuda (accelerator/compile-kernel kir :cuda-v1)
        msl (accelerator/compile-kernel kir :msl-v1)]
    (is (= :kotoba.gpu-artifact/v1 (:format wgsl)))
    (is (= (:kir-sha256 wgsl) (:kir-sha256 cuda)))
    (is (= (:kir-sha256 wgsl) (:kir-sha256 msl)))
    (is (not= (:code-sha256 wgsl) (:code-sha256 cuda)))
    (is (re-find #"@compute @workgroup_size\(256\)" (:code wgsl)))
    (is (re-find #"extern \"C\" __global__" (:code cuda)))
    (is (re-find #"#include <metal_stdlib>" (:code msl)))
    (is (= wgsl (accelerator/verify-artifact! wgsl)))
    (is (= cuda (accelerator/verify-artifact! cuda)))
    (is (= msl (accelerator/verify-artifact! msl)))
    (is (= wgsl (accelerator/compile-kernel kir :wgsl-v1)))))

(deftest reduction-lowering-is-bounded-and-target-specific
  (doseq [operator [:sum :max :min]]
    (let [kir (accelerator/kernel (str "reduce_" (name operator) "_f32") :reduce operator
                                  {:workgroup-size 128})
          wgsl (accelerator/compile-kernel kir :wgsl-v1)
          cuda (accelerator/compile-kernel kir :cuda-v1)
          msl (accelerator/compile-kernel kir :msl-v1)]
      (is (re-find #"workgroupBarrier" (:code wgsl)))
      (is (re-find #"extern __shared__ float" (:code cuda)))
      (is (re-find #"threadgroup float" (:code msl)))
      (is (= 128 (get-in cuda [:limits :workgroup-size]))))))

(deftest invalid-or-tampered-kernels-fail-closed
  (let [valid (accelerator/kernel "safe" :ewise :mul {})]
    (doseq [bad [(assoc valid :kernel/dtype :f64)
                 (assoc valid :kernel/workgroup-size 300)
                 (assoc valid :kernel/effects #{:gpu/storage-read :network})
                 (assoc valid :kernel/name "../../kernel")]]
      (is (thrown-with-msg? Exception #"invalid accelerator KIR" (accelerator/compile-kernel bad :cuda-v1))))
    (is (thrown-with-msg? Exception #"unsupported accelerator target"
                          (accelerator/compile-kernel valid :ptx-unknown)))
    (let [compiled (accelerator/compile-kernel valid :cuda-v1)]
      (is (thrown-with-msg? Exception #"invalid accelerator artifact envelope"
                            (accelerator/verify-artifact! (assoc compiled :code "evil"))))
      (let [resealed (artifact/seal (assoc compiled :code "evil"))]
        (is (thrown-with-msg? Exception #"regeneration mismatch"
                              (accelerator/verify-artifact! resealed)))))))
