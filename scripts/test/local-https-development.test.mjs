import { spawnSync } from "node:child_process";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { request as requestHttps } from "node:https";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  certificateCoversExpectedHosts,
  developmentHosts,
  hasExpectedHosts,
  developmentHttpsPaths,
  ensureCertificateMaterial,
} from "../local-https-development.mjs";
import {
  copyOriginalHeaders,
  createEdgeServer,
  parseApiTarget,
  targetForHost,
} from "../../deploy/compose/local-https-development/edge.mjs";

test("creates one reusable local certificate for all four development hosts", async (t) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "sf-local-https-"));
  const paths = developmentHttpsPaths(directory);
  t.after(() => rm(directory, { recursive: true, force: true }));

  assert.equal(await ensureCertificateMaterial(paths), "created");
  assert.equal(
    await certificateCoversExpectedHosts(paths.serverCertificate),
    true,
  );
  const firstCertificate = await readFile(paths.serverCertificate, "utf8");
  const { X509Certificate } = await import("node:crypto");
  assert.equal(
    new X509Certificate(firstCertificate).checkHost("remote.saas.forge.test"),
    "remote.saas.forge.test",
  );

  assert.equal(await ensureCertificateMaterial(paths), "reused");
  assert.equal(
    await readFile(paths.serverCertificate, "utf8"),
    firstCertificate,
  );
});

test("keeps the three existing proxy targets and preserves browser security headers verbatim", () => {
  assert.deepEqual(targetForHost("platform.saas.forge.test"), {
    hostname: "host.docker.internal",
    port: 5173,
  });
  assert.deepEqual(targetForHost("api.saas.forge.test"), {
    hostname: "gateway",
    port: 8080,
  });
  assert.deepEqual(targetForHost("console.saas.forge.test"), {
    hostname: "host.docker.internal",
    port: 5174,
  });
  assert.equal(targetForHost("unknown.saas.forge.test"), undefined);
  assert.equal(targetForHost("platform.saas.forge.test:443"), undefined);

  const headers = [
    "Origin",
    "https://platform.saas.forge.test",
    "Cookie",
    "__Host-sf_platform_refresh=opaque",
    "Sec-Fetch-Site",
    "same-site",
    "Authorization",
    "Bearer opaque",
  ];
  assert.deepEqual(copyOriginalHeaders(headers), headers);
});

test("forwards browser security headers without Edge synthesis or rewriting", async (t) => {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "sf-local-https-edge-"),
  );
  const paths = developmentHttpsPaths(directory);
  await ensureCertificateMaterial(paths);
  const seen = new Map();
  const upstream = createServer((incoming, outgoing) => {
    for (let index = 0; index < incoming.rawHeaders.length; index += 2) {
      seen.set(
        incoming.rawHeaders[index].toLowerCase(),
        incoming.rawHeaders[index + 1],
      );
    }
    outgoing.writeHead(204).end();
  });
  await listen(upstream);
  const upstreamPort = upstream.address().port;
  const edge = createEdgeServer({
    certificate: await readFile(paths.serverCertificate),
    key: await readFile(paths.serverKey),
    targets: {
      "api.saas.forge.test": { hostname: "127.0.0.1", port: upstreamPort },
    },
  });
  await listen(edge);
  t.after(async () => {
    await Promise.all([close(edge), close(upstream)]);
    await rm(directory, { recursive: true, force: true });
  });

  const status = await edgeRequest(
    edge.address().port,
    await readFile(paths.certificateAuthorityCertificate),
    {
      host: "api.saas.forge.test",
      origin: "https://platform.saas.forge.test",
      cookie: "__Host-sf_platform_refresh=opaque",
      "sec-fetch-site": "same-site",
      authorization: "Bearer opaque",
    },
  );
  assert.equal(status, 204);
  assert.equal(seen.get("origin"), "https://platform.saas.forge.test");
  assert.equal(seen.get("cookie"), "__Host-sf_platform_refresh=opaque");
  assert.equal(seen.get("sec-fetch-site"), "same-site");
  assert.equal(seen.get("authorization"), "Bearer opaque");
});

