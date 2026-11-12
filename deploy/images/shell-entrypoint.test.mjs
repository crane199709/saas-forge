import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { once } from "node:events";
import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

// 显式运行 node --test deploy/images/shell-entrypoint.test.mjs；只创建独立 Nginx 容器，不使用平台数据库。
const docker = (...args) =>
  execFileSync("docker", args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();

test("生产 Nginx 剥离 /api，保留认证 Cookie、静态资源与 SPA 回退", async () => {
  const upstream = createServer((req, res) => {
    res.setHeader("Content-Type", "application/json");
    res.setHeader(
      "Set-Cookie",
      "IAM_SESSION=login; Path=/iam; HttpOnly; SameSite=Lax",
    );
    res.end(
      JSON.stringify({
        path: req.url,
        cookie: req.headers.cookie,
        authorization: req.headers.authorization,
      }),
    );
  }).listen(0, "0.0.0.0");
  await once(upstream, "listening");
  const dir = await mkdtemp(join(tmpdir(), "forge-nginx-"));
  const name = `forge-api-entry-${process.pid}`;
  try {
    await mkdir(join(dir, "assets"));
    await writeFile(join(dir, "index.html"), "<h1>Production shell</h1>");
    await writeFile(join(dir, "assets/app.js"), 'console.log("asset")');
    docker(
      "run",
      "-d",
      "--name",
      name,
      "-p",
      "127.0.0.1::8080",
      "-e",
      `FORGE_GATEWAY_URL=http://host.docker.internal:${upstream.address().port}`,
      "-v",
      `${fileURLToPath(new URL("./shell-entrypoint.sh", import.meta.url))}:/docker-entrypoint.d/40-forge-config.sh:ro`,
      "-v",
      `${dir}:/usr/share/nginx/html`,
      process.env.FORGE_NGINX_TEST_IMAGE ?? "forge/tenant-app:0.1.0",
    );
    const port = docker("port", name, "8080/tcp").split(":").at(-1);
    const origin = `http://127.0.0.1:${port}`;
    let ready = false;
    for (let attempt = 0; attempt < 50; attempt++) {
      try {
        ready = (await fetch(origin)).ok;
      } catch {
        /* Nginx entrypoint still running. */
      }
      if (ready) break;
      if (docker("inspect", "-f", "{{.State.Running}}", name) !== "true") break;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    assert.ok(ready, docker("logs", name));
    const business = await fetch(
      `${origin}/api/new-service/orders?title=hello%20world`,
      { headers: { Authorization: "Bearer tenant-token" } },
    );
    assert.deepEqual(await business.json(), {
      path: "/new-service/orders?title=hello%20world",
      authorization: "Bearer tenant-token",
    });
    assert.equal(
      (await (await fetch(`${origin}/api?probe=1`)).json()).path,
      "/?probe=1",
    );
    const auth = await fetch(`${origin}/iam/auth/refresh`, {
      headers: { Cookie: "IAM_SESSION=login" },
    });
    assert.deepEqual(await auth.json(), {
      path: "/iam/auth/refresh",
      cookie: "IAM_SESSION=login",
    });
    assert.equal(
      auth.headers.get("set-cookie"),
      "IAM_SESSION=login; Path=/iam; HttpOnly; SameSite=Lax",
    );
    for (const path of [
      "/apiXYZ",
      "/iamXYZ",
      "/apps/new-service/home",
      "/unknown-route",
      "/index.html",
    ]) {
      const response = await fetch(origin + path);
      assert.match(await response.text(), /Production shell/);
      assert.equal(response.headers.get("cache-control"), "no-store");
    }
    const asset = await fetch(`${origin}/assets/app.js`);
    assert.match(await asset.text(), /console.log/);
    assert.equal(
      asset.headers.get("cache-control"),
      "public, max-age=31536000, immutable",
    );
    assert.equal((await fetch(`${origin}/assets/missing.js`)).status, 404);
    const runtime = await fetch(`${origin}/runtime-config.json`);
    assert.deepEqual(await runtime.json(), { apiMode: "same-origin" });
    assert.equal(runtime.headers.get("cache-control"), "no-store");
  } finally {
    try {
      docker("rm", "-f", name);
    } finally {
      await new Promise((resolve) => upstream.close(resolve));
      await rm(dir, { recursive: true, force: true });
    }
  }
});
