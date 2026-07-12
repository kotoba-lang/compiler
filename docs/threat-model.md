# Threat model

Assume an adaptive autonomous attacker can generate unlimited malformed source,
artifacts, policies, and inputs; observe failures; exploit parser differentials;
exhaust CPU and memory; and attempt confused-deputy and supply-chain attacks.

Security invariants:

1. Reading source executes no reader-eval, macros, host interop, or loading.
2. Unsupported syntax and effects fail closed before code generation.
3. Native bytes contain only instructions accepted by the target verifier.
4. Generated code has no ambient filesystem, network, process, clock, random,
   environment, dynamic loading, or syscall authority.
5. Artifact identity covers target, compiler format, KIR, effects, limits, and
   exact code bytes.
6. Runtime admission is the intersection of signed delegation, local policy,
   artifact declaration, surface policy, revocation state, and resource limits.
7. Compiler success is not a security proof. Differential tests, fuzzing,
   reproducible builds, independent review, and OS process isolation remain
   required before hostile production execution.
8. Serialized artifacts, policies, keys, and receipts are byte- and
   structure-bounded before semantic verification; trailing forms, malformed
   UTF-8, and tagged EDN fail closed.
9. Source size is not the only compilation budget: function, ABI arity,
   binding, expression-node, and post-`let` lowering costs are bounded before
   backend allocation or code emission.
10. CLI outputs never expose partial artifacts through their final path;
    destination symlinks are not followed, and generated private keys require
    owner-only filesystem permissions.
11. A valid attacker-recomputed artifact hash grants no trust to embedded KIR;
    the verifier independently checks its AST, effect closure, ABI shape, and
    resource budgets before invoking a backend for byte-for-byte regeneration.
12. Receipt-chain verification authenticates every node against current
    executor trust/revocation state; an unkeyed hash chain or authenticated head
    cannot confer trust on a forged ancestor.
13. Signing-key admission proves the encoded private/public Ed25519 pair is
    consistent, while trust provisioning consumes a validated public-only key
    format and never requires distributing private material.
14. Public CLI failures expose one bounded machine-readable envelope, not JVM
    stack traces, host paths, rejected source forms, or unexpected exception
    messages; stable exit classes support fail-closed agent automation.
15. Versioned security objects reject unknown fields instead of silently
    interpreting subsets, and native oracle metadata is recomputed from sealed
    KIR rather than trusted as attacker-controlled descriptive data.

Arithmetic is specified independently of the JVM compiler host: i64
add/subtract/multiply wrap modulo 2^64, while invalid signed division traps. A
bounded KIR reference executor plus cross-backend boundary vectors guard against
validation and execution assigning different meanings to the same program.

Out of scope for the bootstrap: speculative-execution containment, a general
garbage collector, threads, dynamic dispatch, FFI, and arbitrary Clojure.

The conformance loader is a separate process with core dumps disabled, a
one-second CPU limit, a 64 MiB address-space limit on Linux, 1 MiB stack limit, and a
two-second wall alarm. Fatal arithmetic and memory signals become a structured
`KEXE_TRAP` failure. This is defense in depth for testing; it is not yet the
full aiueos namespace/seccomp/Landlock production sandbox.

Native capability authority is data, not ambient process privilege. Context v1
contains a fixed 256-bit bitmap and one callback slot. A literal cap ID selects
one bit; it cannot influence the callback address. Both generated code and the
callback reject a missing bit. The conformance loader materializes the bitmap
from an explicit allow list and tests allowed and empty-policy executions.

On Linux, the loader sets `no_new_privs` and installs a seccomp-BPF filter after
loading and changing code to RX but before guest entry. The allowlist contains
only process exit, structured signal handling, output, unmapping, and minimal
libc bookkeeping calls. Filesystem, network, process creation, and arbitrary
syscalls trap as `SIGSYS`. CI independently probes filesystem access, network
connection, and process creation and requires the machine-readable EDN report
`KEXE_TRAP {:kind :signal :signal :SIGSYS}`. Arithmetic, fuel, and capability
traps likewise report their exact OS signal without calling unsafe libc code
from the signal handler. The native backends deliberately use their own hard
trap instructions for generated-code policy denial: x86-64 `UD2` reports
`SIGILL`, while AArch64 `BRK` reports `SIGTRAP`.

On macOS, the child installs a deny-by-default Seatbelt profile after RX sealing
and before guest entry. It permits writing already-held result descriptors and
minimal self bookkeeping, but grants no file read, network, or child-process
authority. CI requires all three operations to fail with a structured
`:sandbox` denial. Seatbelt is a deprecated platform API and defense in depth,
not a substitute for an aiueos VM boundary.

