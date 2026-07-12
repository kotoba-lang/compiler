# Kotoba browser runtime

Experimental deny-by-default host for `wasm32-browser-kotoba-v1` modules.

```js
import { instantiateKotoba, normalizeKotobaTrap } from "@kotoba-lang/browser-runtime";

const hosted = await instantiateKotoba(wasmBytes, {
  expectedSha256: reviewedDigest,
  allowCapabilities: [7],
  capCall(id, value) {
    if (id === 7) return value + 1n;
    throw new Error("unimplemented capability");
  }
});

try {
  hosted.instance.exports.main();
} catch (error) {
  console.error(normalizeKotobaTrap(error));
}
```

The host copies and caps module bytes, verifies an optional SHA-256 identity,
allows only the versioned Kotoba function imports, denies capabilities unless
their numeric IDs are explicitly admitted, and owns a fixed 4,096-cell
immutable pair arena. It exports no pair storage or host pointer. This module
does not grant DOM, network, storage, worker, clock, randomness, or other
ambient browser authority.

For execution outside the document realm, use the static pure-worker entry:

```js
const worker = new Worker(workerEntryUrl, { type: "module" });
worker.postMessage({
  format: "kotoba.worker-request/v1",
  id: "run-1",
  op: "run",
  wasm: wasmBytes,
  expectedSha256: reviewedDigest,
  allowCapabilities: [],
  args: []
});
```

The request is a closed one-shot protocol. IDs and argument counts are bounded,
arguments/results are i64 `bigint` values, overlapping requests fail as busy,
and responses expose stable error classes rather than exception text. Trusted
capability adapters can only be installed in the worker entry at construction;
they cannot arrive in a guest message. See [CSP.md](CSP.md) for the restrictive
HTTP deployment profile.
