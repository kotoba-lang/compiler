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
verified specialized return stubs for x86-64 and AArch64. KEXE seals its
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
x86-64 reserves r9 for a loader-owned fuel-context pointer and charges every
function entry before guest instructions; its real `CALL rel32` path supports
bounded direct and mutual recursion. AArch64 recursive calls remain rejected
until the equivalent x7 context ABI is complete.

After putting `bin/kotoba` on `PATH`, the public command is simply
`kotoba -M ...`. The bootstrap currently uses Clojure internally, but that is
not part of the compiler CLI contract and can be replaced by the self-hosted
Kotoba driver without changing user commands.

See [docs/architecture.md](docs/architecture.md) and
[docs/threat-model.md](docs/threat-model.md).
