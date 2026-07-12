(ns kotoba.compiler.target)

(def profiles
  {:wasm32-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :wasm :isa :wasm32 :os :unspecified :abi :wasm-mvp :runtime :kotoba-capability-host-v1}
   :wasm32-browser-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :wasm :isa :wasm32 :os :browser :abi :wasm-mvp :runtime :kotoba-browser-host-v1}
   :wasm32-wasi-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :wasm :isa :wasm32 :os :wasi :abi :wasm-mvp :runtime :kotoba-wasi-host-v1}
   :x86_64-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :native :isa :x86_64 :os :unspecified :abi :sysv :runtime :kotoba-supervisor-v1}
   :x86_64-linux-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :native :isa :x86_64 :os :linux :abi :sysv :runtime :kotoba-linux-supervisor-v1}
   :x86_64-macos-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :native :isa :x86_64 :os :macos :abi :sysv :runtime :kotoba-macos-supervisor-v1}
   :x86_64-windows-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :native :isa :x86_64 :os :windows :abi :kotoba-sysv-v1 :runtime :kotoba-windows-supervisor-v1}
   :aarch64-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :native :isa :aarch64 :os :unspecified :abi :aapcs64 :runtime :kotoba-supervisor-v1}
   :aarch64-linux-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :native :isa :aarch64 :os :linux :abi :aapcs64 :runtime :kotoba-linux-supervisor-v1}
   :aarch64-macos-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :native :isa :aarch64 :os :macos :abi :aapcs64 :runtime :kotoba-macos-supervisor-v1}
   :aarch64-android-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :native :isa :aarch64 :os :android :abi :aapcs64 :runtime :kotoba-android-isolated-host-v1}
   :aarch64-ios-kotoba-v1 {:format :kotoba.target-profile/v1 :execution :native :isa :aarch64 :os :ios :abi :aapcs64 :runtime :kotoba-ios-static-host-v1}})

(def compatibility-targets #{:wasm32-kotoba-v1 :x86_64-kotoba-v1 :aarch64-kotoba-v1})
(defn profile [target] (get profiles target))
(defn backend [target]
  (case (:isa (profile target))
    :wasm32 :wasm32-kotoba-v1
    :x86_64 :x86_64-kotoba-v1
    :aarch64 :aarch64-kotoba-v1
    nil))
