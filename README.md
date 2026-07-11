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

The first vertical slice compiles a pure, zero-argument `main` containing
integer constants and arithmetic. It emits executable Wasm and verified return
stubs for x86-64 and AArch64. Effectful calls, allocation, indirect control flow,
and OS ABI emission fail closed until their verifier rules exist.

```bash
bin/kotoba -M compile example.kotoba --target wasm32 --output app.wasm
bin/kotoba -M compile example.kotoba --target x86_64 --output app.kexe
bin/kotoba -M verify app.kexe
```

After putting `bin/kotoba` on `PATH`, the public command is simply
`kotoba -M ...`. The bootstrap currently uses Clojure internally, but that is
not part of the compiler CLI contract and can be replaced by the self-hosted
Kotoba driver without changing user commands.

See [docs/architecture.md](docs/architecture.md) and
[docs/threat-model.md](docs/threat-model.md).
