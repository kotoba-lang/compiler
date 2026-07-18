# Architecture decision: one compiler, multiple verified backends

Status: accepted, 2026-07-11.

`kotoba-lang/kotoba` owns language semantics. This repository owns compilation.
`aiueos` owns process isolation, capability brokerage, loading, and receipts.

The shared safety pipeline precedes every backend. A backend cannot weaken the
source subset, inferred effects, resource bounds, or capability requirements.
Every artifact records its target, KIR digest, declared effects, limits, and
code bytes. A structurally independent target verifier decodes all emitted
instructions before admission.

Native verification does not trust a sealed KIR merely because its hash is
valid. Before re-emission, an independent KIR profile checker validates exact
module/function shapes, i64 AST operations and arities, lexical bindings, call
targets, function/module effect closure, expression depth/count, and symbolic
post-`let` lowering cost. Thus an attacker-resealed KEXE cannot bypass frontend
budgets or hide a capability call inside falsely pure KIR.

All versioned security objects use exact key sets. KEXE, signature envelopes
and statements, trust policies, keys, runtime identities, receipts, and nested
fuel records reject unknown fields, including after an attacker recomputes
unkeyed hashes. Capability policies likewise accept only an optional set-valued
`:allow`. For pure native programs, verification recomputes sealed oracle
metadata through the normative KIR executor; effectful programs require nil.

Targets are versioned contracts:

- `wasm32-kotoba-v1`: portable sandbox target and conformance oracle.
- `wasm32-wasi-kotoba-v1`: sealed server target with no ambient WASI imports.
- `x86_64-kotoba-v1`: direct x86-64 instructions in a sealed KEXE container.
- `x86_64-windows-kotoba-v1`: the same regenerated x86-64 instruction subset,
  sealed to Windows, the `kotoba-sysv-v1` internal ABI, and the measured
  Windows supervisor.
- `aarch64-windows-kotoba-v1`: direct AArch64 instructions sealed to Windows,
  the `kotoba-aapcs64-v1` ABI, and the same measured supervisor boundary.
- `aarch64-kotoba-v1`: direct AArch64 instructions in a sealed KEXE container.
- `aarch64-android-kotoba-v1`: Android AArch64 code sealed to the future
  isolated application host.
- `aarch64-ios-kotoba-v1`: iOS/iPadOS AArch64 code sealed to a signed static
  host; this profile does not imply runtime JIT permission.

Each emitted result also carries `:kotoba.target-profile/v1`, binding execution
kind, ISA, OS, ABI, and supervisor/host runtime. Explicit Linux, macOS,
Windows, browser, and WASI target names have exact profiles; legacy ISA-only aliases use
`:os :unspecified`. Native verification reconstructs the expected profile from
the target name before regenerating code. The executor additionally requires
the sealed OS to equal its host OS. Changing only OS, ABI, or runtime and
re-sealing the unkeyed artifact is rejected.

Android and iOS currently stop at this artifact boundary. Their regenerated
instruction bytes are intentionally equal for the admitted AAPCS64 subset, but
OS and runtime identity make their seals unequal. No desktop loader is allowed
to execute either profile; mobile host libraries and physical-device admission
remain separate gates.

The first Android library slice exposes only
`kotoba_android_execute_verified_v1`. Its request is closed to the Android
target, at most 1 MiB of already-verified code, five arguments, a 256-bit
capability bitmap, and the fixed context v2 layout. NDK CI requires a
reproducible AArch64 ELF with NX stack, RELRO, `BIND_NOW`, and no additional
exports. Code transitions RW to RX and never uses RWX. The library does not
authenticate or independently regenerate KEXE and must run inside a separately
configured Android isolated process; those are product integration gates.
An optional NBB/adb conformance path builds a PIE harness and executes the
extracted verified entry on an attached Arm64 Android device. Hosted emulator
boot success is not part of the trust claim; physical-device evidence and an
Android isolated application process remain required.

The iOS AOT packager first runs the independent KEXE verifier, then converts the
admitted code vector into canonical assembly in `__TEXT,__text`. A separate
`__TEXT,__const` symbol carries the exact iOS target identity. The sealed
manifest binds artifact, code, entry offset/arity, and assembly digests. The
static C host owns the same context v2 callbacks and calls only the link-time
entry symbol; it contains no `mmap`, `mprotect`, JIT entitlement, or writable
code transition. Xcode CI verifies deterministic Arm64 Mach-O objects and
archives, but final app signing and device execution are outside this gate.

