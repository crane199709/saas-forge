import { spawnSync } from "node:child_process";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

import { localDevelopmentServices } from "./local-service-replacement.mjs";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

function script(name) {
  return path.join(repositoryRoot, "scripts", name);
}

export function localDevelopmentPlan(arguments_) {
  const [command, service] = arguments_;
  if (command === "setup" && arguments_.length === 1) {
    return [
      { script: "local-https-development.sh", arguments: ["setup"] },
      { script: "local-https-development.sh", arguments: ["hosts"] },
      { script: "local-https-development.sh", arguments: ["trust-ca"] },
    ];
  }
  if (
    (command === "replace" || command === "restore") &&
    arguments_.length === 2 &&
    localDevelopmentServices.includes(service)
  ) {
    return [
      {
        script: "local-service-replacement.sh",
        arguments: [command, service],
      },
    ];
  }
  if (command === "status" && arguments_.length === 1) {
    return [
      {
        script: "local-https-development.sh",
        arguments: ["status", "edge"],
        continueOnFailure: true,
      },
      ...localDevelopmentServices.map((target) => ({
        script: "local-service-replacement.sh",
        arguments: ["status", target],
        continueOnFailure: true,
      })),
    ];
  }
  if (command === "doctor" && arguments_.length === 1) {
    return [
      {
        script: "local-https-development.sh",
        arguments: ["doctor"],
        continueOnFailure: true,
      },
      ...localDevelopmentServices.map((target) => ({
        script: "local-service-replacement.sh",
        arguments: ["doctor", target],
        continueOnFailure: true,
      })),
    ];
  }
  return undefined;
}

function usage() {
  console.error(
    "用法：bash scripts/local-development.sh <setup|doctor|status>\n" +
      "      bash scripts/local-development.sh <replace|restore> <gateway|iam-service|tenant-access-service|entitlement-service|audit-service>",
  );
}

export function executeLocalDevelopmentPlan(plan, run = spawnSync) {
  let failures = 0;
  for (const step of plan) {
    const result = run("bash", [script(step.script), ...step.arguments], {
      cwd: repositoryRoot,
      env: process.env,
      stdio: "inherit",
    });
    if (result.error || result.status !== 0) {
      failures += 1;
      if (!step.continueOnFailure) break;
    }
  }
  return failures === 0 ? 0 : 1;
}

function main(arguments_) {
  const plan = localDevelopmentPlan(arguments_);
  if (plan === undefined) {
    usage();
    process.exitCode = 2;
    return;
  }
  process.exitCode = executeLocalDevelopmentPlan(plan);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
