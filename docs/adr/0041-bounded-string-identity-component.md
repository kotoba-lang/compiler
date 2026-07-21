# ADR 0041: Bounded string identity Component slice

Status: accepted; executable string identity lowering implemented

## Decision

The first structured Component Model artifact admits exactly one exported
function whose checked signature is `string -> string` and whose KIR body
returns its parameter unchanged. All other string expressions and mixed
structured exports remain rejected.

The dedicated standard32 core uses three fixed 64-KiB memory pages and a
checked bump allocator. `cm32p2_realloc` validates alignment, unsigned pointer
overflow, and the fixed memory ceiling, and preserves the bounded prefix when
given an old allocation. The exported function validates the 65,536-byte
language bound and pointer range, copies the UTF-8 bytes, writes the canonical
pointer/length result area, and returns its address. Post-return resets the
arena for the next synchronous call.

Canonical lowering supplies valid UTF-8 for WIT string arguments. Because this
slice only copies those bytes, it preserves validity without decoding through
the legacy `externref` runtime. Extending the body language requires explicit
linear-memory UTF-8 operations and their own validation evidence.

Core WAT is encoded by pinned official `wasm-tools 1.243.0`; final packaging
still requires standard32 names with `--reject-legacy-names`.
