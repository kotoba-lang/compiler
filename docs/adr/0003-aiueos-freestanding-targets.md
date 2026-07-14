# ADR 0003: aiueos freestanding target contracts

- Status: Accepted, kernel ELF64 packaging implemented; UEFI packaging incomplete
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

## Implemented kernel boundary

For `x86_64-aiueos-kernel-v1`, compilation also emits a loadable ELF64
`ET_EXEC` image with separate RX text and RW context segments. Its entry shim
initializes the hidden freestanding context, calls the sealed Kotoba program
entry, then halts. The image contains no `PT_INTERP`, dynamic section, imports,
host runtime, or ambient syscall dependency.

The static context currently admits pure computation only: its capability
bitmap is empty and its capability function pointers are null. Hardware and
kernel services must be introduced through a versioned aiueos context ABI,
not accidental host linkage.

## Remaining boundary

`x86_64-aiueos-uefi-v1` still emits the verified Kotoba instruction body and
KEXE envelope but not a PE/COFF application, relocations, or Microsoft-x64
entry shim. Therefore the UEFI profile remains a compiler contract rather
than a directly firmware-loadable compiler output.

The Kotoba integration repository supplies the small audited UEFI bootstrap
for the first Phase 1 boot evidence. A subsequent backend change must replace
that bootstrap with PE32+ output where possible.
