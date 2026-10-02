import assert from 'node:assert/strict';
import { Configuration, Console, RemoteManifestsApi, Project, OAuthClientType, ReservedServiceKey } from '../dist/index.js';

// 仅验证正式生成 Client 的请求与反序列化；不作为真实 Gateway 或浏览器验收。
const calls = [];
const fetchApi = async (url, init) => {
  calls.push({ url, init });
  return new Response(JSON.stringify({ items: [], nextCursor: null, hasMore: false }), {
    status: 200, headers: { 'Content-Type': 'application/json' }
  });
};
const api = new Project.DefaultApi(new Project.Configuration({
  basePath: 'https://api.saas.forge.test', credentials: 'omit', accessToken: () => 'diagnostic-only', fetchApi
}));
const key = '0198c9d5-0f25-7b21-8d67-31c8652d4c8f';
const page = await api.listTasks({ projectId: key, limit: 20 });
assert.deepEqual(page, { items: [], nextCursor: null, hasMore: false });
const write = await api.updateTaskRequestOpts({
  projectId: key, taskId: key, idempotencyKey: key, ifMatch: '"2"',
  updateTask: { title: 'changed', description: null, status: 'DONE' }
});
assert.equal(write.method, 'PUT');
assert.equal(write.path, `/api/v1/projects/${key}/tasks/${key}`);
assert.equal(write.headers['If-Match'], '"2"');
assert.equal(write.headers['Idempotency-Key'], key);
assert.equal(write.headers.Authorization, 'Bearer diagnostic-only');
assert.equal(calls[0].init.credentials, 'omit');

const manifests = new RemoteManifestsApi(new Configuration({ basePath: 'https://api.saas.forge.test' }));
const register = await manifests.registerRemoteManifestRequestOpts({ registerRemoteManifestRequest: {
  module: 'project', version: '1.0.0', source: 'https://remote.saas.forge.test/project/1.0.0/remote.js',
  uiVersion: 'diagnostic', entrySha256: 'a'.repeat(64)
} });
assert.equal(register.path, '/api/v1/remote-manifests');
assert.equal(register.method, 'POST');
assert.equal((await manifests.enableRemoteManifestRequestOpts({ id: key, idempotencyKey: key })).headers['Idempotency-Key'], key);
assert.equal(typeof Console.ConsoleAuthenticationApi, 'function');
assert.equal(OAuthClientType.CiClient, 'CI_CLIENT');
assert.equal(ReservedServiceKey.RemoteDelivery, 'REMOTE_DELIVERY');
console.log('Stage 3 generated Client diagnostic passed');