The loader forks after loading and sealing the code mapping. Only the child
enters guest code and receives resource limits, trap handlers, and the OS
sandbox. The parent waits for that exact child and enforces an independent
three-second wall deadline. A stuck child is killed and reported as
`KEXE_TRAP {:kind :supervisor :reason :wall-timeout}`; CI tests this boundary
on both supported host architectures.
The fuel context is backed by a dedicated parent-created shared mapping. The
child can only monotonically consume its counter through regenerated code; the
supervisor reads the final counter and result slot after `waitpid`, producing
the evidence later bound into an executor-signed receipt.

The explicit `measure-runtime` provisioning step pins the reviewed loader source
by raw SHA-256 before invoking the C toolchain. It measures the resulting binary
and places the loader source, loader binary, resolved compiler binary, compiler
version, compiler-reported assembler, and compiler-reported linker hashes in
`:kotoba.native-runtime/v6`. The identity additionally binds the exact native
target profile for the measured host. The loader binary is published separately with
owner-only execute permission. The production `run` path requires both files,
checks exact schemas, trust membership, revocation, and binary hash, and never
starts `cc`. The
executor-signed receipt therefore identifies the exact runtime used. This is an
attestation and drift detector. Receipt verification always requires the pinned
reviewed loader-source identity; deployments can additionally make the complete
runtime hash an explicit trust allowlist and revoke identities independently.
The provisioning step also builds the same output twice
with fixed flags and requires byte-identical SHA-256 values before execution;
Linux build IDs are disabled, while macOS uses the deterministic UUID produced
for an identical output identity. This is not yet a hermetic or independently
reproduced binary build; compromise of the host compiler remains in scope for
the next supply-chain layer.
PATH lookup is performed once, canonicalized to an absolute real path, and that
exact executable is used for the version query and both builds. Its bytes are
hashed before execution and again after the second build. Thus a shadow
compiler cannot impersonate an approved runtime merely by copying a trusted
`--version` string, and a persistent replacement during measurement is rejected. This
detects persistent identity drift; a transient race, dynamically loaded
toolchain components, and a compromised compiler remain in scope until the
toolchain is hermetic.
Runtime v3 also queries `-print-prog-name=as` and `-print-prog-name=ld` under the
sanitized environment. Absolute reports and bare PATH names are accepted only
after canonicalization to executable regular files; relative paths containing
separators, multiline output, missing tools, and diagnostics are rejected. Both
binaries are hashed before and after the reproducibility build. This closes
straightforward assembler/linker shadowing, while compiler-integrated tools and
dynamically loaded components remain covered only indirectly by output identity.

Runtime v4 additionally queries `-print-file-name=include` and requires an
absolute compiler resource directory. A deterministic bounded manifest binds
all builtin header/resource relative paths, sizes, and content hashes before and
after both builds. Symlinks and special files fail closed, and file-count,
path-length, and aggregate-byte ceilings bound hostile traversal. This detects
changes to compiler builtin headers such as intrinsic and sanitizer definitions.
Platform SDK/system headers outside that directory and dynamic library closure
were previously unmeasured host dependencies.

Runtime v5 closes the header portion by compiling the loader once with `-MD`
under the same sanitized environment and production flags. A strict bounded
Make-dependency parser accepts escaped paths and continuations but rejects
missing separators, dangling escapes, NUL, oversized files/tokens, missing or
special dependencies, more than 10,000 canonical files, and more than 64 MiB.
The manifest includes the loader source and all compiler-selected builtin,
system, and platform SDK headers and is recomputed after both real builds.
Dynamic loader/library closure and transient filesystem replacement remain in
scope for a fully hermetic toolchain bundle.
The bootstrap also clears the complete inherited environment for every child.
The toolchain receives only deterministic locale/time/reproducibility values and
a minimal path rooted at the already-resolved compiler directory. The loader
receives only explicit execution protocol flags. Consequently attacker-set
include paths, linker paths, SDK selection, preload variables, credentials, and
proxy configuration cannot cross into measurement or guest execution. System
headers, linker binaries, compiler resource directories, and dynamic libraries
are still host dependencies and remain the next hermeticity boundary.
The bootstrap nevertheless treats every invoked process as potentially
unresponsive or noisy. Version queries and loader executions have five-second
Java deadlines, builds have thirty-second deadlines, and output capture is
capped at 1 MiB per stream (64 KiB for execution). Crossing either boundary
forcibly terminates the process and descendants. These host limits are
independent of the guest loader's internal supervisor and OS resource limits.

