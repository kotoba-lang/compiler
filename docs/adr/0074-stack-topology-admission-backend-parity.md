# 0074 — Stack topology position, and admission-gate ↔ backend capability parity

Status: accepted
Date: 2026-07-24
Root authority: `com-junkawasaki/root` ADR-2607241100 (kotoba stack topology
and design cleanup). This ADR is the compiler-repo mirror of that decision;
the canonical topology and the cross-repo cleanup list live there.

## Position in the stack topology

```
kotoba    = language + datom model      (depends on: compiler, kotoba-lang, contracts, datom, cacao, did)
compiler  = AOT compiler (THIS REPO)    (depends on: security, kotoba-script — NOTHING else in the stack)
kototama  = Wasm tender runtime         (depends on: aiueos, ed25519, chicory)
aiueos    = capability OS / broker      (depends on: security, chicory; consumes compiler ARTIFACTS, not the library)
kotobase  = datom database              (depends on: kotoba, never the reverse)
```

**Invariant (dependency direction):** this repository is the foundation of the
stack. It MUST NOT acquire a dependency on `kotoba`, `kototama`, `aiueos`, or
`kotobase` — those consume the compiler (as a library or as emitted
artifacts), never the reverse. `aiueos`'s bare-metal kernel consumes
compiler-emitted freestanding objects (`x86_64-aiueos-kernel-v1`,
`x86_64-aiueos-user-v1`) as a build-time artifact edge, not a deps.edn edge;
keep it that way.

## Decision 1 — admission gate and backends must share one capability surface

Fleet-migration field evidence (2026-07, com-junkawasaki/root ADR-2607202200
sessions) found that the frontend admission gate and the per-target backends
maintain their supported-surface lists independently, and they have drifted.
All of the following passed the admission gate and then failed (or silently
miscompiled) in a backend:

- five `:f64` parameters: admitted, Wasm module fails `WebAssembly.compile()`
  with `invalid local index` (issue #206)
- bare `:option-i64` (`option-none`/`option-some?`): admitted, fails Wasm
  typed lowering (issue #206 addendum)
- `[:option :f64]` payload: rejected by the subset gate although the
  parametric option machinery supports other payloads (issue #206 addendum)
- cross-file `:require` of an `:f64`-returning function: admitted, fails
  project linking (`stub-value` has no `:f64`/`:f32` case) (issue #206)
- a `let` binding whose value expression references a same-named enclosing
  binding: admitted, **silently miscompiles** on the JS backend to a
  temporal-dead-zone self-reference (issue #225)

The class of bug is always the same: *admitted-but-not-lowerable* (or worse,
admitted-and-mislowered). Individual fixes do not close the class.

**Decision:** the admitted surface must be derived, not duplicated.

1. Each backend declares its supported ops/types/limits as data (a
   capability manifest per target, versioned with the backend).
2. The admission gate consumes those declarations: a form is admissible for
   a compilation only if every requested target declares support. Target-
   specific admission errors name the unsupporting target.
3. A differential-execution conformance suite is generated from the admitted
   surface: for each admitted op/type family, compile and RUN the same
   fixture on every executable target (js, wasm32, x86-64, aarch64 where the
   host allows) and require identical results. KIR identity checking (which
   already exists) is necessary but not sufficient — #225 produced identical
   KIR and a wrong program.

Until (1)–(3) land, new ops added to any backend MUST land with an
executed-on-every-target fixture in the same PR.

## Decision 2 — unify the equality surface

`=` admits only `{:i64 :keyword :bool :option-i64 :result-i64 :vector-i64}`
(plus parametric results); `:string` needs `string=?`; `:f64` needs `f64-eq`.
Three spellings of one concept, with the `=`-vs-`f64-lt` asymmetry (comparison
ops return `:bool` directly) on top. Types are fully static, so `=` can be
frontend-dispatched to the type-specific lowering with zero dynamism and no
safety change.

**Decision:** make `=` the single user-facing equality across all
equality-capable types via monomorphic frontend dispatch. Keep `string=?` /
`f64-eq` as the lowered internal forms (and as deprecated aliases) so existing
`.kotoba` sources stay valid.

## Decision 3 — smaller items owned by this repo

- **kexe loader validation into Kotoba objects:** `tools/kexe_loader.c` /
  `kexe_loader_windows.c` should follow the aiueos-kernel pattern — C owns
  mmap/W^X/entry only; artifact admission/validation logic moves to
  compiler-emitted Kotoba objects shared across all three loader paths.
- **classpath-scan robustness:** consumers currently need
  `:replace-paths`/`:replace-deps` in their `:kotoba` alias because inheriting
  base `:deps` confuses the compiler's classpath scanning. Harden the scan so
  a plain `:extra-deps` alias works; every fleet-migration `deps.edn` carries
  this workaround today.
