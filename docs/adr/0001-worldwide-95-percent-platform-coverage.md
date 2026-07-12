# ADR 0001: Worldwide 95% platform coverage plan

Status: accepted roadmap, 2026-07-12.

## Context

Kotoba currently emits three target families:

- `wasm32-kotoba-v1` for a capability-supplied WebAssembly host;
- `x86_64-kotoba-v1` KEXE machine code;
- `aarch64-kotoba-v1` KEXE machine code.

The supervised native runtime is implemented and continuously tested on Linux
and macOS. Windows, Android, iOS, ChromeOS packaging, WASI, embedded targets,
GPUs, and NPUs are not current product support. An instruction-set backend is
not sufficient to claim platform support: the OS ABI, loader, sandbox,
capability bridge, signing, packaging, tests, and operational support all form
part of a target.

This ADR defines a plan to make both native Kotoba products and Kotoba Wasm
available to at least 95% of the measured worldwide endpoint population, while
adding accelerators without allowing generated code ambient device access.

## Decision

We will retain one verified KIR and separate target identity into execution
format, ISA, OS, ABI, and runtime profile. We will pursue coverage in descending
population and implementation-leverage order: Web, Windows, mobile, server,
then the long tail of embedded devices. GPU and NPU execution will be explicit
bounded accelerator effects, not ambient extensions to CPU machine code.

The 95% goal is a release gate, not a statement of current maturity.

## Coverage definition

No single dataset represents browsers, phones, PCs, servers, industrial
controllers, and disconnected embedded devices. Coverage will therefore be
reported as a vector, never as one unsupported global percentage.

The product goal requires all of these gates:

1. **Interactive endpoints:** supported OS families account for at least 95% of
   identifiable worldwide Web usage in the trailing three-month average of a
   published dataset. Unknown observations are excluded from the denominator
   and reported separately.
2. **Web:** the tested browser matrix accounts for at least 95% of identifiable
   worldwide browser usage, with no browser counted through native support.
3. **Mobile:** Android and iOS native hosts are both release-supported; their
   current and previous major OS versions pass device tests.
4. **Desktop:** Windows, macOS, Linux, and ChromeOS have a supported execution
   path. Windows x64/Arm64 and macOS Arm64 are mandatory native paths.
5. **Server:** Linux x86-64 and AArch64 are native release targets and the WASI
   profile passes at least two independent runtimes.
6. **IoT/edge:** coverage is reported separately by admitted hardware profile;
   no numeric worldwide IoT claim is made until a defensible installed-base
   dataset exists.
7. **Accelerators:** GPU and NPU availability is reported by verified provider,
   device family, and fallback behavior, separately from CPU endpoint coverage.

A platform counts only after conformance runs on physical hardware or a vendor
supported CI/device service. Compilation alone, emulation alone, or an ISA match
does not count.

Every quarterly coverage report must pin the dataset URL, retrieval date, raw
input digest, normalization program version, supported target versions, and
calculation result so the percentage is reproducible.

## Target taxonomy

Target names will evolve from ISA-only aliases to explicit product profiles:

```text
wasm32-browser-kotoba-v1
wasm32-wasi-kotoba-v1

x86_64-linux-kotoba-v1
x86_64-windows-kotoba-v1
x86_64-macos-kotoba-v1

aarch64-linux-kotoba-v1
aarch64-windows-kotoba-v1
aarch64-macos-kotoba-v1
aarch64-android-kotoba-v1
aarch64-ios-kotoba-v1

riscv64-linux-kotoba-v1
riscv64-baremetal-kotoba-v1
thumb2-cortexm-kotoba-v1
```

The existing three names remain compatibility aliases until signed artifact,
policy, receipt, and trust schemas can migrate without target confusion.

## Delivery plan

### Phase 0 — measurement and target identity

- Add a versioned coverage manifest and reproducible calculation tool.
- Split ISA, OS, ABI, object/container format, and runtime profile in schemas.
- Define compatibility and downgrade rules for old KEXE target aliases.
- Publish a support matrix distinguishing `experimental`, `preview`, and
  `release`.

Exit gate: forged OS/ABI/profile substitutions fail verification; a coverage
report can be reproduced byte-for-byte from committed inputs.

Implementation status: the exact-schema coverage manifest, raw-dataset digest
verification, basis-point calculator, committed first snapshot, and
`kotoba -M coverage` command are implemented. Coverage claims now require
trusted, unexpired Ed25519 conformance evidence binding the platform, execution
paths, target profiles, runtime, conformance output, and CI run. Target identity
is now split across execution kind, ISA, OS, ABI, and runtime profile for the
existing browser/Linux/macOS matrix. Compatibility migration and future
platform profiles remain open Phase 0 work.

### Phase 1 — Web coverage

