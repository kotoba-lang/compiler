# ADR 0032: Backend provider qualification gate

Status: accepted; Wasmtime/native/CLJS CI manifest gates implemented

## Decision

Wasmtime, native, and CLJS backend CI must verify the same provider-conformance
manifest before reporting any application-capability qualification state. The
gate binds a canonical manifest digest, capability count, and compiler-local
name/ID registry. Manifest or registry drift fails every backend matrix entry.

Manifest qualification and execution qualification are separate. All three
backends now pass the manifest gate, but their typed provider execution ABI and
request/result host codec remain pending. The machine-readable qualification
resource records those gaps. It is forbidden to mark a backend `qualified`
while gaps remain or without both runtime-boundary and semantic-vector evidence.

This prevents documentation or CI labels from implying that a backend executes
the reference application kits before it actually does. Subsequent work closes
the recorded gaps backend by backend; it does not weaken the common manifest or
invent backend-specific application language semantics.

The `wasmtime` row is engine evidence, not permission to define a Wasmtime-only
ABI. Its typed provider qualification requires a compiler-produced Component
Model artifact and sealed WIT world as specified by ADR 0036. A private Rust
embedding adapter or runner cannot close either provider ABI gap.
