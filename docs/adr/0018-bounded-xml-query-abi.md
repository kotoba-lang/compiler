# ADR 0018: Bounded XML query ABI

Status: implemented; real URDF consumer qualification pending

Kotoba admits XML as data, never as ambient parser authority. The language
provides only `xml-path-count : string,string -> i64` and
`xml-path-attr : string,string,i64,string -> [:option :string]`. Paths are
exact root-relative element paths. There are no selectors, namespaces,
callbacks, external identifiers, entity expansion, DOM objects, or I/O.

The sealed profile is shared with kotoba-script ADR 0018: at most 65,536 UTF-8
input bytes, 2,048 elements, depth 32, 32 attributes per element, 32 path
segments, and ASCII names of at most 128 characters. DTDs, entities,
processing instructions, CDATA, text or mixed content, duplicate attributes,
mismatched tags, malformed UTF-16, and trailing content trap. Missing elements
or attributes produce typed `none`; malformed input and negative indices trap.

The reference evaluator uses a pure CLJC parser, so JVM XML libraries and their
configuration are not part of language semantics. Kotoba Script uses its own
dependency-free restricted-JavaScript implementation. Typed Wasm ABI v6 adds
two `kotoba:typed` imports only when one of the XML operations is present;
unrelated modules acquire no new import authority. The browser host admits
sealed v5 artifacts for compatibility while generating and reporting v6 for
new artifacts.

Evidence:

- kotoba-script PR #45, merge `ed4b697b4e5aeb4c66dde63dff5a9acdcfe1a90a`:
  39 tests and 133 assertions.
- Compiler reference, restricted JavaScript, and actual typed Wasm runtime
  agree on URDF-shaped count, attribute, and typed-absence vectors.
- Compiler suite: 319 tests and 3,990 assertions.
- JVM-free compiler parity: 22 Wasm golden cases, including `xml-query`.
- Browser host admission, identity, legacy-v5 compatibility, execution, and
  denial vectors pass.

Language-wide qualification still requires comparison against an actual
`kami-articulated` URDF consumer and its existing CLJC oracle.
