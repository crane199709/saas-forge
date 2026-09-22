import assert from "node:assert/strict";
import { createServer, globalAgent } from "node:http";
import { createConnection } from "node:net";
import { request } from "node:https";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { createEdgeServer } from "../../deploy/compose/local-https-development/edge.mjs";
import {
  developmentHttpsPaths,
  ensureCertificateMaterial,
} from "../local-https-development.mjs";

test("Tenant Password Setup document reaches the real Tenant Console route", async (t) => {
  const fixture = await edgeFixture(t);
  const response = await fixture.request("/password-setup");
  assert.equal(response.status, 200);
  assert.equal(response.body, "Tenant SPA");
});

test("Tenant Password Setup assets retain Gateway content types and cache semantics", async (t) => {
  const fixture = await edgeFixture(t);
  for (const [requestPath, type, body] of [
    ["/password-setup/app.js", "text/javascript", "Gateway script"],
    ["/password-setup/styles.css", "text/css", "Gateway stylesheet"],
  ]) {
    const response = await fixture.request(requestPath);
    assert.equal(response.status, 200);
    assert.equal(response.body, body);
    assert.equal(response.headers["content-type"], type);
    assert.equal(response.headers["cache-control"], "no-store");
  }
});

test("matches exact Password Setup pathnames with queries while keeping other Tenant paths on Vite", async (t) => {
  const fixture = await edgeFixture(t);
  for (const requestPath of [
    "/password-setup/app.js?v=1",
    "/password-setup/styles.css?v=1",
  ]) {
    const response = await fixture.request(requestPath);
    assert.match(response.body, /^Gateway /u);
  }
  assert.equal(
    (
      await fixture.request("/api/v1/auth/password-setups?fixture=1", {
        method: "POST",
      })
    ).status,
    409,
  );
  for (const requestPath of [
    "/",
    "/login",
    "/password-setup",
    "/password-setup?lang=zh",
    "/src/main.ts",
    "/@vite/client",
    "/password-setup/",
    "/password-setup-extra",
    "/password-setup/app.js.map",
    "/api/v1/auth/password-setups/extra",
    "/api/v1/auth/password-setups-extra",
  ]) {
    assert.equal(
      (await fixture.request(requestPath)).body,
      "Tenant SPA",
      requestPath,
    );
  }
});

test("Password Setup submission preserves browser request and upstream error semantics", async (t) => {
  const fixture = await edgeFixture(t);
  const headers = {
    origin: "https://console.saas.forge.test",
    cookie: "edge-fixture=synthetic",
    "sec-fetch-site": "same-origin",
    "sec-fetch-mode": "cors",
    "sec-fetch-dest": "empty",
    "content-type": "application/json",
    "x-sf-csrf": "1",
    "idempotency-key": "0199241c-7c00-7000-8000-000000000001",
  };
  const body = JSON.stringify({ fixture: "submission" });
  const response = await fixture.request("/api/v1/auth/password-setups", {
    method: "POST",
    headers,
    body,
  });
  assert.equal(response.status, 409);
  assert.equal(response.headers["content-type"], "application/problem+json");
  assert.equal(response.headers["cache-control"], "no-store");
  assert.equal(response.headers["retry-after"], "7");
  assert.equal(response.body, '{"code":"FIXTURE_REJECTED"}');
  assert.equal(fixture.observed.length, 1);
  const [received] = fixture.observed;
  assert.equal(received.method, "POST");
  assert.equal(received.path, "/api/v1/auth/password-setups");
  assert.equal(received.body, body);
  for (const [name, value] of Object.entries({
    host: "console.saas.forge.test",
    ...headers,
  })) {
    assert.equal(received.headers[name], value, name);
  }
});

