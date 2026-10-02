import assert from "node:assert/strict";
import { request } from "node:https";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test, { after } from "node:test";
import {
  developmentHttpsPaths,
  ensureCertificateMaterial,
} from "../local-https-development.mjs";

// 服务端静态交付检查使用独立临时制品，不依赖任何前端源码或构建工具。
const artifacts = await mkdtemp(path.join(os.tmpdir(), "sf-remote-artifacts-"));
for (const version of ["v1", "v2"]) {
  await mkdir(path.join(artifacts, version));
  for (const [name, content] of [
    ["remote.js", `export const version = '${version}';`],
    ["styles.css", `/* ${version} */ body { color: black; }`],
    [
      "image.svg",
      '<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"/>',
    ],
  ])
    await writeFile(path.join(artifacts, version, name), content);
}
await mkdir(path.join(artifacts, 'project', '1.0.0'), { recursive: true });
await writeFile(path.join(artifacts, 'project', '1.0.0', 'remote.js'), 'export const business = true;');
const previousBusinessArtifacts = process.env.SF_BUSINESS_REMOTE_DIRECTORY;
process.env.SF_BUSINESS_REMOTE_DIRECTORY = artifacts;
const previousArtifacts = process.env.SF_REMOTE_STATIC_DIRECTORY;
process.env.SF_REMOTE_STATIC_DIRECTORY = artifacts;
after(async () => {
  if (previousArtifacts === undefined)
    delete process.env.SF_REMOTE_STATIC_DIRECTORY;
  else process.env.SF_REMOTE_STATIC_DIRECTORY = previousArtifacts;
  if (previousBusinessArtifacts === undefined) delete process.env.SF_BUSINESS_REMOTE_DIRECTORY;
  else process.env.SF_BUSINESS_REMOTE_DIRECTORY = previousBusinessArtifacts;
  await rm(artifacts, { recursive: true, force: true });
});

// 此夹具的证书与请求固定使用开发域，不能继承产品 CI 的对照根域。
const acceptanceRootDomain = process.env.SF_ACCEPTANCE_ROOT_DOMAIN;
process.env.SF_ACCEPTANCE_ROOT_DOMAIN = "saas.forge.test";
const { createEdgeServer } =
  await import("../../deploy/compose/local-https-development/edge.mjs");
if (acceptanceRootDomain === undefined)
  delete process.env.SF_ACCEPTANCE_ROOT_DOMAIN;
else process.env.SF_ACCEPTANCE_ROOT_DOMAIN = acceptanceRootDomain;

async function fixture(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "sf-remote-edge-"));
  const paths = developmentHttpsPaths(directory);
  await ensureCertificateMaterial(paths);
  const edge = createEdgeServer({
    certificate: await readFile(paths.serverCertificate),
    key: await readFile(paths.serverKey),
  });
  await new Promise((resolve) => edge.listen(0, "127.0.0.1", resolve));
  t.after(async () => {
    await new Promise((resolve) => edge.close(resolve));
    await rm(directory, { recursive: true, force: true });
  });
  const ca = await readFile(paths.certificateAuthorityCertificate);
  return (
    pathname,
    origin = "https://console.saas.forge.test",
    method = "GET",
  ) =>
    new Promise((resolve, reject) => {
      const req = request(
        {
          hostname: "127.0.0.1",
          port: edge.address().port,
          servername: "remote.saas.forge.test",
          ca,
          path: pathname,
          method,
          headers: {
            host: "remote.saas.forge.test",
            ...(origin === undefined ? {} : { origin }),
          },
        },
        (response) => {
          const chunks = [];
          response.on("data", (chunk) => chunks.push(chunk));
          response.on("end", () =>
            resolve({
              status: response.statusCode,
              headers: response.headers,
              body: Buffer.concat(chunks),
            }),
          );
        },
      );
      req.on("error", reject);
      req.end();
    });
}

test("both version paths deliver stable, distinct modules, CSS and images without HTML fallback", async (t) => {
  const get = await fixture(t);
  const modules = [];
  for (const version of ["v1", "v2"]) {
    for (const [file, mime] of [
      ["remote.js", "text/javascript"],
      ["styles.css", "text/css"],
      ["image.svg", "image/svg+xml"],
    ]) {
      const url = `/static-acceptance/${version}/${file}`;
      const first = await get(url);
      assert.equal(first.status, 200);
      assert.ok(first.headers["content-type"].startsWith(mime));
      assert.deepEqual((await get(url)).body, first.body);
      assert.equal(
        first.headers["cache-control"],
        "public, max-age=31536000, immutable",
      );
      if (file === "remote.js") modules.push(first.body.toString());
    }
  }
  assert.notEqual(modules[0], modules[1]);
  const missing = await get("/static-acceptance/v1/missing.js");
  assert.equal(missing.status, 404);
  assert.doesNotMatch(missing.body.toString(), /<!doctype|<html/iu);
});

test("Remote grants only exact credential-free Tenant CORS and only read methods", async (t) => {
  const get = await fixture(t);
  for (const origin of [
    "https://platform.saas.forge.test",
    "https://evil.saas.forge.test",
    "https://console.saas.forge.test.evil.example",
    "null",
  ]) {
    const response = await get("/static-acceptance/v1/remote.js", origin);
    assert.equal(response.status, 200);
    assert.equal(response.headers["access-control-allow-origin"], undefined);
    assert.equal(
      response.headers["access-control-allow-credentials"],
      undefined,
    );
    assert.equal(response.headers.vary, "Origin");
  }
  const rejected = await get(
    "/static-acceptance/v1/remote.js",
    "https://console.saas.forge.test",
    "POST",
  );
  assert.equal(rejected.status, 405);
  const head = await get(
    "/static-acceptance/v1/remote.js",
    "https://console.saas.forge.test",
    "HEAD",
  );
  assert.equal(head.status, 200);
  assert.equal(head.body.length, 0);
});

test("Tenant can read a built Remote ES module through trusted fourth-domain HTTPS", async (t) => {
  const get = await fixture(t);
  const response = await get("/static-acceptance/v1/remote.js");
  assert.equal(response.status, 200);
  assert.match(response.headers["content-type"], /javascript/u);
  assert.equal(
    response.headers["access-control-allow-origin"],
    "https://console.saas.forge.test",
  );
  assert.equal(response.headers["access-control-allow-credentials"], undefined);
  assert.match(response.body.toString(), /export/u);
});

test('business Remote serves only the exact versioned JavaScript entry with credential-free CORS', async t => {
  const get = await fixture(t);
  const entry = await get('/project/1.0.0/remote.js');
  assert.equal(entry.status, 200);
  assert.equal(entry.body.toString(), 'export const business = true;');
  assert.equal(entry.headers['access-control-allow-origin'], 'https://console.saas.forge.test');
  assert.equal(entry.headers['access-control-allow-credentials'], undefined);
  assert.match(entry.headers['cache-control'], /immutable/);
  for (const path of ['/project/1.0.1/remote.js', '/project/1.0.0/manifest-declaration.json',
    '/project/1.0.0/remote.js?version=2', '/project/../remote.js', '/project/01.0.0/remote.js']) {
    assert.equal((await get(path)).status, 404);
  }
  assert.equal((await get('/project/1.0.0/remote.js', 'https://remote.saas.forge.test')).headers['access-control-allow-origin'], undefined);
  assert.equal((await get('/project/1.0.0/remote.js', undefined, 'POST')).status, 405);
});
