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
syscalls trap as `SIGSYS`. CI deliberately attempts to open `/etc/passwd` after
filter installation and requires a structured `KEXE_TRAP`.
