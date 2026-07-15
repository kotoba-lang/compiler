# ADR 0003: aiueos freestanding target contracts

- Status: Accepted, kernel/process ELF64 and UEFI PE32+ packaging implemented
- Date: 2026-07-14

## Decision

The compiler reserves two fail-closed x86_64 target identities:

- `x86_64-aiueos-uefi-v1`: Microsoft x64 ABI, PE32+ EFI application,
  `efi_main` entry point;
- `x86_64-aiueos-kernel-v1`: aiueos kernel ABI v1, ELF64 image,
  `aiueos_kernel_entry` entry point;
- `x86_64-aiueos-user-v1`: aiueos ring-3 ABI v1, ELF64 image,
  `aiueos_process_entry` entry point and loader-readable result context.

Both profiles declare `runtime :none` and `ambient-syscalls false`. Their
identity is included in the sealed KEXE artifact, so a hosted artifact cannot
be relabelled as firmware or kernel code without failing verification.

## Implemented kernel boundary

For `x86_64-aiueos-kernel-v1`, compilation also emits a loadable ELF64
`ET_EXEC` image with separate RX text and RW context segments. Its entry shim
initializes the hidden freestanding context, calls the sealed Kotoba program
entry, then halts. The image contains no `PT_INTERP`, dynamic section, imports,
host runtime, or ambient syscall dependency.

The user profile emits an import-free `ET_EXEC` with a separate RX text page
and RW context page in aiueos's process image window. Its shim initializes the
hidden `r9` context, invokes a zero-arity Kotoba entry, stores the result in
the context page, and remains schedulable until kernel teardown. aiueos must
validate the ELF and map both segments W^X before entering CPL3.

The compiler also directly emits an ELF64 `ET_REL` link artifact. It exports
`kotoba_aiueos_probe` with the SysV `uint64_t(void)` boundary, initializes the
same private context, and calls the compiler-generated Kotoba entry. The
object has `.text`, `.data`, `.rela.text`, `.symtab`, `.strtab`, and
`.shstrtab`; its sole `R_X86_64_PC32` relocation binds the context without an
unresolved host symbol. `kotoba-compiler compile ... --target
x86_64-aiueos-kernel-v1` writes this object directly, without generating C or
invoking a C compiler.

The static context currently admits pure computation only: its capability
bitmap is empty and its capability function pointers are null. Hardware and
kernel services must be introduced through a versioned aiueos context ABI,
not accidental host linkage.

## Remaining boundary

The linkable probe is deliberately a narrow vertical slice. It supports a
zero-argument Kotoba entry and pure integer computation; it is not yet a
general freestanding standard library, driver ABI, linker, or replacement for
the architecture interrupt/context-switch assembly. aiueos owns the final
kernel link and boot evidence.
