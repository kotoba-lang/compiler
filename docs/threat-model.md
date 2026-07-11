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