KEXE is not mapped by this compiler. The loader must reverify, compare the
policy/artifact digest, allocate writable non-executable memory, copy code,
change it to read+execute, and never map it writable again. No generated code
may contain a syscall instruction. External effects will be reachable only
through fixed, capability-checked loader trampolines.

The experimental slice intentionally accepts less than the current Kotoba Wasm
compiler. Migration proceeds by moving frontend rules and conformance vectors
from `kotoba-lang/kotoba`, never by silently accepting unsupported forms.

Frontend admission is bounded before semantic analysis: source must be a string
no larger than 1 MiB, lexical delimiter nesting is capped at 512 before reader
recursion, top-level form count is capped, expression validation is capped at
256, and literals must fit signed i64. Host reader failures are normalized into
the compiler's `:read` error phase.

The safe profile additionally caps functions (1,024), parameters per exported
function (5), bindings per `let` (4,096), symbol length (128), and total
expression nodes (50,000). A separate symbolic cost pass caps the `let`-elided
program at 100,000 nodes before native substitution occurs, preventing compact
source from causing exponential code generation. The five-argument limit is a
frontend ABI contract shared by Wasm and both native targets.

The CLI treats serialized control-plane objects as hostile too. KEXE, signing
keys/envelopes, trust stores, policies, inputs, and receipts share a strict UTF-8
EDN decoder with an 8 MiB byte ceiling, depth 128, token length 4,096, decoded
node count 200,000, and string length 1 MiB. Exactly one form is required;
tagged literals and trailing forms fail before verifier or crypto code runs.

CLI publication uses a single atomic-output boundary for Wasm, KEXE, keys,
trust policies, results, and receipts. Bytes are written and `fsync`ed in a
same-directory temporary file before atomic replacement, so crashes do not
publish partial artifacts and destination symlinks are not followed. Private
key temporaries and final files carry POSIX mode `0600`; key generation fails
closed where owner-only permissions cannot be established.

The public command boundary normalizes expected failures into one
`kotoba.cli-error/v1` EDN line with a stable phase-derived exit code. Only an
allowlist of safe diagnostic fields crosses that boundary; source forms, local
paths, causes, stack traces, and unexpected host exception messages are
redacted. Unexpected throwables become a generic exit-70 internal failure.

## Current maturity

The compiler is `experimental alpha`, not production-safe. KIR v3 retains
multiple pure functions, runtime arguments, lexical `let`, `if`, integer
arithmetic, comparisons, direct calls, and immutable bounded pairs. Wasm lowers that runtime KIR to real
locals, calls, and structured control flow. Both native backends emit general
verified control flow for this subset and execute through the supervised W^X
loader. General allocation, tracing GC, and a production-strength VM sandbox
remain absent.

Windows x64 has entered Phase 2 at the artifact and measured-runtime boundaries. The CLI accepts the
explicit target, KEXE identity differs from Linux/macOS despite equal code
bytes, the independent verifier regenerates it, and Windows CI requires two
compilations to be byte-identical. Runtime identity selects an OS-specific
reviewed source digest, so `measure-runtime` / `run` cannot confuse the POSIX
loader with the Windows supervisor profile.

The reference Windows x64 supervisor has now crossed the execution boundary in
CI without changing that product fail-closed rule. `VirtualAlloc(PAGE_READWRITE)`
is followed by `VirtualProtect(PAGE_EXECUTE_READ)` and
`FlushInstructionCache`; `ProcessDynamicCodePolicy` is then set to prohibit new
executable mappings. The generated code retains its verified
`kotoba-sysv-v1` convention, entered through a six-slot Clang `sysv_abi`
adapter whose final register is the hidden `r9` context. Context v2 callbacks
use the same ABI and fixed offsets as regeneration.

Before guest entry, the loader joins a Job Object with active-process limit one,
creates a max-privilege-disabled low-integrity restricted token, duplicates it
as an impersonation token, and applies it to the execution thread. Windows CI
executes arithmetic/calls, structured fuel, bounded heap, capability
allow/deny, and fixed filesystem/process probes. Product conformance also
measures the compiler, assembler/linker, resource directory, and complete
header closure; trusts that runtime; executes a signed artifact through
`kotoba -M run`; and verifies its receipt. Loader-byte mutation and OS-profile
substitution are rejected before guest entry. Child-process trap isolation,
network denial, Windows Arm64, and release packaging remain outstanding.

