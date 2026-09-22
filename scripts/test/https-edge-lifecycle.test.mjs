import assert from "node:assert/strict";
import test from "node:test";
import * as httpsDevelopment from "../local-https-development.mjs";

const repositoryRoot = "/workspace/saas-forge";
test("HTTPS Edge exposes standalone commands without selecting a Console", () => {
  for (const command of ["start", "status", "stop"]) {
    assert.deepEqual(
      httpsDevelopment.localHttpsDevelopmentCommand([command, "edge"]),
      { command, target: "edge" },
    );
  }
});

test("HTTPS infrastructure refuses retired application lifecycle commands", () => {
  for (const target of ["platform", "tenant", "all"]) {
    for (const command of ["start", "status", "stop"]) {
      assert.equal(
        httpsDevelopment.localHttpsDevelopmentCommand([command, target]),
        undefined,
      );
    }
  }
});

function fixture({
  exists = true,
  running = true,
  compatible = true,
  owned = true,
  listening = running,
  health = "healthy",
  failStart = false,
} = {}) {
  const calls = [];
  const mutations = [];
  const runner = (command, args) => {
    calls.push([command, ...args]);
    if (args.includes("ps"))
      return { status: 0, stdout: exists ? "edge-1" : "" };
    if (args.includes("inspect"))
      return {
        status: 0,
        stdout: JSON.stringify({
          id: "edge-1",
          running,
          health,
          ports: { "8443/tcp": [{ HostIp: "127.0.0.1", HostPort: "443" }] },
          projectDirectory: owned
            ? `${repositoryRoot}/deploy/compose`
            : "/another/project",
          service: "local-https-edge",
          hash: compatible ? "expected" : "obsolete",
        }),
      };
    if (args.includes("--hash"))
      return { status: 0, stdout: "local-https-edge expected" };
    if (args.includes("start") || args.includes("up")) {
      mutations.push("start");
      exists = true;
      running = true;
      listening = true;
      if (failStart)
        throw new Error("injected Docker failure after creating Edge");
      return { status: 0, stdout: "" };
    }
    if (args.includes("stop")) {
      mutations.push("stop");
      running = false;
      listening = false;
      return { status: 0, stdout: "" };
    }
    throw new Error("Unexpected command");
  };
  return {
    lifecycle: () =>
      httpsDevelopment.createHttpsEdgeLifecycle({
        repositoryRoot,
        paths: httpsDevelopment.developmentHttpsPaths(repositoryRoot),
        runCommand: runner,
        listener: () =>
          listening ? { address: "127.0.0.1", port: 443 } : undefined,
      }),
    calls,
    mutations,
  };
}

test("Edge status reports stopped, ready, invalid and unknown states using only non-sensitive Docker fields", async () => {
  for (const [options, state] of [
    [{ exists: false, running: false }, "STOPPED"],
    [{ running: false }, "STOPPED"],
    [{}, "RUNNING"],
    [{ listening: false }, "UNREADY"],
    [{ health: "unhealthy" }, "UNREADY"],
    [{ compatible: false }, "INVALID"],
    [{ owned: false }, "UNMANAGED"],
    [{ exists: false, running: false, listening: true }, "UNMANAGED"],
  ]) {
    const environment = fixture(options);
    const result = await environment.lifecycle().status();
    assert.equal(result.state, state);
    assert.equal(
      result.exitCode,
      ["RUNNING", "STOPPED"].includes(state) ? 0 : 1,
    );
    assert.deepEqual(environment.mutations, []);
    for (const args of environment.calls.filter((args) =>
      args.includes("inspect"),
    )) {
      assert.ok(args.includes("--format"));
      assert.doesNotMatch(args.join(" "), /\.Env|\.Config\}\}|\.Mounts/u);
    }
  }
});

test("Edge startup records newly acquired identity even when Docker fails after creation and stops only that identity", async () => {
  for (const exists of [false, true]) {
    for (const failStart of [false, true]) {
      const environment = fixture({ exists, running: false, failStart });
      const lifecycle = environment.lifecycle();
      let identity;
      const started = lifecycle.ensure((value) => {
        identity = value;
      });
      if (failStart) await assert.rejects(started);
      else await started;
      assert.equal(identity, "edge-1");
      await assert.rejects(lifecycle.stop("another-container"));
      assert.equal((await lifecycle.status()).state, "RUNNING");
      await lifecycle.stop(identity);
      assert.equal((await lifecycle.status()).state, "STOPPED");
      assert.deepEqual(environment.mutations, ["start", "stop"]);
      assert.ok(
        environment.calls.some(
          (args) => args[1] === "stop" && args[2] === "edge-1",
        ),
      );
    }
  }
  const healthy = fixture();
  await healthy
    .lifecycle()
    .ensure(() => assert.fail("must reuse healthy Edge"));
  assert.deepEqual(healthy.mutations, []);
});
