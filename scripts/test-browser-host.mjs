import assert from "node:assert/strict";
import {
  KotobaHostError,
  browserProfile,
  instantiateKotoba,
  normalizeKotobaTrap
} from "../runtime/browser-host.mjs";
import { withCompatibility } from "./compatibility-fixture.mjs";

const rawMain42 = Uint8Array.from([
  0,97,115,109,1,0,0,0,
  1,5,1,96,0,1,126,
  3,2,1,0,
  7,8,1,4,109,97,105,110,0,0,
  10,6,1,4,0,66,42,11
]);
const main42 = withCompatibility(rawMain42);

const typedMain42 = withCompatibility(Uint8Array.from([
  0,97,115,109,1,0,0,0,
  0,18,12,107,111,116,111,98,97,46,116,121,112,101,100,3,1,4,0,0,
  1,5,1,96,0,1,126,
  3,2,1,0,
  7,8,1,4,109,97,105,110,0,0,
  10,6,1,4,0,66,42,11
]), { kir: "kotoba.kir/v4", valueAbi: "kotoba.typed/externref-v1" });

const hosted = await instantiateKotoba(main42);
assert.equal(hosted.instance.exports.main(), 42n);
assert.match(hosted.sha256, /^[0-9a-f]{64}$/);
assert.deepEqual(hosted.report(), { heap: { capacity: 4096, used: 0 } });
await instantiateKotoba(main42, { expectedSha256: hosted.sha256 });
const typedHosted = await instantiateKotoba(typedMain42);
assert.deepEqual(typedHosted.typedAbi, {
  version: 3,
  descriptors: [["option", "i64"]],
  literals: []
});
assert.ok(Object.isFrozen(typedHosted.typedAbi));

await assert.rejects(
  instantiateKotoba(rawMain42),
  error => error instanceof KotobaHostError && error.code === "missing-compatibility"
);

const unsupportedTypedMain42 = typedMain42.slice();
unsupportedTypedMain42[23] = 1;
await assert.rejects(
  instantiateKotoba(unsupportedTypedMain42),
  error => error instanceof KotobaHostError && error.code === "unsupported-typed-abi"
);

await assert.rejects(
  instantiateKotoba(main42, { expectedSha256: "0".repeat(64) }),
  error => error instanceof KotobaHostError && error.code === "digest-mismatch"
);
await assert.rejects(
  instantiateKotoba(main42, { ambientNetwork: true }),
  error => error.code === "invalid-options"
);
await assert.rejects(
  instantiateKotoba(new Uint8Array(1024 * 1024 + 1)),
  error => error.code === "invalid-module"
);

const forbiddenImport = Uint8Array.from([
  0,97,115,109,1,0,0,0,
  1,4,1,96,0,0,
  2,13,1,4,101,118,105,108,4,99,97,108,108,0,0
]);
await assert.rejects(
  instantiateKotoba(forbiddenImport),
  error => normalizeKotobaTrap(error).code === "forbidden-import"
);

assert.equal(browserProfile.format, "kotoba.browser-host/v1");
assert.equal(browserProfile.maxModuleBytes, 1048576);
assert.equal(browserProfile.pairCapacity, 4096);
assert.equal(browserProfile.typedAbiVersion, 3);
assert.ok(Object.isFrozen(browserProfile));
console.log("browser-host: admission, identity, execution, and denial vectors passed");
