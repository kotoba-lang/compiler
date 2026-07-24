# ADR 0049: Component application-language gap ledger

Status: accepted; closing ledger for the 2026-07-21 implementation session

## Context

ADRs 0037 through 0048 established the Component Model/WASI baseline and
implemented progressively wider Canonical ABI slices. Passing a WIT parser,
linker, or identity round-trip is not sufficient evidence that Kotoba is a
qualified safe application language. This ADR records precisely what remains
so later work does not mistake infrastructure progress for provider-semantic or
runtime qualification.

## Closed in this session series

- deterministic WIT worlds derived from checked KIR;
- standard32 Component binaries for scalar exports, bounded strings, and
  sealed nominal records containing scalar fields;
- record construction, one-field update, and scalar field projection slices;
- direct scalar `typed-cap-call` application imports;
- direct same-type sealed scalar-record request/result capability calls;
- compiler-owned scalar and sealed-record identity provider artifacts;
- exact import/provider multiset checking, pinned `wac plug` composition, and
  post-composition `wasm-tools validate`;
- an implementation-only Wasmtime 42 record round-trip.

## Remaining language and Canonical ABI gaps

1. Nested structured values: strings inside records, nested records, lists,
   tuples/vectors, maps, sets, options, results, and variants need recursive
   flatten/lift/lower/store/load plans plus pre-call and post-return validation.
2. Recursive schema identity: Component v1 still rejects general recursive
   Kotoba schemas. A bounded representation or explicit resource/handle design
   must preserve schema digest identity; assigning a WIT name is insufficient.
3. Capability expressions: computed requests, different request/result record
   identities, multiple calls, multiple exported functions, and calls inside
   admitted control flow do not yet have qualified Component lowering.
4. Canonical resource management: nested allocations, ownership transfer,
   post-return cleanup, overflow checks, aggregate byte/node/item budgets, and
   malformed provider-result rejection need executable adversarial vectors.
5. WASI 0.3 async: `async func`, futures, streams, cancellation, deadlines, and
   item/byte budgets remain specification-only and fail-closed.

## Remaining provider and authority gaps

The current providers are identity wiring fixtures, not implementations of the
nine application capabilities. HTTP, log read/append, clock, state, UI commit
and event delivery, LLM generation, and storage still require production
provider Components with bounded typed request/results and the shared semantic
vectors. Each provider must import only the WASI interfaces declared by its
kit. Storage must not acquire ambient filesystem authority, and LLM must not
acquire ambient sockets.

## Remaining runtime qualification gaps

- Wasmtime: run the full provider-conformance manifest and adversarial
  Canonical ABI vectors on pinned Wasmtime major 43 or newer. The local 42.0.1
  round-trip is not qualifying evidence.
- Native: implement the typed provider syscall ABI and structured
  request/result host codec, then run the same provider manifest and boundary
  vectors. Native must not define different capability semantics.
- Release/CI: provision and attest pinned `wasm-tools`, `wac-cli`, and Wasmtime
  artifacts across supported architectures; composition success alone does not
  establish semantic equivalence.

The CLJS backend remains the only backend with a completed shared provider
manifest gate. Wasmtime and native therefore remain `pending`.

## Next completion order

1. Recursive structured Canonical codecs and validators.
2. Production provider Components for all nine capabilities.
3. Wasmtime 43+ semantic/adversarial qualification.
4. Native syscall/codec implementation and the same qualification corpus.
5. Bounded WASI 0.3 async profile.

## Progress addendum (this session's own ledger is not rewritten in place; see ADR 0064)

Per this repo's own established practice for this file (every ADR from 0050
onward that closed part of what this ledger names has cited this ledger
rather than editing its Decision/gap prose in place — confirmed by this
file's own one-commit history), this addendum only records a pointer, not a
rewrite:

- **LLM (`:llm/generate`) now has a real, live-verified `:clj` production
  provider transport** — `kotoba.compiler.provider.llm-transport` (ADR
  0064) — wired to the repo-wide `murakumo-main` fleet alias, closing one
  entry of item 2's "identity wiring fixtures, not implementations" gap
  for the nine application capabilities named in this ledger's own
  "Remaining provider and authority gaps" section above. The other eight
  capabilities (HTTP, log, clock, state, UI, storage, and the remainder)
  are UNCHANGED by this addendum and remain identity wiring fixtures at
  the `:clj` reference-provider layer discussed in this ledger — ADR
  0064 does not claim otherwise. `:cljs`/nbb transport for LLM itself also
  remains an explicit, documented gap (ADR 0064's own "Remaining gaps").
  This progress is at the JVM/Chicory reference-provider layer this ledger
  itself discusses, not yet the WASM Component Model layer ADR chain
  0037-0063 builds toward — see ADR 0064 for the precise scope.
