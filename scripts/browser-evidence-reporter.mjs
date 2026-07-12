import fs from "node:fs";
import path from "node:path";

export default class KotobaBrowserEvidenceReporter {
  constructor(options = {}) {
    this.outputFile = options.outputFile ?? "test-results/browser-evidence.json";
    this.identities = new Map();
  }

  onTestEnd(test, result) {
    if (result.status !== "passed") return;
    for (const annotation of test.annotations) {
      if (annotation.type !== "kotoba-browser-identity") continue;
      const identity = JSON.parse(annotation.description);
      this.identities.set(identity.project, identity);
    }
  }

  async onEnd(result) {
    const evidence = {
      format: "kotoba.browser-engine-evidence/v1",
      status: result.status,
      commit: process.env.GITHUB_SHA ?? "local",
      ciRunId: process.env.GITHUB_RUN_ID ?? "local",
      projects: Array.from(this.identities.values()).sort((a, b) => a.project.localeCompare(b.project))
    };
    fs.mkdirSync(path.dirname(this.outputFile), { recursive: true });
    fs.writeFileSync(this.outputFile, `${JSON.stringify(evidence, null, 2)}\n`, { flag: "w", mode: 0o600 });
  }
}