test("every formal Tenant path and API Host follows the active Gateway file without restarting Edge", async (t) => {
  const fixture = await edgeFixture(t, { dynamic: true });
  for (const hostname of ["gateway", "host.docker.internal", "gateway"]) {
    await writeFile(
      fixture.targetFile,
      JSON.stringify({ hostname, port: 8080 }),
    );
    for (const requestPath of [
      "/password-setup/app.js",
      "/password-setup/styles.css",
      "/api/v1/auth/password-setups",
    ]) {
      const response = await fixture.request(requestPath, {
        method: requestPath.startsWith("/api/") ? "POST" : "GET",
      });
      assert.equal(
        response.headers["x-fixture-upstream"],
        hostname,
        requestPath,
      );
    }
    assert.equal(
      (
        await fixture.request("/api/v1/auth/password-setups", {
          host: "api.saas.forge.test",
          method: "POST",
        })
      ).headers["x-fixture-upstream"],
      hostname,
    );
    assert.equal((await fixture.request("/")).body, "Tenant SPA");
  }
});

test("a missing active Gateway file fails closed for all formal Tenant paths and the API Host", async (t) => {
  const fixture = await edgeFixture(t, { dynamic: true });
  await rm(fixture.targetFile);
  for (const [host, requestPath] of [
    ["console.saas.forge.test", "/password-setup/app.js"],
    ["console.saas.forge.test", "/password-setup/styles.css"],
    ["console.saas.forge.test", "/api/v1/auth/password-setups"],
    ["api.saas.forge.test", "/api/v1/auth/password-setups"],
  ]) {
    const response = await fixture.request(requestPath, {
      host,
      method: requestPath.startsWith("/api/") ? "POST" : "GET",
    });
    assert.equal(response.status, 502, requestPath);
    assert.equal(response.headers["cache-control"], "no-store");
    assert.equal(response.headers["content-type"], "application/problem+json");
    assert.deepEqual(JSON.parse(response.body), {
      status: 502,
      code: "UPSTREAM_UNAVAILABLE",
    });
    assert.equal(response.headers["x-fixture-upstream"], undefined);
  }
  assert.equal((await fixture.request("/login")).body, "Tenant SPA");
  assert.equal((await fixture.request("/password-setup")).body, "Tenant SPA");
});

test("rejects unapproved Hosts before resolving or forwarding any Password Setup request", async (t) => {
  const fixture = await edgeFixture(t, { dynamic: true });
  for (const host of [
    "unknown.saas.forge.test",
    "console.saas.forge.test:443",
    "constructor",
    "__proto__",
  ]) {
    assert.equal(
      (await fixture.request("/password-setup", { host })).status,
      421,
      host,
    );
  }
});

test("Password Setup upgrades follow Gateway and fail closed while Tenant HMR keeps working", async (t) => {
  const fixture = await edgeFixture(t, { dynamic: true });
  for (const hostname of ["gateway", "host.docker.internal", "gateway"]) {
    await writeFile(
      fixture.targetFile,
      JSON.stringify({ hostname, port: 8080 }),
    );
    for (const requestPath of [
      "/password-setup/app.js",
      "/password-setup/styles.css",
      "/api/v1/auth/password-setups",
    ]) {
      const response = await fixture.upgrade(requestPath);
      assert.equal(response.status, 426);
      assert.equal(response.headers["x-fixture-upstream"], hostname);
    }
    assert.equal((await fixture.upgrade("/")).status, 101);
  }
  for (const value of [undefined, "{"]) {
    if (value === undefined) await rm(fixture.targetFile);
    else await writeFile(fixture.targetFile, value);
    assert.equal((await fixture.upgrade("/password-setup/app.js")).status, 502);
    assert.equal((await fixture.upgrade("/")).status, 101);
    assert.equal(
      (await fixture.upgrade("/", "unknown.saas.forge.test")).status,
      421,
    );
  }
});

