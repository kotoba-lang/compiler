# Kotoba Compiler

The multi-target, deny-by-default compiler for safe Kotoba (`.kotoba`) and the
portable safe `.cljc` profile.

```text
source -> inert reader -> typed/effect HIR -> SSA-like KIR
       -> wasm32 | x86_64 | aarch64 -> independent verifier -> admission
```

WebAssembly is one backend, not the compiler architecture. Native backends emit
machine instructions directly and never invoke an assembler, LLVM, a JVM JIT,
or a Wasm runtime. Native output is deliberately a sealed `KEXE` object rather
than an OS executable: an aiueos loader must verify it, map code W^X, and expose
only policy-derived capability trampolines.

The current experimental slice supports pure integer functions, parameters,
direct calls, sequential `let`, `if`, arithmetic, and comparisons. It emits
executable Wasm with real runtime parameters, locals, calls, and branches, plus
verified runtime functions for x86-64 and AArch64. KEXE seals its
target, KIR identity, effects, resource limits, and exact code bytes with
SHA-256. Effectful calls, allocation, indirect control flow, and OS ABI emission
fail closed until their verifier rules exist.

```bash
bin/kotoba -M compile example.kotoba --target wasm32 --output app.wasm
bin/kotoba -M compile example.kotoba --target x86_64 --output app.kexe
bin/kotoba -M verify app.kexe
scripts/conformance.sh
```

On x86-64 Linux and AArch64 macOS/Linux, `scripts/conformance.sh` additionally compiles the small
auditable loader in `tools/kexe_loader.c`, maps verified code RW, transitions it
to RX with `mprotect`, and executes a runtime arithmetic/comparison vector. No
RWX mapping is created. Zero division and signed-division overflow must trap on
all three backends; loader resource limits keep native traps outside the compiler.

Wasm modules contain a private, non-replenishable i64 fuel global initialized to
256. Every function entry checks and decrements it before evaluating guest code.
This permits bounded recursion while guaranteeing that recursive cycles trap.
x86-64 reserves r9 and AArch64 reserves x7 for a loader-owned fuel-context
pointer; both charge every function entry before guest instructions. Their real
call paths support bounded direct and mutual recursion through verified
`CALL rel32` / `BL imm26` relocations.

The test gate generates a deterministic 100-program property corpus across
arithmetic, comparisons, `if`, lexical `let`, and direct calls. Every program is
compiled to all three targets; the gate requires identical KIR, deterministic
native bytes/seals, successful re-verification, and rejection after a one-byte
mutation.

`kotoba -M check` performs capability admission before backend selection.
`cap-call` accepts only a literal ID in `[0,255]`; effects propagate through the
full function-call graph, including cycles and lexical bindings. Admission is
deny-by-default and covers every exported function, returns a least-privilege
policy, and reports unused grants. Wasm lowers admitted calls to the sole
`kotoba:cap/call(i64,i64)->i64` import; the host rechecks policy on every call.
Native targets carry a sealed context-v1 layout. Generated code checks its
256-bit allow bitmap before calling the single fixed callback slot; the callback
checks the same bitmap again. x86-64 keeps the context in r9 and AArch64 in x7.

KEXE authenticity uses a separate Ed25519 envelope. The signed statement binds
the artifact SHA-256, signer fingerprint/public key, not-before, and expiry.
Verification requires an explicit trusted-signer set, checks signer/artifact
revocation and time validity, then runs the normal KEXE verifier.

```bash
kotoba -M keygen --output key.edn
kotoba -M trust-key key.edn --output trust.edn
kotoba -M sign app.kexe --key key.edn --expires 2000000000 --output app.signed.kexe
kotoba -M verify-signed app.signed.kexe --trust trust.edn
```

```bash
kotoba -M check examples/capability.kotoba \
  --policy examples/capability-policy.edn
kotoba -M compile examples/capability.kotoba --target wasm32 \
  --policy examples/capability-policy.edn --output capability.wasm
```

After putting `bin/kotoba` on `PATH`, the public command is simply
`kotoba -M ...`. The bootstrap currently uses Clojure internally, but that is
not part of the compiler CLI contract and can be replaced by the self-hosted
Kotoba driver without changing user commands.

See [docs/architecture.md](docs/architecture.md) and
[docs/threat-model.md](docs/threat-model.md).