## Bounded pair arena

`pair`, `pair-first`, and `pair-second` are the first admitted heap contract.
Each run owns exactly 4,096 immutable two-i64 cells (65,536 payload bytes).
Allocation returns a one-based handle, never a host pointer. Every read checks
that the handle is positive and no greater than the monotonically published
cell count before selecting a slot. Exhaustion and invalid handles trap; cells
cannot be mutated, freed, or reused during a run.

Native context v2 retains fuel and capability offsets and adds fixed callbacks
at 56, 64, and 72 bytes for allocation and the two projections. Both native
backends emit calls only through those regenerated offsets. The loader
implements the callbacks over shared bounded storage so the supervisor reports
exact `{:capacity 4096 :used n}` accounting. Wasm modules import the equivalent
three functions from `kotoba:heap`; conformance supplies a bounded host arena
and checks valid allocation plus forged-handle rejection. This arena is
deterministic bounded allocation, not a general-purpose or tracing GC.

The frontend lowers `(list a b)` to `(pair a (pair b 0))`, `cons` to `pair`,
`first`/`rest` to the two checked projections, and `empty?` to comparison with
zero. KIR therefore contains only the independently verified pair contract.
List literals admit at most 128 items and their expanded trees are charged to
the ordinary expression and lowering budgets.

`second` composes the checked tail and head projections. `not`, `zero?`,
`pos?`, and `neg?` lower to existing signed i64 comparisons, preserving the
language rule that zero is false and every nonzero value is true.

KIR v3 also has a normative bounded reference executor
(`kotoba.compiler.ir/execute`). All values are signed i64 bit patterns.
Addition, subtraction, multiplication, and unary negation wrap modulo 2^64,
matching Wasm i64 and both native ISAs. Invalid division traps, and each
function entry consumes one unit of non-replenishable fuel. Conformance runs
boundary vectors through Wasm and the host-native backend to detect semantic
drift from this contract.

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

Every emitted Wasm module carries one `kotoba.target` custom section. The WASI
host requires the canonical value `wasm32-wasi-kotoba-v1` before using the
closed capability/heap adapter. It supplies no `wasi_snapshot_preview1`
namespace, so filesystem, network, clock, random, environment, and process
authority must be separately declared and metered in any future profile.
The portable semantic subset is executed independently by Node/V8 and pinned
Wasmtime. Wasmtime receives no preopened directories, environment adapter, or
capability imports; pure results, runtime arguments, and recursive fuel traps
must agree with the product host vectors. Linux x86-64, Linux Arm64, and macOS
Arm64 are distinct CI execution profiles; the Linux Arm64 profile additionally
executes the native AArch64 loader and sanitizer/fuzz boundaries.

`runtime/wasi-service.mjs` is the first service lifecycle adapter. Startup
admits the sealed module digest and target; every request instantiates a fresh
module so the private fuel global and pair arena cannot leak across tenants.
The HTTP schema is exact and bounded. The Kubernetes profile fixes two replicas,
rolling availability, non-root IDs, read-only rootfs, RuntimeDefault seccomp,
zero Linux capabilities, no mounted service-account credentials, probes, and
resource requests/limits. CI deletes a serving pod and requires replacement
plus continued deterministic execution.
Each execution is additionally isolated in a Worker thread. Completion,
engine error, guest trap, abnormal worker exit, and a one-second deadline all
converge on one bounded response; timeout termination cannot block the HTTP
event loop or consume the next request's fuel/heap.
Prometheus text metrics expose fixed counters/gauges and one module-info series.
No attacker-controlled entry name, argument, result, error text, or capability
value becomes a label. Kind conformance checks the module digest and worker
gauge through this endpoint.

The release layer emits deterministic SPDX 2.3 tag/value with a fixed creation
epoch and content-derived namespace. A release statement binds raw artifact and
SBOM names, SHA-256 digests, sizes, exact target profile, builder identity,
signer, and validity interval. Ed25519 covers the canonical statement. Admission
regenerates SPDX from the artifact rather than trusting an arbitrary file with
an `.spdx` suffix, then applies signer/artifact revocation.

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