test("invalid active targets fail closed and recover after a valid target is restored", async (t) => {
  const fixture = await edgeFixture(t, { dynamic: true });
  for (const value of [
    "{",
    "null",
    "{}",
    '{"hostname":"untrusted.example","port":8080}',
    '{"hostname":"gateway","port":8081}',
    '{"hostname":"host.docker.internal","port":"8080"}',
  ]) {
    await writeFile(fixture.targetFile, value);
    for (const requestPath of [
      "/password-setup/app.js",
      "/password-setup/styles.css",
      "/api/v1/auth/password-setups",
    ]) {
      const response = await fixture.request(requestPath, {
        method: requestPath.startsWith("/api/") ? "POST" : "GET",
      });
      assert.equal(response.status, 502, requestPath);
      assert.equal(response.headers["x-fixture-upstream"], undefined);
      assert.deepEqual(JSON.parse(response.body), {
        status: 502,
        code: "UPSTREAM_UNAVAILABLE",
      });
    }
    assert.equal(
      (
        await fixture.request("/api/v1/auth/password-setups", {
          host: "api.saas.forge.test",
          method: "POST",
        })
      ).status,
      502,
    );
    assert.equal((await fixture.request("/")).body, "Tenant SPA");
    assert.equal(
      (
        await fixture.request("/password-setup", {
          host: "unknown.saas.forge.test",
        })
      ).status,
      421,
    );
  }
  await writeFile(fixture.targetFile, '{"hostname":"gateway","port":8080}');
  assert.equal(
    (await fixture.request("/password-setup/app.js")).headers[
      "x-fixture-upstream"
    ],
    "gateway",
  );
});

test("unreachable Gateway targets return 502 without falling back to a healthy Tenant Vite", async (t) => {
  const fixture = await edgeFixture(t, { dynamic: true });
  await fixture.stopGateways();
  for (const hostname of ["gateway", "host.docker.internal"]) {
    await writeFile(
      fixture.targetFile,
      JSON.stringify({ hostname, port: 8080 }),
    );
    for (const requestPath of [
      "/password-setup/app.js",
      "/password-setup/styles.css",
      "/api/v1/auth/password-setups",
    ]) {
      const response = await fixture.request(requestPath, {
        method: requestPath.startsWith("/api/") ? "POST" : "GET",
      });
      assert.equal(response.status, 502, requestPath);
      assert.equal(response.headers["cache-control"], "no-store");
      assert.deepEqual(JSON.parse(response.body), {
        status: 502,
        code: "UPSTREAM_UNAVAILABLE",
      });
    }
    assert.equal(
      (
        await fixture.request("/api/v1/auth/password-setups", {
          host: "api.saas.forge.test",
        })
      ).status,
      502,
    );
    assert.equal((await fixture.request("/")).body, "Tenant SPA");
  }
});

