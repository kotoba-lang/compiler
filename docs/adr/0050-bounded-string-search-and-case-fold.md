# ADR 0050: Bounded string substring search and case fold

Status: implemented and locally qualified (reference, restricted JavaScript,
typed Wasm); native `aarch64`/`x86_64` backends intentionally not attempted

## Context

Kotoba's string-operations primitive set was exactly `{string-byte-length
string=? string-concat string-replace-all keyword-from-string
keyword-name}` (`kotoba.compiler.frontend/string-operations`). There was no
substring-search primitive (no index-of/contains) and no case-fold
primitive (no lowercase/uppercase normalization).

This is a concretely documented blocker for the in-flight
`kotoba-lang/compiler` fleet-migration wave
(`90-docs/adr/2607202200-kotoba-sovereign-source-and-cljc-fleet-migration.edn`
in the `com-junkawasaki/root` superproject): `cloud-itonami/cloud-itonami-
isco-8114`'s `mineralplant.governor/check` performs a defense-in-depth
free-text scope-exclusion check -- a case-folded substring search of a
proposal's rationale against roughly a dozen finalization/execution phrases
-- alongside its closed-set op-allowlist check. Porting only the
op-allowlist half and silently dropping the free-text half would be a
silent narrowing of a safety-critical governor invariant, which the
migration's own gap-disposition policy forbids. That family (`isic-*`,
`isco-*`, `iso3166-*`, `unspsc-*`, `cofog-*`, `gtin-*`; 294 repositories,
2120 production `.cljc` files as of the 2026-07-22 census) is expected to
share this same actor/advisor/governor/store shape.

This ADR does **not** migrate `mineralplant.governor` itself, and does not
touch the separate `kotoba/app` capability-profile completion gate for
LLM/actor/state effects (tracked in `kotoba-lang/kotoba`'s
`docs/lang/application-profile.md`). It closes only the base-language string
primitive gap so that gate, once landed independently, is not also blocked
on missing string operations.

## Decision

Add two base-language string primitives, following `string-replace-all`'s
existing footprint exactly (frontend arity/type admission, reference
evaluator, typed-Wasm lowering, and the pinned restricted-JavaScript
backend), and explicitly **not** extending native (`aarch64`/`x86_64`)
backend or `cljs-kotoba-v1` support -- `string-replace-all`,
`keyword-from-string`, and `keyword-name` already have neither, and these
two join `kotoba.compiler.ir/non-string-typed-ops`, the single shared
denylist both admission paths consult
(`kotoba.compiler.ir/only-string-typed-features?` for native,
`only-cljs-provider-typed-features?` for `cljs-kotoba-v1`).

- **`string-contains? : string string -> i64`** (2 args). Returns `1` if
  the first string contains the second as a substring, `0` otherwise. This
  mirrors `string=?`'s existing bool-as-i64 idiom (not the typed `:bool` ADT
  value used by e.g. `string-index-contains`), which is both the simpler
  cross-target lowering and the more directly useful shape for a governor
  boolean check. An empty needle fails closed
  (`:empty-string-search-needle` / `empty-string-search-needle`), matching
  `string-replace-all`'s existing empty-needle-rejection precedent rather
  than defining "does X contain the empty string" as vacuously true.
- **`string-fold-case : string -> string`** (1 arg). Returns the input with
  Unicode simple case folding applied (JVM `.toLowerCase(Locale/ROOT)`,
  cljs/JS `.toLowerCase()`), bounded to the existing 65,536-byte
  `string-value-byte-limit` on the result, matching `string-concat` and
  `string-replace-all`'s existing bounded-result precedent. `Locale/ROOT` is
  pinned explicitly on the JVM side (`kotoba.compiler.value/fold-case!`) --
  plain `.toLowerCase()`/`clojure.string/lower-case` fold through the
  platform default locale, which is not deterministic across machines (the
  classic case: Turkish `tr`/`tr-TR` folds uppercase `I` to dotless `ı`, not
  `i`). A safe deterministic application language cannot let case-folding
  depend on which machine it runs on.
- The two compose directly for the governor's actual need -- case-insensitive
  substring search:
  `(string-contains? (string-fold-case haystack) (string-fold-case needle))`.

Full Unicode `SpecialCasing.txt` context/locale-sensitive exceptions
(Turkish dotless `ı`/dotted `İ`, German `ß`->`ss` expansion, Lithuanian
dot-retention, and similar) are **not** claimed to be identical across the
JVM reference evaluator and the JS-hosted restricted-JavaScript/typed-Wasm
targets. Conformance vectors cover ASCII and the common accented-Latin
range (`CAFÉ`/`café`), where `Locale/ROOT` and JS `.toLowerCase()` agree.

## Cross-target conformance evidence

Per the fleet-migration ADR's `:cross-host-conformance` rule: semantic
parity across hosts is what is gated, not byte-identical compiler output.

- **Reference (JVM/CLJC) evaluator**: `kotoba.compiler.ir/eval-expr` --
  `string-contains?` via `clojure.string/includes?` (`:cljs`
  `clojure.string/includes?` identically, `i64/one`/`i64/zero` result);
  `string-fold-case` via the new `kotoba.compiler.value/fold-case!`.
  Directly exercised via `kotoba.compiler.ir/execute`.
- **Restricted JavaScript** (`:js-kotoba-v1`, `kotoba.script/emit`, the
  pinned `kotoba-lang/kotoba-script` dependency): companion
  `kotoba-lang/kotoba-script` PR #64
  (`3598e5bd3e6758f7d88523a8f2b03ce8e64ceaf0`, pinned in `deps.edn`) adds
  `stringContains`/`stringFoldCase` to the runtime prelude and dispatch
  tables; the emitted module is executed under real Node.js, including
  round-tripping through `verify-output!`'s restricted-AST check (no
  `globalThis`/`window`/`document`/`eval`/`Function`/dynamic-import).
- **Typed Wasm** (`:wasm32-browser-kotoba-v1`, `kotoba.compiler.backend.wasm`
  + `wasm-typed`): two new `kotoba:typed` host imports,
  `string-contains` (`i32 i32(ref) i32(ref) -> i32`, extended to `i64` via
  `i64.extend_i32_u` at the call site exactly like `string=?`'s `typed-equal`
  call) and `string-fold-case` (`i32 i32(ref) -> i32(ref)`). No new typed
  descriptor kind and no Wasm typed-ABI version bump were needed (both stay
  within the existing `:string` descriptor). `runtime/browser-host.mjs`
  implements both host functions and admits the two new import names into
  its `ALLOWED_IMPORTS` allowlist; the emitted module is instantiated and
  executed as real Wasm bytes under Node's `WebAssembly` via
  `runtime/browser-host.mjs`.

Evidence:

- `kotoba-lang/kotoba-script` PR #64,
  `3598e5bd3e6758f7d88523a8f2b03ce8e64ceaf0`: 47 tests / 160 assertions
  locally (`clojure -M:test`), 0 failures -- that repo has no CI configured.
- `kotoba-lang/compiler`, this change: `clojure -M:test` 396 tests / 4280
  assertions, 0 failures. (4 pre-existing errors in
  `component-artifact-test` are an unrelated local `wasm-tools` version
  mismatch -- 1.250.0 installed vs. this repo's pinned 1.243.0 -- reproduced
  identically on a clean checkout with no changes from this ADR; CI pins
  the correct version via `bytecodealliance/actions/wasm-tools/setup`.)
  `npm run test-nbb-wasm32` (26 cases, 0 failed), `npm run test-browser-host`,
  and `npm run test-wasmtime` (independent Wasmtime instantiation, separate
  from Node's V8 `WebAssembly`) all pass with no regression.
- New test: `kotoba.compiler.string-operation-test/bounded-string-search-
  and-case-fold-have-cross-target-conformance` -- found/not-found, empty
  needle on both empty and non-empty haystacks (fails closed), a
  40,000/30,000-byte pair near the string-value bound, ASCII and multi-byte
  UTF-8 (`CAFÉ`/`café`) case folding, and end-to-end case-insensitive-search
  composition -- all three targets (reference `ir/execute`, restricted-JS
  via `instantiateKotoba`, typed Wasm via `runtime/browser-host.mjs`).

## Explicitly out of scope / known gaps

- **Native `aarch64`/`x86_64` backends**: not attempted. `string-replace-all`
  already has no native lowering (native only implements the
  `string-byte-length`/`string=?`/`string-concat` "string-only typed
  features" slice, `kotoba.compiler.core`'s `only-string-typed-features?`
  gate), so this follows existing precedent rather than a new gap. A
  program using `string-contains?` or `string-fold-case` targeting
  `:x86_64-kotoba-v1`/`:aarch64-kotoba-v1` fails closed with the same
  "typed values currently require the kotoba-script web target, typed
  Wasm/CLJS target, or (native targets) string-only typed features" error
  `string-replace-all` already produces, not a confusing backend crash.
- **`cljs-kotoba-v1` backend** (`kotoba.compiler.backend.cljs`, plain
  ClojureScript source text, distinct from the restricted-JavaScript
  `:js-kotoba-v1` target): also not extended, again matching
  `string-replace-all`'s existing scope (`only-cljs-provider-typed-
  features?` shares the same `non-string-typed-ops` denylist).
- **Full Unicode `SpecialCasing.txt` parity**: not claimed; see Decision.
- This ADR does not migrate `mineralplant.governor` or touch any
  `kotoba/app` capability-profile work -- both are explicitly separate,
  independently gated efforts.
