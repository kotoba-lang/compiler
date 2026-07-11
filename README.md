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

The native verifier treats embedded KIR as hostile even when an attacker has
recomputed every unkeyed hash. It independently validates the KIR AST, lexical
scope, call arities, transitive capability effects, ABI limits, node/depth
budgets, and `let` expansion cost before regenerating and comparing machine
code.
KEXE, signed envelopes/statements, trust policies, capability policies,
runtime identities, signing/verification keys, receipts, and receipt fuel maps
all use exact versioned schemas: unknown fields are rejected rather than
ignored. For pure KEXE, the verifier also re-executes `main` with the normative
KIR interpreter and requires sealed `:value` metadata to match; effectful KEXE
must carry no oracle value.

The current experimental slice supports pure integer functions, parameters,
direct calls, sequential `let`, `if`, arithmetic, comparisons, and immutable
`pair` / `pair-first` / `pair-second` values. It emits
executable Wasm with real runtime parameters, locals, calls, and branches, plus
verified runtime functions for x86-64 and AArch64. KEXE seals its
target, KIR identity, effects, resource limits, and exact code bytes with
SHA-256. Pair allocation is the sole admitted heap operation; general objects,
mutation, indirect control flow, and OS ABI emission fail closed until their
verifier rules exist.

Pair storage is a fixed 4,096-cell (64 KiB) arena per execution. Handles are
one-based integers validated on every access; zero, negative, future, and
out-of-range handles trap before an address is formed. Allocation is monotonic,
immutable, and traps at capacity—there is no fallback to host allocation and no
GC pause or unbounded growth. The normative KIR executor enforces the same
capacity. Native code reaches only three fixed context-v2 callbacks at sealed
offsets; the loader owns the arena. Wasm uses equivalent `kotoba:heap` imports,
whose host implementation must enforce the same contract.

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
All CLI outputs are written to a same-directory temporary file, forced to disk,
then atomically renamed. Destination symlinks are replaced
rather than followed, and generated Ed25519 private-key files are explicitly
owner-readable/writable only (`0600`).
Verification requires an explicit trusted-signer set, checks signer/artifact
revocation and time validity, then runs the normal KEXE verifier.

```bash
kotoba -M keygen --output key.edn
kotoba -M public-key key.edn --output verification-key.edn
kotoba -M trust-key verification-key.edn --output trust.edn
kotoba -M sign app.kexe --key key.edn --expires 2000000000 --output app.signed.kexe
kotoba -M verify-signed app.signed.kexe --trust trust.edn
```

`keygen` proves that the encoded Ed25519 private and public keys form one pair
before any signing operation. `public-key` emits a separate
`:kotoba.verification-key/v1` without private material; `trust-key` validates
its algorithm, encoding, fingerprint, and exact shape before provisioning
trust. Direct provisioning from a validated signing key remains supported for
bootstrap compatibility but is discouraged outside local setup.

Verified executions can produce `kotoba.run-receipt/v1`. Its hash binds the
signed envelope and artifact, signer, target/entry, required effects, exact
policy admission, input/output hashes, fuel accounting, status, time interval,
and optional parent receipt. Verification repeats current signature, trust,
revocation, policy, and artifact checks before accepting the evidence. The
receipt hash is itself signed by a trusted executor key; a hash chain alone is
not treated as proof that execution occurred.

`kotoba -M verify-chain chain.edn --trust trust.edn` requires every node to have
a currently trusted, non-revoked executor signature and returns the explicit
scope `:executor-attested-chain/v1`. It verifies provenance and linkage; full
execution evidence still uses `verify-receipt` with the envelope, policy, input,
and result. Creating a child receipt likewise refuses an unattested parent.

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

Failures emit exactly one EDN value on stderr and no host stack trace:

```clojure
{:format :kotoba.cli-error/v1
 :ok false
 :error :decode
 :message "EDN input contains trailing forms"
 :details {:phase :decode}}
```

Exit codes are stable by boundary: `64` usage, `65` rejected input/compiler or
verifier data, `69` execution setup, `74` output I/O, `76` receipt, `77`
signature/trust/runtime identity, `70` redacted internal failure, and `120` for
a measured guest trap.

`kotoba -M run` is the admitted native execution path. It verifies the signed
KEXE envelope and current trust/revocation state, checks local capability
policy, requires host ISA and entry arity to match, then invokes the supervised
loader. The command writes the measured result separately and creates an
executor-signed receipt using the supervisor's actual post-execution fuel
counter; callers cannot supply result, status, timing, or fuel values.
The result evidence also binds the pinned loader-source hash, the exact loader
binary hash, the resolved C compiler executable's byte hash, and its version
output hash. A source mismatch is denied
before compilation, and the executor signature makes the runtime identity part
of the receipt's output evidence.

For high-assurance verification, provision the measured runtime from a reviewed
build into the trust policy before any guest execution. The measured loader is
published owner-only and executable; `run` hashes that exact file and never
invokes a C compiler:

```bash
kotoba -M measure-runtime --output runtime.edn --loader-output kotoba-loader
kotoba -M trust-runtime runtime.edn --trust trust.edn --output pinned-trust.edn
kotoba -M run app.signed.kexe --trust pinned-trust.edn \
  --runtime runtime.edn --loader kotoba-loader ...
kotoba -M verify-receipt run.receipt.edn --trust pinned-trust.edn ...
```

The pin covers the reviewed loader source, reproduced loader binary, compiler
binary, and compiler version output. `cc` is resolved once to an absolute real
path; both builds use that path, and its bytes are re-hashed after the second
build to detect persistent replacement during measurement. Native execution always requires an explicit
`:trusted-runtime-sha256` membership; an absent or empty set denies every
runtime. Runtime revocation uses `:revoked-runtime-sha256`. Measurement is a
deliberate provisioning operation and still executes the local toolchain, so it
belongs in a controlled build environment rather than on an exposed executor.
Every spawned process has a Java-side wall deadline and separately bounded
stdout/stderr capture. A hanging or output-flooding compiler or loader is killed
together with its descendants. Toolchain builds allow 30 seconds and 1 MiB per
stream; admitted execution allows 5 seconds and 64 KiB per stream in addition
to the loader's internal three-second supervisor deadline.

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