async function edgeFixture(t, { dynamic = false } = {}) {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "sf-password-setup-edge-"),
  );
  const paths = developmentHttpsPaths(directory);
  const servers = [];
  const observed = [];
  const gatewayServers = [];
  t.after(async () => {
    await Promise.all(
      servers.map((server) => new Promise((resolve) => server.close(resolve))),
    );
    await rm(directory, { recursive: true, force: true });
  });
  await ensureCertificateMaterial(paths);
  async function listen(server) {
    servers.push(server);
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    return { hostname: "127.0.0.1", port: server.address().port };
  }
  const tenantServer = createServer((incoming, outgoing) =>
    outgoing.end("Tenant SPA"),
  );
  tenantServer.on("upgrade", (incoming, socket) => {
    socket.write(
      "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n",
    );
    socket.on("data", (data) => socket.write(data));
    socket.on("end", () => socket.end());
  });
  const tenant = await listen(tenantServer);
  const gatewayServer = (hostname) => {
    const server = createServer((incoming, outgoing) => {
      outgoing.setHeader("x-fixture-upstream", hostname);
      if (incoming.url.split("?")[0] === "/api/v1/auth/password-setups") {
        let body = "";
        incoming.setEncoding("utf8");
        incoming.on("data", (chunk) => (body += chunk));
        incoming.on("end", () => {
          observed.push({
            method: incoming.method,
            path: incoming.url,
            headers: incoming.headers,
            body,
          });
          outgoing.writeHead(409, {
            "content-type": "application/problem+json",
            "cache-control": "no-store",
            "retry-after": "7",
          });
          outgoing.end('{"code":"FIXTURE_REJECTED"}');
        });
        return;
      }
      const [type, body] = {
        "/password-setup": ["text/html", "Gateway document"],
        "/password-setup/app.js": ["text/javascript", "Gateway script"],
        "/password-setup/styles.css": ["text/css", "Gateway stylesheet"],
      }[incoming.url.split("?")[0]] ?? ["text/plain", "Gateway other"];
      outgoing.writeHead(200, {
        "content-type": type,
        "cache-control": "no-store",
      });
      outgoing.end(body);
    });
    gatewayServers.push(server);
    server.on("upgrade", (incoming, socket) => {
      socket.end(
        `HTTP/1.1 426 Upgrade Required\r\nConnection: close\r\nX-Fixture-Upstream: ${hostname}\r\nContent-Length: 0\r\n\r\n`,
      );
    });
    return server;
  };
  const gateway = await listen(gatewayServer("gateway"));
  const targetFile = path.join(directory, "api-target.json");
  if (dynamic) {
    const local = await listen(gatewayServer("host.docker.internal"));
    const connect = globalAgent.createConnection.bind(globalAgent);
    // 只替换 Docker 网络寻址；Edge、目标文件读取和上下游 HTTP/TLS 均为真实实例。
    t.mock.method(globalAgent, "createConnection", (options, callback) => {
      const destination =
        options.host === "gateway"
          ? gateway
          : options.host === "host.docker.internal"
            ? local
            : undefined;
      return destination === undefined
        ? connect(options, callback)
        : createConnection(
            { ...options, host: destination.hostname, port: destination.port },
            callback,
          );
    });
    await writeFile(
      targetFile,
      JSON.stringify({ hostname: "gateway", port: 8080 }),
    );
  }
  const edge = await listen(
    createEdgeServer({
      certificate: await readFile(paths.serverCertificate),
      key: await readFile(paths.serverKey),
      targets: {
        "console.saas.forge.test": tenant,
        "api.saas.forge.test": gateway,
      },
      apiTargetFile: dynamic ? targetFile : undefined,
    }),
  );
  const ca = await readFile(paths.certificateAuthorityCertificate);
  return {
    observed,
    targetFile,
    stopGateways: () =>
      Promise.all(
        gatewayServers.map(
          (server) => new Promise((resolve) => server.close(resolve)),
        ),
      ),
    upgrade(requestPath, host = "console.saas.forge.test") {
      return new Promise((resolve, reject) => {
        const outgoing = request({
          ...edge,
          ca,
          servername: "console.saas.forge.test",
          path: requestPath,
          headers: { host, connection: "Upgrade", upgrade: "websocket" },
        });
        outgoing.on("response", (incoming) => {
          incoming.resume();
          incoming.on("end", () =>
            resolve({ status: incoming.statusCode, headers: incoming.headers }),
          );
        });
        outgoing.on("upgrade", (incoming, socket) => {
          socket.once("error", reject);
          socket.once("data", (data) => {
            socket.end();
            try {
              assert.equal(data.toString(), "hmr-ping");
              resolve({
                status: incoming.statusCode,
                headers: incoming.headers,
              });
            } catch (error) {
              reject(error);
            }
          });
          socket.write("hmr-ping");
        });
        outgoing.on("error", reject);
        outgoing.end();
      });
    },
    request(
      requestPath,
      {
        host = "console.saas.forge.test",
        method = "GET",
        headers = {},
        body,
      } = {},
    ) {
      return new Promise((resolve, reject) => {
        const outgoing = request(
          {
            ...edge,
            ca,
            servername: "console.saas.forge.test",
            path: requestPath,
            method,
            headers: { host, ...headers },
          },
          (incoming) => {
            let content = "";
            incoming.setEncoding("utf8");
            incoming.on("data", (chunk) => (content += chunk));
            incoming.on("end", () =>
              resolve({
                status: incoming.statusCode,
                headers: incoming.headers,
                body: content,
              }),
            );
          },
        );
        outgoing.on("error", reject);
        outgoing.end(body);
      });
    },
  };
}
