import assert from 'node:assert/strict'
import { createServer as createHttpServer } from 'node:http'
import { once } from 'node:events'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { createServer } from 'vite'
import { defineSubAppConfig } from '../src/vite.ts'

// HTTP 是宿主与网关的公开边界；上游替身不模拟 Vite 内部代理实现。
test('独立宿主固定代理 /api 与 /iam，新服务无需配置且未知路径保留 SPA 回退', async () => {
  const upstream = createHttpServer((req, res) => {
    res.setHeader('Content-Type', 'application/json')
    res.setHeader('Set-Cookie', 'IAM_SESSION=login; Path=/iam; HttpOnly; SameSite=Lax')
    res.end(
      JSON.stringify({
        path: req.url,
        cookie: req.headers.cookie,
        authorization: req.headers.authorization,
      }),
    )
  }).listen(0, '127.0.0.1')
  await once(upstream, 'listening')
  const address = upstream.address() as { port: number }
  const root = await mkdtemp(join(tmpdir(), 'forge-proxy-'))
  await writeFile(join(root, 'index.html'), '<h1>Standalone host</h1>')
  const config = defineSubAppConfig({
    appCode: 'new-service',
    port: 0,
    gateway: `http://127.0.0.1:${address.port}`,
  })({ command: 'serve', mode: 'development' })
  const server = await createServer({
    ...config,
    configFile: false,
    root,
    server: { ...config.server, host: '127.0.0.1' },
  })
  try {
    await server.listen()
    const origin = server.resolvedUrls!.local[0]!
    const business = await fetch(`${origin}api/new-service/orders?title=hello%20world`, {
      headers: { Authorization: 'Bearer tenant-token' },
    })
    assert.deepEqual(await business.json(), {
      path: '/new-service/orders?title=hello%20world',
      authorization: 'Bearer tenant-token',
    })
    assert.equal((await (await fetch(`${origin}api?probe=1`)).json()).path, '/?probe=1')
    const auth = await fetch(`${origin}iam/auth/refresh`, {
      headers: { Cookie: 'IAM_SESSION=login' },
    })
    assert.equal((await auth.json()).path, '/iam/auth/refresh')
    assert.equal(
      auth.headers.get('set-cookie'),
      'IAM_SESSION=login; Path=/iam; HttpOnly; SameSite=Lax',
    )
    for (const path of ['apiXYZ', 'iamXYZ', 'apps/new-service/home', 'unknown-route']) {
      const response = await fetch(`${origin}${path}`, { headers: { Accept: 'text/html' } })
      assert.match(await response.text(), /Standalone host/, path)
    }
    assert.match(await (await fetch(`${origin}@vite/client`)).text(), /WebSocket/)
  } finally {
    await server.close()
    await new Promise<void>((resolve) => upstream.close(() => resolve()))
    await rm(root, { recursive: true, force: true })
  }
})
