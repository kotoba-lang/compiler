# ADR 0003: aiueos freestanding target contracts

- Status: Accepted, backend packaging incomplete
- Date: 2026-07-14

## Decision

The compiler reserves two fail-closed x86_64 target identities:

- `x86_64-aiueos-uefi-v1`: Microsoft x64 ABI, PE32+ EFI application,
  `efi_main` entry point;
- `x86_64-aiueos-kernel-v1`: aiueos kernel ABI v1, ELF64 image,
  `aiueos_kernel_entry` entry point.

Both profiles declare `runtime :none` and `ambient-syscalls false`. Their
identity is included in the sealed KEXE artifact, so a hosted artifact cannot
be relabelled as firmware or kernel code without failing verification.

## Current boundary

The existing x86_64 backend emits the verified Kotoba instruction body and
KEXE envelope. It does not yet emit PE/COFF or ELF sections, relocations, an
entry shim, or a final firmware/kernel file. Therefore these profiles are
compiler contracts and admitted-code identities, not a claim that KEXE itself
is directly firmware-loadable.

The Kotoba integration repository supplies the small audited UEFI bootstrap
for the first Phase 1 boot evidence. A subsequent backend change must package
the emitted body according to these profiles and replace that bootstrap where
possible.