The verifier boundary has a deterministic mutation-fuzz corpus. Mutators cover
raw and attacker-resealed code changes, KIR identity/body divergence, exports,
ABI offsets, limits, target/format confusion, signature statements and keys,
receipt evidence, fuel accounting, unkeyed hash recomputation, and executor
signatures. CI records the seed and case count; any unexpected exception type
or accepted mutation fails the job and can be replayed from those values. This
complements, but does not replace, coverage-guided native fuzzing or an
independent audit.

The only guest heap objects currently admitted are immutable pairs. The loader
preallocates 4,096 cells and exposes no address to generated code. Handles are
validated against the current allocation frontier in runtime callbacks;
negative, zero, future, and exhausted allocations trap. Context-v2 callback
offsets, arena capacity, and the 64 KiB resource limit are sealed and
independently rechecked before code admission. Mutation fuzzing changes these
fields and requires rejection. This prevents host-memory corruption and
unbounded allocation, but it is not yet a tracing collector: memory is reclaimed
only when the entire supervised execution ends.

Safe list syntax does not introduce another object representation or runtime
entry point. Empty is zero and non-empty lists are pair chains; projection from
empty or forged tails reaches the same checked handle trap. Expansion occurs
before admission budgets, and a single list form is limited to 128 items.

The C loader's parser and valid execution path run under ASan and UBSan on both
host platforms in CI. Decimal parsing resets and checks `errno` for every
`strtoul`, `strtoull`, and `strtoll`, so values outside the declared unsigned or
i64 domains are rejected instead of silently saturating. The malformed corpus
also covers missing arguments, empty numbers, invalid ISAs, capability IDs and
lists, and arity mismatches. Leak detection is disabled because the macOS ASan
runtime does not support it; address and undefined-behavior detection remain
fatal.
The sanitizer build uses the compile-time-only `KEXE_SANITIZER_TEST` profile on
Linux to omit seccomp, because sanitizer runtimes require syscalls outside the
production allowlist. No runtime environment variable can disable seccomp in a
production loader. The ordinary conformance job separately compiles without
that macro and requires the filesystem `SIGSYS` probe to be denied.

`tools/kexe_parser_fuzz.c` includes the production parser translation unit, so
the fuzz target cannot drift into a separate reimplementation. Linux CI runs
libFuzzer with ASan/UBSan and a persistent seed corpus over capability lists,
strict unsigned decimal values, arity values, and signed i64 values. macOS uses
the same entry point with a deterministic xorshift driver under ASan/UBSan
because its Xcode toolchain advertises but does not ship the libFuzzer runtime.
Both modes run 20,000 cases per job; Linux additionally evolves inputs from
coverage feedback.

The weekly and manually dispatched `long-fuzz` workflow uses libFuzzer's wall
time limit rather than a fixed run count. Its temporary working corpus is copied
out on every exit, and GitHub Actions uploads it together with libFuzzer crash,
timeout, and leak artifacts even for failed runs. Artifacts are retained for 30
days. Promoting useful evolved inputs into the reviewed repository corpus is a
deliberate human change; CI never commits unreviewed bytes automatically.

`review-fuzz-corpus.cljs` runs under pinned NBB and treats downloaded artifacts as hostile. It rejects
symlinks, non-regular files, unknown named seeds, inputs over 1024 bytes, more
than 10,000 files, and aggregate content over 1 MiB. Dry-run is the default
review path. Explicit `--apply` first replays the entire imported corpus through
the sanitized harness, deduplicates against all existing seed contents, and
stores new inputs under their SHA-256 names. A CI self-test exercises filename,
symlink, size, and empty-corpus rejection.

Linux fuzz runs parse libFuzzer's terminal `DONE` record into a machine-readable
`:kotoba.fuzz-coverage/v1` artifact. Edge coverage, feature count, and corpus
count must meet a reviewed lower bound. The baseline includes the exact raw
SHA-256 of `kexe_loader.c`; source changes require an explicit baseline review
instead of silently inheriting old numbers. These values are regression alarms,
not a claim of complete path coverage. A fixed libFuzzer seed makes short-run
comparisons repeatable; the reviewed feature lower bound leaves headroom for
the smaller fixed-run corpus relative to the longer wall-time workflow.

Frontend fuzzing combines mutations of a valid multi-function source with raw
reader grammar bytes. Accepted inputs must converge on one KIR for all three
backends, produce deterministic Wasm, and pass native regeneration verification.
Rejected inputs must surface a controlled `:read`, `:subset`, `:admission`,
`:ir`, or `:verify` error. Before invoking tools.reader, a string-aware lexical
scan caps delimiter depth at 512; source type and 1 MiB size are checked, and
integer literals are restricted to the signed i64 domain. This prevents parser
stack pressure and host big-integer values from crossing into the runtime ABI.