test("accepts only the fixed local Gateway override target", () => {
  assert.deepEqual(
    parseApiTarget(
      JSON.stringify({ hostname: "host.docker.internal", port: 8080 }),
    ),
    { hostname: "host.docker.internal", port: 8080 },
  );
  assert.deepEqual(
    parseApiTarget(JSON.stringify({ hostname: "gateway", port: 8080 })),
    {
      hostname: "gateway",
      port: 8080,
    },
  );
  assert.equal(
    parseApiTarget(
      JSON.stringify({ hostname: "untrusted.example", port: 8080 }),
    ),
    undefined,
  );
  assert.equal(parseApiTarget("{"), undefined);
});

for (const host of ["platform.saas.forge.test", "console.saas.forge.test"]) {
  test(`relays ${host} WebSocket upgrade used by Vite HMR`, async (t) => {
    const directory = await mkdtemp(
      path.join(os.tmpdir(), "sf-local-https-hmr-"),
    );
    const paths = developmentHttpsPaths(directory);
    await ensureCertificateMaterial(paths);
    const upstream = createServer();
    upstream.on("upgrade", (incoming, socket) => {
      assert.equal(incoming.headers.host, host);
      socket.write(
        "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n",
      );
      socket.on("data", (data) => socket.write(data));
      socket.on("end", () => socket.end());
    });
    await listen(upstream);
    const edge = createEdgeServer({
      certificate: await readFile(paths.serverCertificate),
      key: await readFile(paths.serverKey),
      targets: {
        [host]: { hostname: "127.0.0.1", port: upstream.address().port },
      },
    });
    await listen(edge);
    t.after(async () => {
      await Promise.all([close(edge), close(upstream)]);
      await rm(directory, { recursive: true, force: true });
    });

    await hmrUpgrade(
      edge.address().port,
      await readFile(paths.certificateAuthorityCertificate),
      host,
    );
  });
}

test("upgrades a three-Host leaf with the existing CA and then remains idempotent", async (t) => {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "sf-local-https-upgrade-"),
  );
  t.after(() => rm(directory, { recursive: true, force: true }));
  const paths = developmentHttpsPaths(directory);
  await ensureCertificateMaterial(paths);
  const authority = await readFile(paths.certificateAuthorityCertificate);
  const authorityKey = await readFile(paths.certificateAuthorityKey);
  await writeFile(
    paths.serverExtensions,
    "subjectAltName=DNS:platform.saas.forge.test,DNS:console.saas.forge.test,DNS:api.saas.forge.test\n",
  );
  const result = spawnSync("openssl", [
    "x509",
    "-req",
    "-in",
    paths.serverRequest,
    "-CA",
    paths.certificateAuthorityCertificate,
    "-CAkey",
    paths.certificateAuthorityKey,
    "-CAcreateserial",
    "-days",
    "397",
    "-extfile",
    paths.serverExtensions,
    "-out",
    paths.serverCertificate,
  ]);
  assert.equal(result.status, 0);
  assert.equal(
    await certificateCoversExpectedHosts(paths.serverCertificate),
    false,
  );
  assert.equal(await ensureCertificateMaterial(paths), "created");
  assert.deepEqual(
    await readFile(paths.certificateAuthorityCertificate),
    authority,
  );
  assert.deepEqual(await readFile(paths.certificateAuthorityKey), authorityKey);
  assert.equal(
    await certificateCoversExpectedHosts(paths.serverCertificate),
    true,
  );
  assert.equal(await ensureCertificateMaterial(paths), "reused");
  assert.deepEqual(developmentHosts, [
    "platform.saas.forge.test",
    "console.saas.forge.test",
    "api.saas.forge.test",
    "remote.saas.forge.test",
  ]);
});

