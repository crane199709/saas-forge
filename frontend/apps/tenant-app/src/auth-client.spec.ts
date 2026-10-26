/// <reference types="node" />
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
beforeEach(() => vi.stubGlobal('crypto', webcrypto))
import { createTenantClient } from '@forge/sdk'

const membershipA = { membershipId: 'membership-a', tenantId: 'tenant-a', tenantName: '租户 A' }
const membershipB = { membershipId: 'membership-b', tenantId: 'tenant-b', tenantName: '租户 B' }

function tokens(access: string, refresh: string, membership = membershipA) {
  return new Response(
    JSON.stringify({
      accessToken: access,
      refreshToken: refresh,
      tokenType: 'Bearer',
      expiresIn: 900,
      identityName: '多成员身份',
      ...membership,
    }),
  )
}

function authorizeRedirect(path: string) {
  const state = new URL(path, location.origin).searchParams.get('state')
  const response = new Response('')
  Object.defineProperty(response, 'url', {
    value: location.origin + '/?code=authorization-code&state=' + state,
  })
  return Promise.resolve(response)
}

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
          JSON.stringify({
            clientId: 'tenant-app',
            redirectUri: location.origin + '/',
            memberships: [membershipA],
          }),
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
    expect((await client.login('demo', 'correct-password')).status).toBe('signedIn')
    // 唯一的有效 Membership 自动进入，换码时显式指定。
    expect(JSON.parse(fetcher.mock.calls[2]?.[1]?.body).membershipId).toBe('membership-a')
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

it('并发请求同时遇到 401 只触发一次刷新，并各自用新 Token 重放', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'old-access', refreshToken: 'old-refresh' }),
  )
  let finishRefresh!: (response: Response) => void
  const fetcher = vi.fn().mockImplementation((path: string, init?: RequestInit) => {
    if (path === '/iam/auth/refresh')
      return new Promise<Response>((resolve) => {
        finishRefresh = resolve
      })
    const token = new Headers(init?.headers).get('Authorization')
    return Promise.resolve(
      token === 'Bearer new-access' ? new Response('{}') : new Response(null, { status: 401 }),
    )
  })
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  const first = client.request('/demo/orders')
  const second = client.request('/demo/context')
  await vi.waitFor(() => expect(finishRefresh).toBeDefined())
  finishRefresh(
    new Response(JSON.stringify({ accessToken: 'new-access', refreshToken: 'new-refresh' })),
  )
  expect((await first).status).toBe(200)
  expect((await second).status).toBe(200)
  const refreshes = fetcher.mock.calls.filter(([path]) => path === '/iam/auth/refresh')
  expect(refreshes).toHaveLength(1)
  expect(client.session()?.refreshToken).toBe('new-refresh')
})

it('刷新接口故障（如 Redis 不可用）时保留会话，不把用户登出', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'old-access', refreshToken: 'old-refresh' }),
  )
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(new Response(null, { status: 503 })),
  )
  const client = createTenantClient(sessionStorage)
  await expect(client.request('/demo/context')).rejects.toThrow('auth.unavailable')
  expect(client.session()?.refreshToken).toBe('old-refresh')
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
      new Response(
        JSON.stringify({
          clientId: 'tenant-app',
          redirectUri: location.origin + '/',
          memberships: [membershipA],
        }),
      ),
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

