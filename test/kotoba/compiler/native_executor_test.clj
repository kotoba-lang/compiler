(ns kotoba.compiler.native-executor-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.atomic-output :as atomic-output]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.native-executor :as executor]
            [kotoba.compiler.runtime-identity :as runtime-identity]
            [kotoba.compiler.signing :as signing]))

(defn- target []
  (if (contains? #{"aarch64" "arm64"} (.toLowerCase (System/getProperty "os.arch")))
    :aarch64-kotoba-v1
    :x86_64-kotoba-v1))

(defn- signed [source policy]
  (let [artifact (:artifact (compiler/compile-source source (target) policy))
        key (signing/generate-keypair)
        envelope (signing/sign artifact key {:not-before 1000 :expires 2000})
        trust {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
               :revoked-signers #{} :revoked-artifacts #{}}]
    {:artifact artifact :key key :envelope envelope :trust trust}))

(defonce measured-runtime
  (delay
    (let [{:keys [runtime loader-bytes]} (executor/measure-runtime)
          loader (doto (java.io.File/createTempFile "kotoba-test-loader-" "")
                   (.deleteOnExit))]
      (atomic-output/write-bytes! (.getPath loader) loader-bytes {:executable? true})
      {:runtime runtime :loader-path (.getPath loader)})))

(defn- execution-options [trust]
  (let [{:keys [runtime loader-path]} @measured-runtime]
    {:trust (assoc trust :trusted-runtime-sha256
                   #{(runtime-identity/identity-sha256 runtime)})
     :options {:now 1500 :entry 'main :runtime runtime :loader-path loader-path}}))

(deftest verified-native-execution-produces-measured-evidence
  (let [{:keys [envelope trust]} (signed "(defn main [] 42)" {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args []}
                                 options)]
    (is (= {:status :ok :result 42} (select-keys (:evidence result) [:status :result])))
    (is (= :kotoba.native-runtime/v1 (get-in result [:evidence :runtime :format])))
    (is (= executor/loader-source-sha256
           (get-in result [:evidence :runtime :loader-source-sha256])))
    (is (every? #(re-matches #"[0-9a-f]{64}" %)
                (vals (dissoc (get-in result [:evidence :runtime]) :format))))
    (is (= {:status :ok :result 42
            :fuel {:initial 256 :remaining 255}}
           (:report result)))
    (is (<= (:started-at result) (:finished-at result)))))

(deftest execution-rejects-before-entering-untrusted-or-unauthorized-code
  (let [{:keys [envelope trust]} (signed "(defn main [] 42)" {:allow #{}})
        tampered (assoc-in envelope [:artifact :code 0] 255)
        {:keys [runtime loader-path]} @measured-runtime]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runtime identity is not trusted"
                          (executor/execute envelope trust {:allow #{}} {:args []}
                                            {:now 1500 :entry 'main :runtime runtime
                                             :loader-path loader-path})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"artifact integrity mismatch"
                          (executor/execute tampered trust {:allow #{}} {:args []}
                                            {:now 1500 :entry 'main})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"entry arity"
                          (executor/execute envelope trust {:allow #{}} {:args [1]}
                                            {:now 1500 :entry 'main}))))
  (let [policy {:allow #{[:cap/call 7]}}
        {:keys [envelope trust]} (signed "(defn main [] (cap-call 7 41))" policy)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"denies required effects"
                          (executor/execute envelope trust {:allow #{}} {:args []}
                                            {:now 1500 :entry 'main})))))

(deftest execution-rejects-a-loader-that-does-not-match-the-approved-bytes
  (let [{:keys [envelope trust]} (signed "(defn main [] 42)" {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        changed (doto (java.io.File/createTempFile "kotoba-changed-loader-" "")
                  (.deleteOnExit))]
    (atomic-output/write-bytes! (.getPath changed) (byte-array [0 1 2 3])
                                {:executable? true})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match runtime identity"
                          (executor/execute envelope trust {:allow #{}} {:args []}
                                            (assoc options :loader-path (.getPath changed)))))))

(deftest native-trap-is-returned-as-measured-evidence
  (let [{:keys [envelope trust]}
        (signed "(defn forever [x] (forever x)) (defn main [] 0)" {:allow #{}})
        {:keys [trust options]} (execution-options trust)
        result (executor/execute envelope trust {:allow #{}} {:args [0]}
                                 (assoc options :entry 'forever))
        expected-signal (if (= (target) :aarch64-kotoba-v1) :SIGTRAP :SIGILL)]
    (is (= :trap (get-in result [:evidence :status])))
    (is (= {:kind :signal :signal expected-signal}
           (get-in result [:evidence :trap])))
    (is (= 0 (get-in result [:report :fuel :remaining])))
    (is (= 120 (get-in result [:report :exit])))))
