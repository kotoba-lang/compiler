# ADR-0002 — Typed GPU accelerator KIR and verified kernel artifacts

- Status: accepted; v1 implemented for f32 elementwise and reduction kernels
- Date: 2026-07-12

## Decision

GPU kernels use `:kotoba.accelerator-kir/v1`, separate from scalar CPU KIR.
The v1 schema admits a deliberately small pure operator set:

- f32 elementwise add/subtract/multiply/divide;
- f32 workgroup reductions sum/max/min;
- u32 bounds and power-of-two workgroups up to 1024 threads.

Kernels declare bounded element counts and exactly storage-read/storage-write
effects. Invalid names, types, effects, operators, workgroups or bounds are
rejected before code generation. Arbitrary shader text and ambient GPU access
are not admitted.

The compiler deterministically lowers one KIR to WGSL, CUDA C or Metal Shading
Language. A sealed
`:kotoba.gpu-artifact/v1` binds target, complete KIR, KIR SHA-256, emitted code,
code SHA-256 and resource limits. Verification treats the artifact as hostile:
it validates KIR, re-lowers it and requires exact code/hash equality even when
an attacker has recomputed the outer seal.

`kotoba-lang/num` owns numerical operator selection and runtime buffers;
`kotoba-lang/compiler` owns typed kernel semantics and code generation. The
compiler never depends on `num`, avoiding a dependency cycle. `num` pins a
compiler revision and consumes verified artifacts.

## Consequences

- WGSL, CUDA and MSL kernels share one admitted meaning and identity.
- Compiler receipts can later bind GPU driver/NVRTC pipeline evidence without
  weakening existing CPU/native artifact verification.
- cuBLAS/cuSPARSE remain reviewed external calls; generated kernels initially
  replace handwritten elementwise/reduction code.
- More dtypes, tensor indexing, fusion and SPIR-V/PTX are future versioned KIR
  extensions, not silently accepted v1 syntax.
