# ADR 0004: nbb-native compile path for wasm32 targets

- Status: Accepted, implemented for `compile`/`check` on `wasm32*` targets
- Date: 2026-07-16

## Context

`bin/kotoba` was an nbb wrapper that spawned `clojure -M:run` for every
command and every target -- the entire compiler (`frontend`, `ir`, every
backend, `admission`, packaging, signing, the verifier) was JVM Clojure
(`.clj`). This is at odds with this monorepo's repo-wide runtime priority
(`kotoba wasm runtime` > `clojurewasm` > `ClojureScript` > `nbb`, JVM/`bb`
demoted to last-resort compat) and with treating this compiler as sitting in
the same architectural position as JVM/LLVM for the `.kotoba` language: that
position shouldn't itself require running a JVM to produce a `.wasm`
artifact.

## Decision

`kotoba.compiler.frontend`, `kotoba.compiler.ir`,
`kotoba.compiler.backend.wasm`, and `kotoba.compiler.admission` are `.cljc`
(converted in place, not duplicated) so they run identically under plain
Clojure (`:clj`) and nbb (`:cljs`). `bin/kotoba` spawns a fresh nbb process
(with `--classpath src` so the `.cljc` namespaces resolve) instead of
`clojure` when the command is `check`, or `compile` targeting `wasm32`,
`wasm32-browser`, or `wasm32-wasi` -- the fast path in
`kotoba.compiler.nbb.cli`. Every other target and every other subcommand is
unchanged and still spawns `clojure -M:run`.

Two new supporting namespaces exist ONLY for the `:cljs` side:

- `kotoba.compiler.cljs-i64`: represents every `.kotoba` i64 VALUE as a JS
  `bigint` (never a plain cljs number, which is an IEEE-754 double and
  silently loses precision above 2^53) with an exact two's-complement
  64-bit wraparound (`js/BigInt.asIntN`) matching the JVM path's
  `unchecked-add`/`unchecked-subtract`/`unchecked-multiply` on `long`. This
  matters beyond ordinary arithmetic: `kotoba.compiler.ir/lower`
  constant-folds a pure (effect-free) `main` by literally *executing* it at
  compile time (the "oracle"), so this file's correctness directly
  determines whether the compiled artifact for such a program is right, not
  just whether an admission check fires correctly.
- `kotoba.compiler.kotoba-reader`: a small reader for exactly the grammar
  `frontend/analyze` admits (lists, vectors, maps, sets, keywords, symbols,
  integers, strings, `#?()` reader-conditionals -- confirmed against every
  `examples/*.kotoba` fixture, none of which uses a set or reader-
  conditional, but `frontend` explicitly requests `:read-cond :allow`, so
  both are supported for parity). This exists in place of
  `clojure.tools.reader` (JVM-only) or its nominal ClojureScript sibling
  `cljs.tools.reader` (same Maven artifact, same API shape) because the
  latter, tried first, depends on several `cljs.core` internals nbb's
  SCI-based interpreter does not resolve -- three distinct unresolved-symbol
  failures in as many attempted fixes (`cljs.core.ExceptionInfo` instance
  checks, `IPrintWithWriter`/`pr-writer` protocol dispatch, a
  `:require-macros` cross-namespace macro), not one isolated gap, which is
  what motivated writing a small purpose-built reader instead of continuing
  to chase compatibility with a general-purpose one never designed for SCI.

## Verification

`test/nbb/run.cljs` (`npm run test-nbb-wasm32`) compiles every
`examples/*.kotoba` fixture plus dedicated i64/sleb128 boundary fixtures
(`test/nbb/fixtures/`: true i64 max/min, add-wraparound, the sleb
continuation-bit crossing at 127/128) through the nbb-native path and
asserts that every output is valid Wasm. Cross-host compatibility is judged by
observable semantics, ABI behavior, resource bounds, and fail-closed rejection;
the binary representation is not a language contract. Routine test runs need
no JVM at all. `clojure -M:test` (169
tests, 2986 assertions) passes unchanged, confirming the `.cljc` conversion
altered nothing on the `:clj` side.

## Non-goals

x86_64/aarch64 native codegen, ELF64/PE32+ packaging, signing, the
independent verifier, release/coverage evidence, and every `kotoba.compiler.
cli` subcommand besides `compile`/`check` are unchanged and remain JVM-only
-- not ported, not claimed as covered. This mirrors `kototama`'s own R1
(JVM/Chicory tender) being demoted to "compat suite" behind its R2
native-WASM-host path: an honest, partial migration rather than an
overclaimed one.
