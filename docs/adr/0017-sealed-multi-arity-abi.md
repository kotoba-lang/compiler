# ADR 0017: Sealed static multi-arity ABI

Status: implemented and qualified

## Context

Portable Clojure code commonly declares one function with several fixed
arities. Kotoba currently admits only one parameter vector per `defn`. Merely
adding JavaScript argument inspection would make Web semantics differ from
reference, Wasm, and native targets, and it would turn missing or extra
arguments into target-dependent coercions.

## Decision

Kotoba admits bounded, statically selected fixed-arity clauses:

```clojure
(defn offset
  ([x] (offset x 1))
  ([x delta] (+ x delta)))
```

The contract is deliberately closed:

- A declaration has between one and 8 clauses, with total functions still
  bounded by the existing module function limit.
- Each clause has a distinct logical arity. Variadic `&`, optional arguments,
  runtime argument-count dispatch, `apply`, keyword arguments, and default
  JavaScript values remain rejected.
- Every direct call is resolved by `(source-name, arity)` during frontend
  admission. An absent or duplicate clause fails compilation.
- Each clause lowers to an ordinary monomorphic function. Overloaded clauses
  use the canonical internal/export symbol `<name>$arity$<decimal-count>`;
  single-arity functions retain their existing symbol and artifact identity.
- `main` remains exactly one zero-arity clause.
- A namespace export of an overloaded source name expands to all of its
  clauses in ascending arity order. Interface metadata includes both the
  source name and canonical ABI symbol, so consumers never infer the mangling.
- Parameter and result types remain clause-local and must pass the existing
  exact cross-backend value-type checks. No implicit conversion participates
  in overload selection.

This is static overloading, not runtime multimethod dispatch. Reference,
restricted JavaScript, typed Wasm, and native backends therefore consume the
same already-resolved HIR and cannot disagree about a selected clause.

## Safety invariants

Admission rejects duplicate arities before HIR construction, calls with no
matching clause, more than 8 clauses, variadic parameters, malformed clause
bodies, and ambiguous namespace exports. Canonical names are reserved from
source declarations, preventing a user from shadowing a generated ABI symbol.
The existing per-function parameter bound, lowering budget, call-depth/fuel
limits, capability inference, and exact runtime boundary validation apply to
every lowered clause independently.

## Compatibility

Existing single-arity source and artifacts remain byte-stable. Multi-arity
source is intentionally rejected by older compilers. Published interfaces
carry each canonical ABI symbol explicitly; consumers bind those inspected
symbols rather than assuming that an older compiler understands the source
syntax or reconstructing the mangling themselves.

## Qualification gates

Implementation is complete only when positive recursion/cross-clause tests and
negative duplicate/missing/variadic/limit tests pass in the frontend, and the
same public clauses execute with identical results in reference, restricted
JavaScript, typed Wasm, browser-host admission, and JVM-free nbb compilation.

## Evidence

The compiler suite passes 317 tests / 3,981 assertions. The tests execute
cross-clause calls through the normative reference executor, restricted
JavaScript, and an instantiated browser-host typed-Wasm module. The JVM-free
nbb compiler produces a byte-identical 21-case corpus including the
multi-arity fixture, and browser-host admission, identity, execution, and
denial vectors pass. Negative admission covers duplicate and missing arities,
variadic parameters, more than eight clauses, and attempted use of the
reserved `$arity$` ABI marker.