describe('多租户', () => {
  it('多个 Membership 先返回租户列表，选择后携带 membershipId 换码', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            clientId: 'tenant-app',
            redirectUri: location.origin + '/',
            memberships: [membershipA, membershipB],
          }),
        ),
      )
      .mockImplementationOnce(authorizeRedirect)
      .mockResolvedValueOnce(tokens('access-b', 'refresh-b', membershipB))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    const result = await client.login('multi', 'password')
    expect(result).toEqual({ status: 'selectTenant', memberships: [membershipA, membershipB] })
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(client.session()).toBeNull()
    const session = await client.selectTenant('membership-b')
    expect(session.tenantName).toBe('租户 B')
    expect(fetcher.mock.calls[1]?.[0]).toContain('/iam/oauth2/authorize?')
    expect(JSON.parse(fetcher.mock.calls[2]?.[1]?.body).membershipId).toBe('membership-b')
    expect(client.session()?.membershipId).toBe('membership-b')
  })

  it('没有有效 Membership 时进入无租户状态，不发起授权', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          clientId: 'tenant-app',
          redirectUri: location.origin + '/',
          memberships: [],
        }),
      ),
    )
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    expect(await client.login('lonely', 'password')).toEqual({ status: 'noTenant' })
    expect(fetcher).toHaveBeenCalledTimes(1)
    await expect(client.selectTenant('membership-a')).rejects.toThrow('auth.failed')
  })

  it('列出 Membership 时携带 Access Token', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'access-a', refreshToken: 'refresh-a' }),
    )
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([membershipA, membershipB])))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    expect(await client.memberships()).toEqual([membershipA, membershipB])
    expect(fetcher.mock.calls[0]?.[0]).toBe(location.origin + '/iam/auth/memberships')
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe(
      'Bearer access-a',
    )
  })

  it('切换租户用刷新凭证换发新会话，切换前发出的请求作废并通知订阅者', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'access-a', refreshToken: 'refresh-a' }),
    )
    let finishOrders!: (response: Response) => void
    const fetcher = vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finishOrders = resolve
          }),
      )
      .mockResolvedValueOnce(tokens('access-b', 'refresh-b', membershipB))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    const changes: (string | undefined)[] = []
    client.onSessionChange((session) => changes.push(session?.tenantId))
    const staleOrders = client.request('/demo/orders')
    const session = await client.switchTenant('membership-b')
    expect(session.tenantId).toBe('tenant-b')
    expect(fetcher.mock.calls[1]?.[0]).toBe('/iam/auth/switch')
    expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body)).toEqual({
      refreshToken: 'refresh-a',
      membershipId: 'membership-b',
    })
    expect(createTenantClient(sessionStorage).session()?.accessToken).toBe('access-b')
    expect(changes).toEqual(['tenant-b'])
    finishOrders(new Response('[]'))
    await expect(staleOrders).rejects.toThrow('auth.sessionChanged')
  })

  it('切换被拒绝时保留原会话', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'access-a', refreshToken: 'refresh-a' }),
    )
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ code: 'auth.tenantAccessDenied' }), { status: 403 }),
        ),
    )
    const client = createTenantClient(sessionStorage)
    await expect(client.switchTenant('membership-x')).rejects.toThrow('auth.tenantAccessDenied')
    expect(client.session()?.accessToken).toBe('access-a')
  })

  it('切换与进行中的刷新串行，使用轮换后的刷新凭证', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'access-a', refreshToken: 'refresh-a' }),
    )
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(tokens('access-a2', 'refresh-a2'))
      .mockResolvedValueOnce(tokens('access-b', 'refresh-b', membershipB))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    const refreshing = client.refresh()
    const switching = client.switchTenant('membership-b')
    await refreshing
    await switching
    expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body).refreshToken).toBe('refresh-a2')
    expect(client.session()?.tenantId).toBe('tenant-b')
  })
})

describe('切换期间的边界', () => {
  it('切换进行中遇到 401 的旧租户请求不会带新租户 Token 重发', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'access-a', refreshToken: 'refresh-a' }),
    )
    let finishSwitch!: (response: Response) => void
    const fetcher = vi.fn().mockImplementation((path: string) =>
      path === '/iam/auth/switch'
        ? new Promise<Response>((resolve) => {
            finishSwitch = resolve
          })
        : Promise.resolve(new Response(null, { status: 401 })),
    )
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    const switching = client.switchTenant('membership-b')
    const write = client.request('/demo/orders', { method: 'POST', body: '{}' })
    await vi.waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2))
    finishSwitch(tokens('access-b', 'refresh-b', membershipB))
    await switching
    await expect(write).rejects.toThrow('auth.sessionChanged')
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('选择租户被拒绝时报告 tenantAccessDenied', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            clientId: 'tenant-app',
            redirectUri: location.origin + '/',
            memberships: [membershipA, membershipB],
          }),
        ),
      )
      .mockImplementationOnce(authorizeRedirect)
      .mockResolvedValueOnce(new Response('{}', { status: 403 }))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    await client.login('multi', 'password')
    await expect(client.selectTenant('membership-b')).rejects.toThrow('auth.tenantAccessDenied')
  })

  it('切换遇到服务端故障时保留原会话', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'access-a', refreshToken: 'refresh-a' }),
    )
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(null, { status: 502 })))
    const client = createTenantClient(sessionStorage)
    await expect(client.switchTenant('membership-b')).rejects.toThrow('auth.switchFailed')
    expect(client.session()?.accessToken).toBe('access-a')
  })
})
