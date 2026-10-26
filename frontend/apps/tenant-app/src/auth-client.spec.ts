/// <reference types="node" />
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
beforeEach(() => vi.stubGlobal('crypto', webcrypto))
import { createTenantClient } from '@forge/sdk'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

describe('Tenant SDK', () => {
  it('登录后保存会话并给业务请求携带 Token', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ clientId: 'tenant-app', redirectUri: location.origin + '/' }),
        ),
      )
      .mockImplementationOnce((path: string) => {
        const state = new URL(path, location.origin).searchParams.get('state')
        const response = new Response('')
        Object.defineProperty(response, 'url', {
          value: location.origin + '/?code=authorization-code&state=' + state,
        })
        return Promise.resolve(response)
      })
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            accessToken: 'access-1',
            refreshToken: 'refresh-1',
            tokenType: 'Bearer',
            expiresIn: 900,
            identityName: '演示身份',
            tenantName: '演示租户',
          }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            identityId: 'identity-1',
            tenantId: 'tenant-1',
            membershipId: 'membership-1',
            version: 1,
          }),
        ),
      )
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    await client.login('demo', 'correct-password')
    const restored = createTenantClient(sessionStorage)
    expect(restored.session()?.tenantName).toBe('演示租户')
    const response = await restored.request('/demo/context')
    expect((await response.json()).tenantId).toBe('tenant-1')
    expect(new Headers(fetcher.mock.calls[3]?.[1]?.headers).get('Authorization')).toBe(
      'Bearer access-1',
    )
  })
})

it('过期请求刷新一次后使用新 Token，旧刷新凭证不再保存', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'old-access', refreshToken: 'old-refresh' }),
  )
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(new Response(null, { status: 401 }))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ accessToken: 'new-access', refreshToken: 'new-refresh' })),
    )
    .mockResolvedValueOnce(new Response('{}'))
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  expect((await client.request('/demo/context')).status).toBe(200)
  expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body).refreshToken).toBe('old-refresh')
  expect(new Headers(fetcher.mock.calls[2]?.[1]?.headers).get('Authorization')).toBe(
    'Bearer new-access',
  )
  expect(client.session()?.refreshToken).toBe('new-refresh')
})

it('刷新失败清除会话且不重复业务请求', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'old-access', refreshToken: 'old-refresh' }),
  )
  const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  await expect(client.request('/demo/context')).rejects.toThrow('auth.failed')
  expect(client.session()).toBeNull()
  expect(fetcher).toHaveBeenCalledTimes(2)
})

it('拒绝向外站发送凭证，损坏的本地会话可恢复', async () => {
  sessionStorage.setItem('forge.tenant.session', 'broken')
  const client = createTenantClient(sessionStorage)
  expect(client.session()).toBeNull()
  await expect(client.request('https://outside.test/demo')).rejects.toThrow('auth.invalidPath')
  await expect(client.request('//outside.test/demo')).rejects.toThrow('auth.invalidPath')
})

it('URL 规范化后仍不能把凭证发到外站', async () => {
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  for (const path of ['/\t/outside.test/demo', '/\n/outside.test/demo', '/\r/outside.test/demo']) {
    await expect(client.request(path)).rejects.toThrow('auth.invalidPath')
  }
  expect(fetcher).not.toHaveBeenCalled()
})

it('清除会话后，已发出的刷新响应不能恢复旧身份', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'old-access', refreshToken: 'old-refresh' }),
  )
  let finish!: (response: Response) => void
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve
        }),
    ),
  )
  const client = createTenantClient(sessionStorage)
  const pending = client.refresh()
  client.clear()
  finish(new Response(JSON.stringify({ accessToken: 'late-access', refreshToken: 'late-refresh' })))
  await expect(pending).rejects.toThrow('auth.sessionChanged')
  expect(client.session()).toBeNull()
})

it('授权回调 state 不匹配时不交换授权码', async () => {
  const callback = new Response('')
  Object.defineProperty(callback, 'url', {
    value: location.origin + '/?code=stolen-code&state=wrong-state',
  })
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ clientId: 'tenant-app', redirectUri: location.origin + '/' })),
    )
    .mockResolvedValueOnce(callback)
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  await expect(client.login('demo', 'password')).rejects.toThrow('auth.failed')
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect(client.session()).toBeNull()
})

it('退出携带 IAM Cookie，立即清除本地凭证，失败不能假装退出成功', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r' }),
  )
  let finish!: (response: Response) => void
  const fetcher = vi.fn().mockImplementation(
    () =>
      new Promise<Response>((resolve) => {
        finish = resolve
      }),
  )
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  const pending = client.logout()
  expect(client.session()).toBeNull()
  expect(fetcher).toHaveBeenCalledWith(
    '/iam/auth/logout',
    expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
    }),
  )
  finish(new Response(null, { status: 500 }))
  await expect(pending).rejects.toThrow('auth.logoutFailed')
})
