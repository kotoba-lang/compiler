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