test("routes both Console HTTPS Hosts independently and rejects unknown Hosts", async (t) => {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "sf-two-console-edge-"),
  );
  const paths = developmentHttpsPaths(directory);
  await ensureCertificateMaterial(paths);
  const platform = createServer((req, res) => res.writeHead(201).end());
  const tenant = createServer((req, res) => res.writeHead(202).end());
  await Promise.all([listen(platform), listen(tenant)]);
  const edge = createEdgeServer({
    certificate: await readFile(paths.serverCertificate),
    key: await readFile(paths.serverKey),
    targets: {
      "platform.saas.forge.test": {
        hostname: "127.0.0.1",
        port: platform.address().port,
      },
      "console.saas.forge.test": {
        hostname: "127.0.0.1",
        port: tenant.address().port,
      },
    },
  });
  await listen(edge);
  t.after(async () => {
    await Promise.all([close(edge), close(platform), close(tenant)]);
    await rm(directory, { recursive: true, force: true });
  });
  const ca = await readFile(paths.certificateAuthorityCertificate);
  for (const [host, expected] of [
    ["platform.saas.forge.test", 201],
    ["console.saas.forge.test", 202],
    ["unknown.saas.forge.test", 421],
    ["console.saas.forge.test:443", 421],
  ]) {
    assert.equal(
      await edgeRequest(edge.address().port, ca, { host }),
      expected,
    );
  }
});

test("hosts upgrade requires explicit consent, is idempotent and refuses noninteractive authorization", async () => {
  const { installHosts, confirm } =
    await import("../local-https-development.mjs");
  let content = "127.0.0.1 platform.saas.forge.test api.saas.forge.test\n";
  let authorized = false;
  let writes = 0;
  const system = {
    readHosts: async () => content,
    authorize: async (question, expected) => {
      assert.equal(expected, "HOSTS");
      assert.ok(question.includes("四个"));
      authorized = true;
    },
    appendHosts: async (entry) => {
      assert.equal(authorized, true);
      content += entry;
      writes++;
    },
  };
  await assert.rejects(
    () =>
      installHosts({
        ...system,
        authorize: async () => {
          throw new Error("declined");
        },
      }),
    /declined/u,
  );
  assert.equal(writes, 0);
  await installHosts(system);
  assert.equal(hasExpectedHosts(content), true);
  await installHosts({
    ...system,
    authorize: async () => {
      throw new Error("must skip repeated authorization");
    },
  });
  assert.equal(writes, 1);
  if (!process.stdin.isTTY || !process.stdout.isTTY) {
    await assert.rejects(() => confirm("test", "HOSTS"), /非交互终端/u);
  }
});

function listen(server) {
  return new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
}

function close(server) {
  return new Promise((resolve, reject) =>
    server.close((error) => (error ? reject(error) : resolve())),
  );
}

function edgeRequest(port, certificateAuthority, headers) {
  return new Promise((resolve, reject) => {
    const request_ = requestHttps(
      {
        hostname: "127.0.0.1",
        port,
        method: "POST",
        path: "/api/v1/auth/refresh",
        ca: certificateAuthority,
        servername: "api.saas.forge.test",
        headers: { ...headers, "content-type": "application/json" },
      },
      (response) => {
        response.resume();
        response.on("end", () => resolve(response.statusCode));
      },
    );
    request_.on("error", reject);
    request_.end(JSON.stringify({ sessionSlot: "PLATFORM" }));
  });
}

function hmrUpgrade(port, certificateAuthority, host) {
  return new Promise((resolve, reject) => {
    const request_ = requestHttps({
      hostname: "127.0.0.1",
      port,
      path: "/",
      ca: certificateAuthority,
      servername: host,
      headers: {
        host,
        connection: "Upgrade",
        upgrade: "websocket",
      },
    });
    request_.on("upgrade", (response, socket) => {
      assert.equal(response.statusCode, 101);
      socket.write("hmr-ping");
      socket.once("data", (data) => {
        assert.equal(data.toString(), "hmr-ping");
        socket.end();
        resolve();
      });
      socket.once("error", reject);
    });
    request_.on("error", reject);
    request_.end();
  });
}
