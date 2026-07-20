(ns test.nbb.cases
  "Shared fixture manifest for the nbb-native wasm32 compile path's
  cross-check against the JVM path -- consumed by both
  `generate-golden.cljs` (authors the golden `.wasm` files once, via the
  JVM path) and `run.cljs` (the repeatable, JVM-free regression test that
  every subsequent run checks against those golden files). Kept as one
  shared data file so the two scripts can never silently drift apart on
  which cases exist.

  :policy is a path to an EDN policy file, or nil for `{}` (deny-by-default,
  no capabilities granted -- the right default for every pure fixture)."
  )

;; `:target` is the short CLI name (`kotoba.compiler.cli`'s `parse-target`
;; input shape, e.g. what a user passes to `--target`); `target-keyword`
;; below maps it to the resolved profile keyword `kotoba.compiler.nbb.cli`'s
;; own (identically-valued, deliberately not `:require`d here -- that ns
;; runs its dispatch as an unconditional side effect at load time, wrong to
;; pull in as a library) `targets` table uses.
(def target-keyword
  {"wasm32" :wasm32-kotoba-v1
   "wasm32-browser" :wasm32-browser-kotoba-v1
   "wasm32-wasi" :wasm32-wasi-kotoba-v1})

(def cases
  [{:name "aiueos-probe" :source "examples/aiueos-probe.kotoba" :target "wasm32-browser" :policy nil}
   {:name "capability" :source "examples/capability.kotoba" :target "wasm32-browser" :policy "examples/capability-policy.edn"}
   {:name "fuel" :source "examples/fuel.kotoba" :target "wasm32-browser" :policy nil}
   {:name "heap" :source "examples/heap.kotoba" :target "wasm32-browser" :policy nil}
   {:name "i64-semantics" :source "examples/i64-semantics.kotoba" :target "wasm32-browser" :policy nil}
   {:name "list" :source "examples/list.kotoba" :target "wasm32-browser" :policy nil}
   {:name "structured" :source "examples/structured.kotoba" :target "wasm32-browser" :policy nil}
   ;; i64/sleb128 boundary regression cases (this PR): the whole point of
   ;; the `:cljs` port's `cljs-i64`/bigint machinery is the FULL signed
   ;; 64-bit range, which no `examples/*.kotoba` fixture happens to
   ;; exercise -- these do, deliberately at the exact values where a 32-bit-
   ;; truncating or double-precision-losing port would first diverge.
   {:name "i64-max-literal" :source "test/nbb/fixtures/i64-max-literal.kotoba" :target "wasm32-browser" :policy nil}
   {:name "i64-min-literal" :source "test/nbb/fixtures/i64-min-literal.kotoba" :target "wasm32-browser" :policy nil}
   {:name "i64-add-wraparound" :source "test/nbb/fixtures/i64-add-wraparound.kotoba" :target "wasm32-browser" :policy nil}
   {:name "negative-one-literal" :source "test/nbb/fixtures/negative-one-literal.kotoba" :target "wasm32-browser" :policy nil}
   {:name "sleb-boundary-127" :source "test/nbb/fixtures/sleb-boundary-127.kotoba" :target "wasm32-browser" :policy nil}
   {:name "sleb-boundary-neg128" :source "test/nbb/fixtures/sleb-boundary-neg128.kotoba" :target "wasm32-browser" :policy nil}
   {:name "sleb-boundary-128" :source "test/nbb/fixtures/sleb-boundary-128.kotoba" :target "wasm32-browser" :policy nil}
   {:name "local-uleb128-130" :source "test/nbb/fixtures/local-uleb128-130.kotoba" :target "wasm32-browser" :policy nil}
   ;; Exact float token normalization and Wasm lowering must be byte-identical
   ;; between the JVM and JVM-free nbb compiler hosts.
   {:name "f64-bits" :source "test/nbb/fixtures/f64-bits.kotoba" :target "wasm32-browser" :policy nil}
   {:name "f32-bits" :source "test/nbb/fixtures/f32-bits.kotoba" :target "wasm32-browser" :policy nil}
   {:name "vector-f64" :source "test/nbb/fixtures/vector-f64.kotoba" :target "wasm32-browser" :policy nil}
   {:name "i32-profile" :source "test/nbb/fixtures/i32-profile.kotoba" :target "wasm32-browser" :policy nil}])
