import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import test from "node:test";

import {
  executeLocalDevelopmentPlan,
  localDevelopmentPlan,
} from "../local-development.mjs";

test("provides one daily interface for setup, replacement, and restore", () => {
  assert.deepEqual(
    localDevelopmentPlan(["setup"]).map(({ script, arguments: arguments_ }) => [
      script,
      ...arguments_,
    ]),
    [
      ["local-https-development.sh", "setup"],
      ["local-https-development.sh", "hosts"],
      ["local-https-development.sh", "trust-ca"],
    ],
  );
  for (const target of ["platform", "tenant", "all"]) {
    assert.equal(
      localDevelopmentPlan(["frontend", "start", target]),
      undefined,
    );
  }
  assert.equal(localDevelopmentPlan(["frontend"]), undefined);
  assert.equal(localDevelopmentPlan(["frontend", "start"]), undefined);
  assert.equal(
    localDevelopmentPlan(["frontend", "start", "platform", "extra"]),
    undefined,
  );
  assert.deepEqual(
    localDevelopmentPlan(["replace", "audit-service"])[0].arguments,
    ["replace", "audit-service"],
  );
  assert.deepEqual(localDevelopmentPlan(["restore", "gateway"])[0].arguments, [
    "restore",
    "gateway",
  ]);
  assert.equal(localDevelopmentPlan(["replace", "unknown-service"]), undefined);
});

test("status and doctor cover all five targets even when one check fails", () => {
  const status = localDevelopmentPlan(["status"]);
  const doctor = localDevelopmentPlan(["doctor"]);
  assert.deepEqual(
    status.map((step) => step.arguments.at(-1)),
    [
      "edge",
      "gateway",
      "iam-service",
      "tenant-access-service",
      "entitlement-service",
      "audit-service",
    ],
  );
  assert.equal(doctor.length, 6);
  assert.ok([...status, ...doctor].every((step) => step.continueOnFailure));

  let calls = 0;
  const exitCode = executeLocalDevelopmentPlan(status, () => ({
    status: calls++ === 0 ? 1 : 0,
  }));
  assert.equal(calls, 6);
  assert.equal(exitCode, 1);
});

test("rejects incomplete and obsolete frontend CLI invocations with safe usage", () => {
  const script = new URL("../local-development.sh", import.meta.url).pathname;
  for (const arguments_ of [
    ["frontend"],
    ["frontend", "start"],
    ["frontend", "start", "platform", "extra"],
    ["frontend", "start", "unknown"],
  ]) {
    const result = spawnSync("bash", [script, ...arguments_], {
      encoding: "utf8",
    });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /<setup\|doctor\|status>/u);
  }
});