## Browser Wasm host

`runtime/browser-host.mjs` implements the first product host for
`wasm32-browser-kotoba-v1`. It is a browser-native ES module because that file
is itself the distributable runtime; repository orchestration and gates remain
NBB programs. Admission copies at most 1 MiB of input, computes SHA-256 through
Web Crypto, validates an optional expected digest, compiles the module, and
examines the engine-reported import and export descriptors before instantiation.

The complete admitted import identity is the tuple `(module, name, kind)` for
`kotoba:cap/call` and the three `kotoba:heap` pair functions. Every other import
is rejected. `main` must be a function and no memory, table, or global may be
exported. The host creates a fresh private 4,096-cell immutable arena for each
instance and validates every handle. Capability IDs are a unique bounded u8
set; the host policy is checked again on every dynamic call and implementations
must return an i64-compatible `bigint`.

The API accepts only `expectedSha256`, `allowCapabilities`, and `capCall`.
Unknown options fail closed so later additions cannot silently acquire ambient
authority. The host does not construct imports for DOM, fetch, storage, time,
randomness, threads, or dynamic linking. Stable trap normalization distinguishes
policy/host rejection, guest Wasm traps, and unexpected host failures without
exposing engine messages. A Worker wrapper, CSP deployment profile, and real
browser release matrix remain Phase 1 roadmap work.

The Worker host wraps that same admission boundary in a closed
`kotoba.worker-request/v1` one-shot protocol. It accepts only `run`, a bounded
request ID, module bytes and digest, an allowlist, and up to five i64 `bigint`
arguments. Execution is serialized per worker; a concurrent request is denied
instead of creating another arena or authority set. Results use
`kotoba.worker-response/v1` and contain no stack, engine message, source, or
path. Capability implementations are copied from an install-time trusted Map
inside the static worker entry. They cannot be transferred through the message
channel. The stock entry therefore runs only pure modules.

The browser matrix is built from compiler output on every run and served by a
closed-route NBB HTTP fixture with the production CSP headers. Playwright drives
pinned Chromium, Firefox, and WebKit engines plus two mobile emulation profiles.
Every project must execute direct and Worker admission, install-time capability
allow/deny, heap accounting, forged-handle rejection, and a negative page whose
CSP intentionally omits `'wasm-unsafe-eval'`. The latter must receive a
`WebAssembly.CompileError`. Engine results are kept distinct from branded
browser, OS, and physical-device release evidence.

CI conditionally adds the branded `chrome` and `msedge` Playwright channels on
ephemeral Linux and Windows runners. Browser orchestration invokes the pinned
NBB CLI through the pinned Node executable rather than a POSIX command shim, so
fixture compilation, serving, and evidence validation share one cross-platform
control path. A custom reporter emits
`kotoba.browser-engine-evidence/v2`; an independent NBB gate rejects missing or
extra projects, malformed versions, schema drift, host-OS drift, and commit/run identity
mismatch. The receipt distinguishes `engine`, `mobile-emulation`, and
`branded-browser` evidence and is retained for 30 days. It is intentionally not
accepted by the signed worldwide-coverage manifest until browser/OS/version
window and trust requirements are implemented.

Actual Safari uses a separate path because Playwright WebKit is not the Safari
product. NBB compiles the same temporary artifacts and starts the closed-route
fixture; a bounded WebDriver client launches `safaridriver`, creates exactly one
Safari session, navigates only the two loopback fixture URLs, polls only fixed
CSS selectors, and closes the session and child processes in `finally`. The
result is verified through the same closed evidence schema with project
`safari-stable-macos` and platform `darwin`.

Evidence v2 additionally carries the closed boolean property
`cspWasmEnforced`. The Playwright projects prove and report `true`; actual
Safari reports the observed `false`. This is not normalized away. Runtime
admission is always the digest/import/export/capability/heap boundary, while CSP
remains a separately measured defense-in-depth property.

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
The Linux child uses `no_new_privs` plus a seccomp-BPF syscall allowlist. The
macOS child uses a deny-by-default Seatbelt profile. Conformance independently
probes filesystem, network, and process creation denial on both OS families.
The context and result slot live in a small anonymous shared mapping, allowing
the parent to attest the post-execution fuel counter without trusting text from
the child. With structured reporting enabled, a successful execution produces
an EDN value containing status, result, and initial/remaining fuel.
Fuel is read after all transitive generated calls return, so it records the
actual dynamic charge count rather than a caller-supplied estimate.

