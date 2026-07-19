# ADR 0014: Structured f32/f64 values across JavaScript and typed Wasm

Status: accepted, implemented

Safety-qualified f32/f64 values were previously restricted to scalar
parameters and results. That prevented geometry state, transforms, and bounded
simulation records from crossing a Kotoba component boundary even though their
shape was already expressible with fixed heterogeneous vectors, records,
options, results, variants, and typed maps.

Nested f32/f64 fields are now admitted under the existing depth, node, item,
field, and nominal-descriptor limits. Typed Wasm transports those fields with
dedicated `push-f32/f64`, `get-f32/f64`, and `assoc-f32/f64` imports rather
than coercing them through i64 or externref. The browser host validates each
number at construction, projection, update, parameter, and result boundaries;
f32 must already be exactly `Math.fround`-representable, signed zero is
preserved, and NaN is canonicalized as a JavaScript numeric NaN. Kotoba Script
uses the same sealed descriptors and checks.

The executable conformance case covers a mixed f64/f32 fixed vector, a nominal
point record, persistent field updates, NaN, signed zero, and a typed graph map
from keyword to point record across the reference executor, restricted
JavaScript, and typed Wasm.

Direct f32/f64 set items and direct typed-map keys or values remain fail-closed.
Their ordered-collection ABI requires a separately specified total ordering and
additional scalar map/set imports. Records or fixed vectors containing floats
are reference-shaped values and may be map values today. Dynamically sized
homogeneous f64 buffers are also a separate follow-up; this ADR does not claim
that a fixed 32-field/vector profile replaces heightmaps or mesh buffers.
