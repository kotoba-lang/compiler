# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

This project has no formal release process yet: there are no git tags, and
neither `package.json` nor `deps.edn` carries a version field (confirmed
2026-07-18). Entries below are therefore dateless/version-less at the top
(`[Unreleased]`) rather than numbered, since numbering would imply a release
scheme that does not exist.

This file starts now (2026-07-18). The "Unreleased" section below is a
snapshot of accumulated capability, not a diff against a prior tagged
release — there is no prior release to diff against. The dated entries under
"History" summarize real, chronologically-ordered milestones reconstructed
from `git log` (263 commits, 2026-07-11 through 2026-07-18 at the time of
writing) grouped by day; they are an honest summary of that log, not an
entry-by-entry reconstruction of every commit, and no commit before this
file's own addition was written with a changelog in mind.

## [Unreleased]

### Status

The compiler is **experimental alpha, not production-safe**
(`docs/architecture.md:124`). General allocation, tracing GC, and a
production-strength VM sandbox remain absent.

### Current capabilities (state so far)

- **Multi-target ahead-of-time compilation** from a single `.kotoba` source
  pipeline (`source -> inert reader -> typed/effect HIR -> SSA-like KIR ->
  backend`) to `wasm32` / `wasm32-browser` / `wasm32-wasi`, `x86_64` (incl.
  `x86_64-windows`), `aarch64` (incl. `aarch64-android`, `aarch64-ios`,
  bare-metal `aarch64-aiueos-kernel-v1`), and `cljs` (KIR lowered to plain
  ClojureScript source), plus a restricted JavaScript/web target and a typed
  GPU accelerator KIR emitting WGSL, CUDA C, or Metal Shading Language.
- **Three independent safety gates** ahead of every backend: frontend
  admission (`kotoba.compiler.frontend` — subset/reader validation, forbidden
  heads), deny-by-default capability admission (`kotoba.compiler.admission`
  — capability calls fail closed unless explicitly allowed), and a
  structurally independent target verifier that decodes and re-checks every
  emitted instruction before an artifact is admitted, including against
  attacker-resealed KEXE containers.
- **Fuzzing infrastructure**: coverage- and sanitizer-guided (ASan/UBSan)
  fuzzing of the native loader and frontend parser, with corpus
  promotion/review tooling and CI coverage-regression gating
  (`scripts/fuzz-native.cljs`, `scripts/review-fuzz-corpus.cljs`).
- **nbb-native execution path**: `wasm32`/`wasm32-browser`/`wasm32-wasi`
  `compile`/`check` run entirely under `nbb` (ClojureScript on Node) with no
  JVM process spawned, sharing `.cljc` source with the JVM-compat path used
  by every other target and CLI subcommand.
- Signed, receipted execution evidence (Ed25519 artifact admission,
  executor-attested run receipts, reproducible platform coverage snapshots)
  and a supervised W^X loader on native targets (Linux seccomp sandboxing,
  macOS sandboxing, Windows restricted-token supervision).

## History (summarized from `git log`, not entry-by-entry)

### 2026-07-11 — Bootstrap: verified multi-target compiler core

- Multi-target compiler bootstrapped with wasm32, x86-64, and AArch64
  backends, each executing fuel-bounded runtime KIR under a supervised W^X
  loader.
- Deny-by-default capability admission and Ed25519 artifact admission added;
  executor-attested run receipts introduced.
- Linux native execution sandboxed with seccomp; native loader fuzzing
  (coverage + ASan/UBSan) and frontend fuzzing set up.
- Compiler exposed through `kotoba -M`; safety gates verified in CI.

### 2026-07-12 — Windows target, GPU kernels, browser host

- `x86_64-windows-kotoba-v1` sealed target added: verified KEXE execution
  under a Windows W^X supervisor with restricted impersonation tokens and
  owner ACLs on private outputs.
- Typed GPU accelerator KIR added, lowering bounded f32 kernels to WGSL,
  CUDA C, and Metal Shading Language.
- Deny-by-default browser Wasm host added, isolated in a closed worker host;
  browser Wasm matrix testing across three engines, including branded Safari
  conformance attestation.
- Reproducible, signed platform coverage reporting added
  (`bin/kotoba -M coverage`).
- Security gates migrated from babashka to nbb.

### 2026-07-13 — Mobile targets, per-architecture fuzz floors

- Hardened Android AArch64 NDK cross-build host added
  (`aarch64-android`).
- Verified iOS code packaged as a static AOT archive (`aarch64-ios`).
- Native fuzz coverage floors bound per architecture.

### 2026-07-14 — cljs backend, aiueos freestanding targets, language growth

- New `cljs` backend added (ADR-2607151500): KIR lowered to plain
  ClojureScript source text rather than machine code or a Wasm binary,
  including a `cap-call` host-dispatcher mechanism and a loud (not silent)
  arithmetic-overflow guard.
- `aiueos` freestanding kernel target contracts added; freestanding ELF64
  kernels and PE32+ EFI firmware packaging introduced.
- Language surface grew: `and`/`or`/`when`, keyword and map literals,
  `get`/`assoc`, destructuring, vector-as-data, and `loop`/`recur`
  (ADR-2607150000).

### 2026-07-15 — aiueos kernel export series

- A long series of small, individually-reviewed `aiueos` kernel exports
  landed (capability/journal/registry/PCI/syscall/storage planners and
  validators), each behind its own PR.
- A silent-`"nil"`-output bug in `compile --target cljs-kotoba-v1` fixed.

### 2026-07-16 — nbb-native wasm32 path, UEFI loader, iOS Simulator, x86 tail-call safety

- nbb-native `compile`/`check` path for `wasm32`/`wasm32-browser`/
  `wasm32-wasi` targets landed: no JVM process spawned for that path (#35).
- Embedded Kotoba kernel UEFI loader packaged; UEFI memory map wired into
  the kernel; kernel segment admission hardened.
- Real iOS Simulator execution of compiled `.kotoba` code added (no
  hardware/signing required) (#48).
- x86-64 tail recursion made stack-safe; tail jumps restricted to true tail
  positions.
- `aiueos` user-process ELF target, mediated user runtime ABI, and process/
  scheduler planner exports continued landing behind individual PRs.

### 2026-07-17 — Bare-metal AArch64 kernel target, `do` form, Kotoba Script backend

- `aarch64-aiueos-kernel-v1` bare-metal AArch64 kernel target added, with
  bounded `kernel-load-u32`/`kernel-store-u32` MMIO intrinsics.
- `do` sequencing form (ordered side effects, evaluated once) added to the
  language.
- Restricted Kotoba Script backend added, with a Java 17-compatible
  verifier (#49).
- Frontend admission extended: bounded namespace and function docstrings
  (#50, #51), closed top-level data constants (#52), capability-safe module
  exports (#53).

### 2026-07-18 — Web target typed strings, module linking, supply-chain sealing

- Explicit web library modules and bounded typed strings for the Kotoba web
  target added (#54, #55).
- Frontend hardened further: multi-body `when` and a catalog of forbidden
  heads (maturity P0/L2).
- Closed Kotoba module linking and embedded module-graph seals in ESM
  artifacts added (#57, #59).
- Aggregate project syntax and literals bounded (#60); verified
  supply-chain identity sealed (#61) — most recent commit at the time of
  writing.