The public `kotoba -M run` path accepts only a signed envelope plus current
trust, a measured runtime manifest and matching loader, local policy, typed
argument input, and an executor key. It verifies and
admits before extracting bytes, rejects a target/host or entry/arity mismatch,
and converts only the supervisor report into result evidence. That evidence and
its measured interval and fuel counters are passed directly to receipt signing;
there are no CLI flags for supplying those fields.

## Signed artifact admission

The internal SHA-256 seal detects accidental mutation but is not authenticity:
an attacker can recompute it. `kotoba.signed-kexe/v1` therefore carries an
Ed25519 statement over artifact SHA, signer fingerprint and X.509 public key,
not-before, and expiry. Admission verifies the signature, explicit trusted
signer membership, signer/artifact revocation sets, and the validity interval,
then invokes the ordinary KEXE verifier. Revocation remains outside immutable
artifact identity and can change without rewriting the artifact.

Signing keys and verification keys are distinct serialized formats. A signing
key is accepted only after a fixed domain-separated challenge signed by its
PKCS#8 private key verifies under its X.509 public key, proving that encoded
halves match. `public-key` strips private material into
`kotoba.verification-key/v1`; trust provisioning validates the public encoding,
algorithm, exact fields, and fingerprint before recording the signer ID.

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
Chain verification requires a current trust policy and verifies the executor
attestation on every node, not only the head. Its result is deliberately scoped
as `:executor-attested-chain/v1`: artifact/policy/input/output replay remains the
job of full per-receipt verification. Receipt creation also validates a parent
attestation before accepting its hash as a link.
Native result evidence is schema-checked against the pinned loader source.
Trust policies may contain `:trusted-runtime-sha256` and
`:revoked-runtime-sha256`. Native execution requires membership in the strict
allowlist over the loader source, loader binary, compiler binary, and compiler
version tuple, plus the compiler-selected assembler and linker binaries;
absence and an empty set both deny. Provisioning builds twice and publishes a
measured owner-executable loader. Execution consumes that exact binary, checks
its hash before entry, and does not invoke the C toolchain.
Runtime identity v6 adds the explicit host target profile to that measured
closure. The executor compares artifact, runtime, and actual host independently:
legacy artifacts may have an unspecified OS, but a measured runtime never may.
Runtime ISA, ABI, OS, and supervisor profile must exactly describe the host.
The checked loader bytes are copied into a private execution directory before
launch, closing replacement between hash verification and process creation.
All host subprocesses have two independent resource boundaries: a wall-clock
deadline and capped stdout/stderr readers. Limit breach forcibly terminates the
process tree. This applies during untrusted toolchain measurement as well as
measured loader execution, preventing a compiler from retaining the bootstrap
JVM indefinitely or exhausting its heap with streamed diagnostics.
`ProcessBuilder` environments are cleared before launch. Compiler queries and
builds receive a closed allowlist (`PATH`, `LANG`, `LC_ALL`, `TZ`,
`SOURCE_DATE_EPOCH`, `ZERO_AR_DATE`); loader execution receives only explicit
protocol flags. Ambient include/library paths, SDK overrides, preload hooks,
credentials, proxies, and user configuration are therefore absent from both
boundaries.
Runtime v4 queries the compiler's builtin include directory and hashes it as a
`kotoba.directory-manifest/v1`: sorted relative path, byte length, and content
SHA-256 for every regular file plus aggregate bytes. Traversal rejects links,
special files, over 20,000 entries or 10,000 files, overlong paths, and more than 64 MiB before
reading file contents. The manifest is recomputed after the second build.
Runtime v5 performs a separate `-MD` compile with the production C flags and
parses its Make dependency file through a bounded escape-aware parser. The
resulting `kotoba.dependency-manifest/v1` sorts canonical real paths and binds
path, size, content SHA-256, and aggregate bytes for the loader source plus all
actually selected SDK/system headers. Depfile bytes, token length, dependency
count, and aggregate referenced bytes are capped before content hashing. The
closure is re-hashed after the reproducibility build.