## Browser host boundary

The browser host treats Wasm bytes, expected digests, capability policy, guest
arguments, capability results, and pair handles as attacker-controlled. Before
instantiation it copies and caps the bytes, computes their Web Crypto SHA-256,
checks the exact engine-reported import/export surface, and creates fresh
per-instance capability and heap closures. Forged, stale, zero, negative, and
future pair handles fail before indexing. Capability authority is a bounded u8
allow set and is rechecked at the dynamic call boundary.

The host deliberately provides no ambient browser object. This prevents a guest
module from naming DOM, network, storage, clock, randomness, or module-loading
APIs through Wasm imports. It does not claim to defend against a compromised
JavaScript realm, browser engine, Web Crypto implementation, or embedding page:
those are trusted computing base. The embedding application must authenticate
the expected artifact digest and must not translate `unexpected-host-error`
into attacker-visible exception details. Worker isolation, CSP, cross-origin
isolation, browser-version testing, and denial probes remain required before the
web target can be promoted from experimental evidence to release coverage.

Worker messages are also hostile. The v1 protocol rejects unknown fields,
unbounded or malformed correlation IDs, non-i64 arguments, unsupported
operations, and overlapping execution. It never accepts source URLs, JavaScript
functions, capability implementations, arbitrary export names, or diagnostic
verbosity. The worker URL and install-time capability Map remain embedding TCB.
The recommended CSP admits only same-origin scripts/workers and the narrower
`'wasm-unsafe-eval'` compilation sink; it excludes blob/data workers and
JavaScript `'unsafe-eval'`. Network fetch remains embedding authority rather
than guest authority.

Browser fixture bytes are compiled during the matrix run, live in a fresh
temporary directory, and are exposed only through a fixed route table. The
server does not map request paths onto arbitrary filesystem paths and rejects
non-GET methods. All successful fixture responses carry CSP, same-origin CORP,
no-referrer, nosniff, and no-store headers. Browser automation is not counted as
a sandbox proof: a compromised browser engine remains TCB, and mobile device
emulation does not exercise a mobile kernel, hardware, JIT policy, or vendor
browser build.

The browser evidence reporter and its output are not trusted merely because
tests passed. A separate NBB gate requires the closed receipt schema, exact
expected project set, version syntax, evidence classes, and GitHub commit/run
binding before upload. The uploaded artifact is still unsigned CI output and
can expire; it cannot satisfy platform-release evidence or the worldwide 95%
claim. Branded Chrome/Edge now run on Linux and Windows, with the runner
platform bound into the receipt. These runs still do not establish
previous-version compatibility, update-channel integrity, macOS behavior,
mobile coverage, or physical-device isolation.

SafariDriver is an additional CI TCB and is enabled only on the ephemeral macOS
runner. The controller does not accept remote URLs, arbitrary selectors,
scripts, or capability handlers: its ports, routes, selectors, session count,
and timeouts are fixed in reviewed code. Failure text is not placed in the
evidence receipt. Safari evidence remains a hosted-runner product test, not
proof about every macOS release or Apple device.

The macOS 14 Safari run demonstrates that CSP semantics cannot be assumed from
the WebKit engine run: Safari permits Wasm compilation when
`'wasm-unsafe-eval'` is omitted. Evidence v2 binds this as
`cspWasmEnforced=false`; the verifier requires the divergence rather than
silently accepting a missing test. Consequently no Kotoba security decision
may rely on CSP blocking unreviewed Wasm bytes.

## Windows supervisor boundary

The experimental Windows loader never creates RWX memory. It byte-caps and
copies verifier-extracted code into RW storage, changes it to RX, flushes the
instruction cache, and enables the process dynamic-code prohibition before
guest entry. The hidden context is loader-owned and all capability and pair
callbacks use reviewed fixed slots. Job active-process limits and a
low-integrity restricted impersonation token are checked by negative process
and filesystem probes on the Windows runner.

This boundary is not yet sufficient for release admission. The product command
now admits the signed KEXE, verifies its regenerated code, binds the reviewed
Windows source plus compiler/linker/resource/header closure into runtime trust,
and passes only extracted code to the measured loader. Windows CI verifies the
result receipt and rejects both loader-byte mutation and OS-profile
substitution. Guest traps still terminate the loader process rather than a
separately supervised child. Network denial is not implemented and
Job/restricted-token behavior has only hosted-runner evidence. Windows Arm64,
Authenticode/MSIX, SBOM, and provenance gates also remain; these omissions keep
Windows execution out of release coverage accounting.
