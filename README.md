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

Compilation has explicit structural budgets in addition to the 1 MiB source
limit. Function count, common five-argument ABI, bindings, expression nodes, and
the estimated `let`-elided lowering size are checked before backend emission;
compact substitution chains cannot amplify into unbounded native code.

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
Linux additionally applies `no_new_privs` and a seccomp-BPF syscall allowlist
before guest entry. macOS applies a deny-by-default Seatbelt profile in the
child. CI independently requires filesystem, network, and process-creation
probes to be denied on both OS families.

Wasm modules contain a private, non-replenishable i64 fuel global initialized to
256. Every function entry checks and decrements it before evaluating guest code.
This permits bounded recursion while guaranteeing that recursive cycles trap.
x86-64 reserves r9 and AArch64 reserves x7 for a loader-owned fuel-context
pointer; both charge every function entry before guest instructions. Their real
call paths support bounded direct and mutual recursion through verified
`CALL rel32` / `BL imm26` relocations.

KIR v3 includes a normative fuel-bounded reference executor. Signed i64
add/subtract/multiply wrap modulo 2^64; invalid division traps. CI compares
boundary vectors with Wasm and the native ISA available on each runner, so
compile-time validation cannot silently use different arithmetic semantics.

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
All external EDN inputs—including KEXE, envelopes, trust stores, policies,
execution inputs, and receipts—pass through one strict bounded decoder before
verification. It accepts exactly one valid UTF-8 form and caps bytes, nesting,
token length, decoded nodes, and strings. Source files are byte-capped while
streaming before the frontend allocates the complete input.
Verification requires an explicit trusted-signer set, checks signer/artifact
revocation and time validity, then runs the normal KEXE verifier.

```bash
kotoba -M keygen --output key.edn
kotoba -M trust-key key.edn --output trust.edn
kotoba -M sign app.kexe --key key.edn --expires 2000000000 --output app.signed.kexe
kotoba -M verify-signed app.signed.kexe --trust trust.edn
```

Verified executions can produce `kotoba.run-receipt/v1`. Its hash binds the
signed envelope and artifact, signer, target/entry, required effects, exact
policy admission, input/output hashes, fuel accounting, status, time interval,
and optional parent receipt. Verification repeats current signature, trust,
revocation, policy, and artifact checks before accepting the evidence. The
receipt hash is itself signed by a trusted executor key; a hash chain alone is
not treated as proof that execution occurred.

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

`kotoba -M run` is the admitted native execution path. It verifies the signed
KEXE envelope and current trust/revocation state, checks local capability
policy, requires host ISA and entry arity to match, then invokes the supervised
loader. The command writes the measured result separately and creates an
executor-signed receipt using the supervisor's actual post-execution fuel
counter; callers cannot supply result, status, timing, or fuel values.
The result evidence also binds the pinned loader-source hash, the exact loader
binary hash, and a hash of the C compiler identity. A source mismatch is denied
before compilation, and the executor signature makes the runtime identity part
of the receipt's output evidence.

For high-assurance verification, provision the measured runtime from a reviewed
run into the trust policy, then verify against that pinned policy:

```bash
kotoba -M trust-runtime run.result.edn --trust trust.edn --output pinned-trust.edn
kotoba -M verify-receipt run.receipt.edn --trust pinned-trust.edn ...
```

The pin covers the reviewed loader source, reproduced loader binary, and C
compiler identity. Once `:trusted-runtime-sha256` exists, it is an explicit
allowlist; an empty set denies all native runtimes. Runtime revocation uses
`:revoked-runtime-sha256`.

Security mutation fuzzing runs in every CI job with a recorded seed. It mutates
sealed native artifacts (including attacker-resealed KIR/code/ABI fields),
Ed25519 envelopes, and executor receipts, requiring every case to fail closed.
A failure can be replayed locally:

```bash
KOTOBA_FUZZ_SEED=5426643073673934426 KOTOBA_FUZZ_CASES=1000 clojure -M:test
```

The C loader is also compiled with AddressSanitizer and UndefinedBehaviorSanitizer
in every Linux and macOS CI job. The sanitizer gate executes verified native
code and a malformed CLI corpus covering empty, overflowing, invalid ISA,
capability, arity, and i64 inputs.

The same production parser implementation is compiled into a fuzz harness.
Linux CI performs 20,000 libFuzzer coverage-guided runs from a committed seed
corpus with ASan/UBSan enabled. macOS CI runs 20,000 deterministic sanitized
mutations of the identical harness because the current Xcode image does not
ship its libFuzzer runtime.

A separate `long-fuzz` workflow runs the Linux coverage-guided harness for five
minutes every Monday and can be started manually with a custom duration. It
uploads the evolved corpus plus any `crash-*`, `timeout-*`, or `leak-*` inputs
for 30 days even when fuzzing fails, so findings remain reproducible rather than
being lost with the runner.

Downloaded corpus artifacts are reviewed in dry-run mode before promotion:

```bash
scripts/review-fuzz-corpus.sh path/to/artifact/corpus --dry-run
scripts/review-fuzz-corpus.sh path/to/artifact/corpus --apply
```

Promotion accepts only non-symlink regular files no larger than 1024 bytes,
enforces aggregate limits, rejects untrusted filenames, deduplicates by content
SHA-256, and reruns the sanitized fuzz harness before copying new inputs under
canonical SHA-256 names.

Linux libFuzzer emits `:kotoba.fuzz-coverage/v1` summaries containing edge
coverage, feature count, and corpus count. CI compares them with the reviewed
baseline in `fuzz/baselines/native-parser.edn`. The baseline is bound to the raw
loader-source SHA-256, so a C change cannot silently reuse stale coverage
expectations. Linux runs use the fixed libFuzzer seed `424242`; current minimums
are cov 60, features 100, and corpus 20. The feature threshold intentionally
allows bounded differences between fixed-run and wall-time workflows.

The managed compiler boundary also runs 600 deterministic frontend mutations:
300 edits of a valid structured program and 300 raw grammar inputs. Any accepted
source must produce identical KIR across Wasm, x86-64, and AArch64,
byte-reproducible Wasm, and verifier-admitted native artifacts. Rejections must
use a controlled compiler phase rather than leaking host reader exceptions.

See [docs/architecture.md](docs/architecture.md) and
[docs/threat-model.md](docs/threat-model.md).
