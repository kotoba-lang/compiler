# Architecture decision: one compiler, multiple verified backends

Status: accepted, 2026-07-11.

`kotoba-lang/kotoba` owns language semantics. This repository owns compilation.
`aiueos` owns process isolation, capability brokerage, loading, and receipts.

The shared safety pipeline precedes every backend. A backend cannot weaken the
source subset, inferred effects, resource bounds, or capability requirements.
Every artifact records its target, KIR digest, declared effects, limits, and
code bytes. A structurally independent target verifier decodes all emitted
instructions before admission.

Targets are versioned contracts:

- `wasm32-kotoba-v1`: portable sandbox target and conformance oracle.
- `x86_64-kotoba-v1`: direct x86-64 instructions in a sealed KEXE container.
- `aarch64-kotoba-v1`: direct AArch64 instructions in a sealed KEXE container.

KEXE is not mapped by this compiler. The loader must reverify, compare the
policy/artifact digest, allocate writable non-executable memory, copy code,
change it to read+execute, and never map it writable again. No generated code
may contain a syscall instruction. External effects will be reachable only
through fixed, capability-checked loader trampolines.

The experimental slice intentionally accepts less than the current Kotoba Wasm
compiler. Migration proceeds by moving frontend rules and conformance vectors
from `kotoba-lang/kotoba`, never by silently accepting unsupported forms.

## Current maturity

The compiler is `experimental alpha`, not production-safe. KIR v3 retains
multiple pure functions, runtime arguments, lexical `let`, `if`, integer
arithmetic, comparisons, and direct calls. Wasm lowers that runtime KIR to real
locals, calls, and structured control flow. Native v1 explicitly declares
`:closed-program-specialization`; general native control-flow machine code,
memory, effects, fuel instrumentation, and the W^X loader remain required before
hostile execution can be considered.

x86-64 has subsequently advanced to `:runtime-sysv-v1`: each pure KIR function
is an exported SysV integer function with a sealed offset/length/arity record.
The verifier re-lowers the sealed KIR and requires byte-for-byte code and export
table equality. The Linux conformance loader enforces RW -> RX transition and
executes runtime arguments. This seal is an integrity binding, not publisher
authentication; signed package admission remains a separate required layer.

AArch64 uses the equivalent `:runtime-aapcs64-v1` contract. Parameters are
captured into the preserved x19..x23 bank, calls use verified `BL` relocations,
stack temporaries preserve 16-byte alignment, and every branch target is emitted
on a four-byte instruction boundary. The same sealed-export and re-lowering
checks apply to both native targets.

Signed division has an explicit cross-backend trap contract. Wasm `i64.div_s`
and x86-64 `IDIV` already trap on zero and `MIN_VALUE / -1`; AArch64 `SDIV`
does neither, so the AArch64 emitter inserts divisor and overflow guards ending
in `BRK`. Conformance requires all three backends to reject both cases.

## Fuel contract

`wasm32-kotoba-v1` owns a private mutable i64 fuel global initialized to 256.
Every function body begins with an unconditional zero-check and decrement, so
direct and mutual recursive calls cannot bypass accounting. Guest code cannot
import, export, or replenish the counter. Conformance executes factorial and an
unbounded recursive function, requiring the former to return and the latter to
trap. Native backends enforce the corresponding hidden fuel context described
below.

x86-64 now implements that ABI as `:hidden-context-r9`: the sixth SysV integer
register is removed from the source ABI and carries a loader-owned pointer to a
256-unit counter. Each function entry checks and decrements `*r9`; zero executes
`UD2`. Direct calls are emitted as verified `CALL rel32` relocations and forward
the same r9 unchanged, allowing bounded direct and mutual recursion. Source
functions therefore accept at most five integer parameters on x86-64 v1.

AArch64 mirrors this as `:hidden-context-x7`. It preserves x19..x23 plus
x29/x30 according to AAPCS64, moves source parameters into that callee-saved
bank, and emits verified `BL imm26` relocations while forwarding x7 unchanged.
All temporary stack slots and register-save frames remain 16-byte aligned.

## Capability admission

The first typed effect is `[:cap/call id]`, authored only as
`(cap-call <literal-u8> value)`. Dynamic IDs are rejected rather than widened to
ambient authority. The frontend derives direct effects and call edges, closes
them to a fixpoint (including mutual recursion), and assigns effects to every
function. Because all pure functions are currently exported, module admission
uses the union across all functions, not only `main` reachability.

Policy is deny-by-default: `{:allow #{...}}`. Admission reports missing effects,
the exact minimal policy, and unused grants. This stage deliberately separates
authority analysis from execution: effectful code passes `kotoba -M check` only
with policy, and each backend must independently provide a capability-checked
trampoline before accepting it.

Wasm now implements the first trampoline as one typed import:
`kotoba:cap/call(i64 cap-id, i64 value) -> i64`. The compiler emits only IDs
already present in inferred effects and admitted policy. The runtime host still
intersects its local allow set on every invocation; compile-time admission is
never treated as runtime authority.

Native targets implement a sealed context-v1 ABI: version at offset 0, fuel at
8, a 256-bit allow bitmap at 16, and the sole `cap_call` function pointer at 48.
Generated code checks the relevant bitmap bit before loading that fixed slot;
the host callback receives the context and checks version, range, and bitmap
again. x86-64 preserves r9 around the indirect call; AArch64 preserves x7 and
uses `BLR` only on the slot loaded from `[x7,#48]`. The verifier regenerates all
checks and call instructions from sealed KIR.

The reference loader uses a two-process execution model. Its parent loads bytes
into an RW mapping, seals it RX, then forks. The child alone enters generated
code under resource limits and the platform sandbox; the parent only supervises
termination and an independent wall-clock deadline. Guest traps and supervisor
failures have distinct structured report kinds.

## Signed artifact admission

The internal SHA-256 seal detects accidental mutation but is not authenticity:
an attacker can recompute it. `kotoba.signed-kexe/v1` therefore carries an
Ed25519 statement over artifact SHA, signer fingerprint and X.509 public key,
not-before, and expiry. Admission verifies the signature, explicit trusted
signer membership, signer/artifact revocation sets, and the validity interval,
then invokes the ordinary KEXE verifier. Revocation remains outside immutable
artifact identity and can change without rewriting the artifact.

## Run receipts

`kotoba.run-receipt/v1` is a canonical hash-linked evidence record. Creation
first re-verifies the signed artifact under current trust, then binds envelope
and artifact hashes, signer, target/entry, required effects, policy/admission,
input/output, fuel, status, timestamps, and an optional parent receipt hash.
Verification requires the original signed envelope and evidence values and
repeats all authenticity, revocation, policy, and KEXE checks. Chains reject
broken links, reordering, duplicate hashes, and more than 10,000 entries.
Each receipt hash also carries an Ed25519 executor attestation verified against
the current trusted/revoked signer sets. This proves which executor attested the
evidence; it does not by itself prove hardware integrity or confidential
execution.