Implementation status: the dependency-free ES module host now enforces the
exact v1 import schema, optional SHA-256 artifact identity, a private bounded
pair heap, runtime capability checks, closed options, export restrictions, and
deterministic trap classes. Conformance executes compiler-produced Wasm through
this product host and includes forged-import, forged-handle, policy, heap, and
digest denial vectors. The closed one-shot module Worker host and restrictive
same-origin CSP profile are also implemented and gated. This is Phase 1
progress, not its exit gate. Pinned Chromium, Firefox, and WebKit engine tests
now execute in CI, including mobile emulation and CSP denial. Branded current
and previous browser releases, physical mobile devices, and the measured usage
matrix below are still outstanding and cannot be inferred from engine tests.
Current branded Chrome Stable and Edge Stable are now exercised on Linux and
Windows and produce commit/run/OS-bound version receipts. This narrows, but
does not close, the exit gap. The installed Safari on macOS 14 now runs through
SafariDriver and emits the same bound receipt. Previous releases, other macOS
versions, physical Android and iOS, signed evidence, and usage-weighted
accounting remain.
Safari also exposes a measured CSP gap: unlike the other gated browsers, the
macOS 14 product permits Wasm compilation without `'wasm-unsafe-eval'`.
Evidence v2 records that property as false, so Safari compatibility can advance
without incorrectly claiming the CSP defense. Digest and host admission remain
the normative boundary.

- Stabilize `wasm32-browser-kotoba-v1` and its exact import schema.
- Ship an ES module host, Worker host, capability-policy adapter, CSP guidance,
  bounded heap implementation, and deterministic trap mapping.
- Test current and previous Chrome, Edge, Firefox, and Safari releases on
  Windows, macOS, Android, and iOS.
- Add cross-origin isolation and JIT-disabled/fallback tests where applicable.

Exit gate: the browser usage matrix reaches 95%, every browser executes the
same normative vectors, and denied capabilities and forged pair handles trap.

### Phase 2 — Windows native desktop and enterprise

Implementation status: the explicit x86_64 Windows target profile, internal
ABI identity, supervisor identity, CLI route, independent regeneration, and
cross-runner reproducibility gate are implemented. A first Windows supervisor
also executes verified/extracted code under W^X, dynamic-code prohibition, a
one-process Job Object, and a low-integrity restricted token. CI covers calls,
fuel, capability allow/deny, heap accounting, and filesystem/process probes.
The product path now performs reproducible loader construction, OS-specific
source and toolchain-closure measurement, runtime trust, signed `kotoba -M run`,
and receipt verification. Loader mutation and OS-profile substitution fail
closed in Windows CI. Parent/child trap supervision, network denial,
Authenticode signing, packaging, and Arm64 remain required before any
Windows-native coverage is counted.

- Implement x64 and Arm64 Windows KEXE loaders.
- Use W^X allocation, Control Flow Guard-compatible entry points, Job Objects,
  restricted tokens/AppContainer where available, and process mitigation
  policies.
- Bind loader, OS ABI, compiler/toolchain closure, policy, and code identity.
- Add Windows code signing, MSIX packaging, offline verification, audit output,
  SBOM, and provenance.
- Run x64 and physical Arm64 CI. Emulated x64-on-Arm is compatibility evidence,
  not Arm64-native evidence.

Exit gate: Windows x64 and Arm64 pass the complete mutation, sanitizer where
supported, sandbox, signature, receipt, and runtime-identity suites.

### Phase 3 — Mobile native

Implementation status: explicit `aarch64-android-kotoba-v1` and
`aarch64-ios-kotoba-v1` compiler targets now bind Android isolated-host and iOS
static-host identities. Independent regeneration rejects OS/runtime profile
substitution, and equal AArch64 code still produces distinct sealed artifacts.
A pinned NDK now also cross-builds the first Android AArch64 host library twice
byte-identically and checks its hardened ELF and single-function export surface.
This remains build-boundary progress only: no mobile execution or endpoint
coverage is claimed until KEXE admission integration, isolated-process
packaging, lifecycle, and physical-device gates pass.
The iOS path now additionally reverifies KEXE and emits a digest-bound static
AOT manifest plus canonical Mach-O text. Pinned Xcode 16.2 builds the Arm64 text
object and no-JIT host archive twice byte-identically. App integration,
codesigning, store-compatible packaging, trap isolation, and physical iPhone/
iPad execution remain required.

- Implement Android AArch64 as an NDK host library with isolated-process and
  application-sandbox integration.
- Implement iOS/iPadOS AArch64 as signed AOT/static embedding; no runtime JIT or
  writable-executable transition is assumed.
- Define capability bridges for lifecycle, network, storage, sensors, camera,
  and notifications. Every bridge is deny-by-default and independently metered.
- Add deterministic memory-pressure, suspend/resume, background termination,
  and OS-version compatibility tests on physical devices.

Exit gate: signed sample products pass store-compatible packaging checks and
the current/previous major Android and iOS device matrix.

### Phase 4 — Server and portable component runtime

