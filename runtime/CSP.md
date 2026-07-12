# Browser deployment profile

Serve the page, module worker, runtime modules, and reviewed Wasm artifact from
one authenticated origin. Apply this policy as an HTTP response header to the
document and worker script; a `<meta>` policy is not an equivalent worker gate.

```http
Content-Security-Policy: default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'
Cross-Origin-Resource-Policy: same-origin
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
```

`'wasm-unsafe-eval'` is the narrow CSP Level 3 permission for WebAssembly byte
compilation. Do not replace it with the broader `'unsafe-eval'`.
`worker-src 'self'` excludes blob, data, and attacker-selected worker URLs.
`connect-src 'self'` is needed only when the embedding application fetches the reviewed
Wasm bytes; use `'none'` when the artifact is delivered without `fetch`.

Create a module worker from a static URL:

```js
const worker = new Worker(
  new URL("./node_modules/@kotoba-lang/browser-runtime/worker-entry.mjs", import.meta.url),
  { type: "module", name: "kotoba-runtime" }
);
```

The stock entry has no capability handlers. Applications that need a reviewed
capability must publish a static worker entry which imports `installKotobaWorker`
and passes an install-time `Map`; handlers are never accepted over `postMessage`.
Do not construct the worker URL or capability map from guest-controlled input.

This v1 host does not use `SharedArrayBuffer`, shared memory, or threads, so
COOP/COEP cross-origin isolation is not required for correctness. A future
shared-memory profile must use a new runtime identity and separate evidence.

Normative CSP behavior is defined by [Content Security Policy Level 3](https://www.w3.org/TR/CSP3/),
including the distinct WebAssembly compilation permission and `worker-src`
fetch directive.
