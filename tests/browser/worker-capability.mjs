import { installKotobaWorker } from "/runtime/worker-host.mjs";

installKotobaWorker(globalThis, { capabilities: new Map([[7, value => value + 1n]]) });
