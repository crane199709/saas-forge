import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repository = fileURLToPath(new URL("../../", import.meta.url));

test("default OpenAPI verification works without a Node or frontend runtime", async (t) => {
  const bin = await mkdtemp(path.join(os.tmpdir(), "sf-backend-verification-"));
  t.after(() => rm(bin, { recursive: true, force: true }));
  await writeFile(path.join(bin, "node"), "#!/bin/sh\nexit 99\n", {
    mode: 0o700,
  });
  const result = spawnSync(
    path.join(repository, "mvnw"),
    [
      "--batch-mode",
      "--no-transfer-progress",
      "-pl",
      "saas-forge-contracts/saas-forge-openapi-contracts",
      "-am",
      "verify",
    ],
    {
      cwd: repository,
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}` },
      encoding: "utf8",
      timeout: 120_000,
    },
  );
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.doesNotMatch(result.stdout, /verify-consoles/);
});
