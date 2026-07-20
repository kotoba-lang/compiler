# ADR 0036: Component Model first WASI capability architecture

Status: accepted; specification adopted, compiler implementation pending

## Context

The compiler already emits Kotoba directly as core WebAssembly. A separate
Rust runner would not compile Kotoba; it would only adapt Kotoba's private
`externref` imports to one embedding API. Making that adapter the architecture
would duplicate the host codec, bind portability claims to Wasmtime, and
postpone a standard component contract.

The existing `wasm32-wasi-kotoba-v1` target is a sealed core-Wasm profile. It
has no `wasi_snapshot_preview1` imports and is not a WebAssembly Component Model
component despite earlier documentation calling it a component profile.

## Decision

Kotoba will add a Component Model target produced by `kotoba-lang/compiler`.
The compiler owns generation of the core module, canonical ABI adapters,
component type information, and final component artifact. A runtime-specific
Rust runner is not part of this compilation architecture.

Each declared Kotoba typed capability becomes a versioned WIT interface. A
compiled application's WIT world imports exactly those interfaces and exports
only its declared entry points. Closed Kotoba schemas map to WIT records,
variants, options, results, lists, and scalars according to the normative
mapping in `application-language.edn`. Recursive schema identity remains
digest-bound inside Kotoba, but Component v1 rejects recursive schemas because
standard WIT value types do not provide the same general recursive ADT model.
Component lowering rejects every type whose bounds or identity would be
weakened.

The platform baseline is WASI 0.3.0. Synchronous v1 functions remain WIT
`func`; native `async func`, `stream<T>`, and `future<T>` require the separately
declared bounded async/effect profile. WASI 0.2.11 remains an explicit legacy
compatibility profile and is never selected implicitly. Standard
WIT lists carry no relied-upon Kotoba bound: collection limits and canonical
set/map ordering are enforced by generated pre/post boundary validators.

WASI authority is confined to provider components. Application components do
not receive filesystem, sockets, clocks, random, environment, or process
interfaces merely by selecting a WASI target. A provider may import a WASI
interface only when its capability kit declares that authority, and the
composed world must expose no undeclared import. Request validation occurs
before provider invocation and result validation after return.

Wasmtime is one conformance engine for generated components, not a language
host contract. Qualification requires the same component and WIT world to run
without Kotoba-specific engine patches or an application-specific runner.

The existing core targets remain supported during migration, but their private
import ABI must not be extended as a substitute for the Component Model target.

## Consequences

Implementation proceeds in this order: freeze WIT mappings and worlds, emit a
valid component artifact, compose bounded provider components, then qualify
engines. Wasmtime typed-provider gaps remain pending until that path exists.
Temporary engine adapters may be non-normative test fixtures, but cannot
establish backend qualification.

The normative version pins, type mappings, capability interfaces, and provider
WASI imports are recorded in `component-model-v1.edn`.