Implementation status: `wasm32-wasi-kotoba-v1` is now an explicit compiler
target with a sealed `kotoba.target` custom section. The dependency-free server
host requires that identity and the bounded Kotoba capability/heap import
schema; it supplies no ambient WASI namespace. CI executes pure and capability
programs and rejects a substituted target plus a forged
`wasi_snapshot_preview1` import. Pinned Wasmtime independently executes pure
results, arguments, and fuel exhaustion on Linux x86-64, Linux Arm64, and
macOS Arm64. The native Linux Arm64 runner also passes the AArch64 W^X loader,
sanitizer corpus, and 20,000-run architecture-bound libFuzzer gate. Kubernetes,
cancellation, observability, and provenance gates remain.

- Stabilize Linux x86-64 and AArch64 distribution and container profiles.
- Add `wasm32-wasi-kotoba-v1` with an exact capability adapter rather than
  granting ambient WASI authority.
- Validate on two independent WASI engines and Kubernetes on both native ISAs.
- Add service limits, cancellation, observability, rollout, and supply-chain
  provenance without expanding guest authority.

Exit gate: native and WASI deployments pass identical semantic, resource,
policy, replay, and failure-injection suites.

### Phase 5 — GPU

- Introduce typed accelerator KIR for pure tensor/buffer kernels. CPU KIR cannot
  manufacture device pointers or driver calls.
- Begin with WebGPU/WGSL for Web and a portable Vulkan/SPIR-V provider; add
  Metal and Direct3D 12 providers where portability layers cannot meet the
  security or performance contract.
- Add an optional CUDA provider for datacenter coverage, behind the same sealed
  provider ABI.
- Verify shapes, buffer bounds, numeric modes, workgroup limits, device-memory
  quotas, timeout/cancellation, and declared read/write regions before dispatch.
- Require a deterministic CPU reference path for correctness testing and safe
  fallback. Accelerator output is never trusted as a verifier decision.

Exit gate: differential tests match the CPU oracle within declared numeric
tolerances; malformed kernels, oversized dispatches, device loss, and driver
failure fail closed across each release-supported provider.

### Phase 6 — NPU

- Define a versioned tensor graph IR with a small admitted operator set and
  static shapes/bounds before adding vendor providers.
- Implement providers in population order: Core ML/ANE, Android NNAPI/vendor
  delegates, Windows DirectML, then datacenter inference providers.
- Seal model digest, operator-set version, quantization, provider identity,
  memory/time budgets, and fallback policy into admission and receipts.
- Treat model parsing and vendor drivers as hostile boundaries isolated from
  the verifier and supervisor.

Exit gate: unsupported operators never silently change semantics; provider and
CPU-reference results pass tolerance vectors, and provider compromise cannot
grant guest filesystem, network, process, or arbitrary host-memory access.

### Phase 7 — IoT and architecture long tail

- Add RISC-V 64 Linux first, then bounded bare-metal RISC-V and Thumb-2 profiles.
- Replace one global resource profile with sealed classes appropriate to MCU,
  edge, and gateway memory budgets.
- Define RTOS/bare-metal capability tables, static linking, interrupt rules,
  watchdog behavior, secure boot binding, and signed update/rollback policy.
- Consider RISC-V 32, Xtensa, and additional ISAs only when measured deployment
  demand justifies a verifier and long-term CI burden.

Exit gate: every claimed board/RTOS combination has hardware conformance,
power-loss tests, reproducible firmware identity, and a maintained security
update path.

## Security invariants

All phases preserve these invariants:

- one deny-by-default frontend and one independently checked KIR contract;
- exact versioned schemas with unknown-field rejection;
- target/OS/ABI/provider identity included in signed artifacts and receipts;
- no ambient filesystem, network, process, sensor, GPU, or NPU authority;
- bounded fuel, stack, heap, device memory, dispatch size, and diagnostics;
- W^X or platform-approved AOT execution; no RWX fallback;
- deterministic CPU oracle for admitted pure behavior;
- mutations of code, policy, target, runtime, provider, or resource limits are
  rejected before execution;
- a platform is removed from the coverage numerator when its required CI or
  security-update path is not healthy.

## Priorities and expected effect

The implementation order is deliberately not “add every ISA.” Browser Wasm
provides the broadest reach with one safe runtime. Windows hosts close the
largest desktop gap. Android and iOS close the mobile-native gap. Existing
x86-64/AArch64 code generators cover most initial native work; new backends are
reserved for RISC-V and microcontroller profiles. GPU/NPU providers increase
workload coverage and performance, but do not increase endpoint coverage and
must not be used to inflate the 95% figure.

## Consequences

- Platform support becomes more expensive because each claimed target requires
  a maintained loader, sandbox, packaging path, hardware CI, and incident path.
- The target matrix grows without duplicating language semantics or weakening
  the verifier.
- Wasm, native CPU, GPU, and NPU artifacts can share policy and receipts while
  retaining distinct execution identities.
- The project can make a reproducible 95% claim after the gates pass, while
  honestly reporting the remaining 5%, unknown observations, IoT coverage, and
  accelerator coverage separately.

## Non-goals

- Claiming current 95% native product coverage.
- Treating ISA emission, emulation, or browser availability as native support.
- Supporting arbitrary GPU shaders, vendor model formats, drivers, or Clojure
  FFI inside the trusted KIR.
- Weakening sandbox or verification requirements to add a platform to the
  numerator.
