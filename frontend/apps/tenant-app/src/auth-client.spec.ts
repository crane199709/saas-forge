/// <reference types="node" />
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
beforeEach(() => vi.stubGlobal('crypto', webcrypto))
import { ApiError, createTenantClient } from '@crane199709/sdk'

function envelope(body: object, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function ok(data?: unknown) {
  return envelope({ code: 'ok', httpStatus: 200, data, traceId: 'trace-ok' })
}

function fail(code: string, status: number, userTip = '提示：' + code) {
  return envelope(
    { code, httpStatus: status, errorMessage: code, userTip, traceId: 'trace-' + status },
    status,
  )
}

const membershipA = { membershipId: 'membership-a', tenantId: 'tenant-a', tenantName: '租户 A' }
const membershipB = { membershipId: 'membership-b', tenantId: 'tenant-b', tenantName: '租户 B' }

function tokens(access: string, refresh: string, membership = membershipA) {
  return ok({
    accessToken: access,
    refreshToken: refresh,
    tokenType: 'Bearer',
    expiresIn: 900,
    identityName: '多成员身份',
    ...membership,
  })
}

function loginSettings(memberships: object[]) {
  return ok({ clientId: 'tenant-app', redirectUri: location.origin + '/', memberships })
}

function authorizeRedirect(path: string) {
  const state = new URL(path, location.origin).searchParams.get('state')
  const response = new Response('')
  Object.defineProperty(response, 'url', {
    value: location.origin + '/?code=authorization-code&state=' + state,
  })
  return Promise.resolve(response)
}

function signedIn() {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access-a', refreshToken: 'refresh-a' }),
  )
}

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

describe('Tenant SDK', () => {
  it('登录后保存会话并给业务请求携带 Token', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(loginSettings([membershipA]))
      .mockImplementationOnce(authorizeRedirect)
      .mockResolvedValueOnce(
        ok({
          accessToken: 'access-1',
          refreshToken: 'refresh-1',
          tokenType: 'Bearer',
          expiresIn: 900,
          identityName: '演示身份',
          tenantName: '演示租户',
        }),
      )
      .mockResolvedValueOnce(
        ok({
          identityId: 'identity-1',
          tenantId: 'tenant-1',
          membershipId: 'membership-1',
          version: 1,
        }),
      )
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    expect((await client.login('demo', 'correct-password')).status).toBe('signedIn')
    // 唯一的有效 Membership 自动进入，换码时显式指定。
    expect(JSON.parse(fetcher.mock.calls[2]?.[1]?.body).membershipId).toBe('membership-a')
    const restored = createTenantClient(sessionStorage)
    expect(restored.session()?.tenantName).toBe('演示租户')
    const context = await restored.api<{ tenantId: string }>('/demo/context')
    expect(context.tenantId).toBe('tenant-1')
    expect(new Headers(fetcher.mock.calls[3]?.[1]?.headers).get('Authorization')).toBe(
      'Bearer access-1',
    )
  })

  it('账号或密码错误时抛出 auth.failed，并带上 userTip 与 traceId', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(fail('auth.failed', 401, '账号或密码错误，或登录会话已失效')),
    )
    const client = createTenantClient(sessionStorage)
    await expect(client.login('demo', 'wrong')).rejects.toMatchObject({
      code: 'auth.failed',
      userTip: '账号或密码错误，或登录会话已失效',
      traceId: 'trace-401',
    })
  })

  it('登录遇到服务不可用时如实报告，不当作密码错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(fail('system.unavailable', 503)))
    const client = createTenantClient(sessionStorage)
    await expect(client.login('demo', 'password')).rejects.toMatchObject({
      code: 'system.unavailable',
      httpStatus: 503,
    })
  })
})

it('过期请求刷新一次后使用新 Token，旧刷新凭证不再保存', async () => {
  signedIn()
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(fail('auth.unauthenticated', 401))
    .mockResolvedValueOnce(tokens('new-access', 'new-refresh'))
    .mockResolvedValueOnce(ok({}))
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  expect((await client.request('/demo/context')).status).toBe(200)
  expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body).refreshToken).toBe('refresh-a')
  expect(new Headers(fetcher.mock.calls[2]?.[1]?.headers).get('Authorization')).toBe(
    'Bearer new-access',
  )
  expect(client.session()?.refreshToken).toBe('new-refresh')
})

it('刷新凭证被拒绝时清除会话且不重复业务请求', async () => {
  signedIn()
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(fail('auth.unauthenticated', 401))
    .mockResolvedValueOnce(fail('auth.failed', 401))
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  await expect(client.request('/demo/context')).rejects.toThrow('auth.failed')
  expect(client.session()).toBeNull()
  expect(fetcher).toHaveBeenCalledTimes(2)
})

it('并发请求同时遇到 401 只触发一次刷新，并各自用新 Token 重放', async () => {
  signedIn()
  let finishRefresh!: (response: Response) => void
  const fetcher = vi.fn().mockImplementation((path: string, init?: RequestInit) => {
    if (path === '/iam/auth/refresh')
      return new Promise<Response>((resolve) => {
        finishRefresh = resolve
      })
    const token = new Headers(init?.headers).get('Authorization')
    return Promise.resolve(
      token === 'Bearer new-access' ? ok({}) : fail('auth.unauthenticated', 401),
    )
  })
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  const first = client.request('/demo/orders')
  const second = client.request('/demo/context')
  await vi.waitFor(() => expect(finishRefresh).toBeDefined())
  finishRefresh(tokens('new-access', 'new-refresh'))
  expect((await first).status).toBe(200)
  expect((await second).status).toBe(200)
  const refreshes = fetcher.mock.calls.filter(([path]) => path === '/iam/auth/refresh')
  expect(refreshes).toHaveLength(1)
  expect(client.session()?.refreshToken).toBe('new-refresh')
})

it('刷新接口故障（如 Redis 不可用）时保留会话，不把用户登出', async () => {
  signedIn()
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(fail('auth.unauthenticated', 401))
      .mockResolvedValueOnce(fail('system.unavailable', 503, '服务暂时不可用，请稍后重试')),
  )
  const client = createTenantClient(sessionStorage)
  await expect(client.api('/demo/context')).rejects.toMatchObject({
    code: 'system.unavailable',
    userTip: '服务暂时不可用，请稍后重试',
  })
  expect(client.session()?.refreshToken).toBe('refresh-a')
})

it('刷新得到代理返回的非信封错误时保留会话', async () => {
  signedIn()
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(fail('auth.unauthenticated', 401))
      .mockResolvedValueOnce(new Response('<html>Bad Gateway</html>', { status: 502 })),
  )
  const client = createTenantClient(sessionStorage)
  await expect(client.request('/demo/context')).rejects.toMatchObject({
    code: 'system.error',
    httpStatus: 502,
  })
  expect(client.session()?.refreshToken).toBe('refresh-a')
})

it.each([
  ['其他错误码的信封', () => fail('auth.unauthenticated', 401)],
  ['非信封响应', () => new Response('Unauthorized', { status: 401 })],
])('刷新接口返回 401 %s时同样视为刷新凭证被拒绝并清除会话', async (_, refreshed) => {
  signedIn()
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(fail('auth.unauthenticated', 401))
      .mockResolvedValueOnce(refreshed()),
  )
  const client = createTenantClient(sessionStorage)
  await expect(client.request('/demo/context')).rejects.toMatchObject({ httpStatus: 401 })
  expect(client.session()).toBeNull()
})

it('刷新遇到网络故障时保留会话', async () => {
  signedIn()
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(fail('auth.unauthenticated', 401))
      .mockRejectedValueOnce(new TypeError('Failed to fetch')),
  )
  const client = createTenantClient(sessionStorage)
  await expect(client.request('/demo/context')).rejects.toThrow('Failed to fetch')
  expect(client.session()?.refreshToken).toBe('refresh-a')
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
  signedIn()
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
  // 取得轮换锁、刷新请求已发出后再清除。
  await vi.waitFor(() => expect(finish).toBeDefined())
  client.clear()
  finish(tokens('late-access', 'late-refresh'))
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
    .mockResolvedValueOnce(loginSettings([membershipA]))
    .mockResolvedValueOnce(callback)
  vi.stubGlobal('fetch', fetcher)
  const client = createTenantClient(sessionStorage)
  await expect(client.login('demo', 'password')).rejects.toThrow('auth.failed')
  expect(fetcher).toHaveBeenCalledTimes(2)
  expect(client.session()).toBeNull()
})

it('退出携带 IAM Cookie，立即清除本地凭证，失败不能假装退出成功', async () => {
  signedIn()
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
  finish(fail('system.error', 500))
  await expect(pending).rejects.toThrow('auth.logoutFailed')
})

it('退出成功返回 200 信封', async () => {
  signedIn()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(ok()))
  const client = createTenantClient(sessionStorage)
  await expect(client.logout()).resolves.toBeUndefined()
  expect(client.session()).toBeNull()
})

describe('多租户', () => {
  it('多个 Membership 先返回租户列表，选择后携带 membershipId 换码', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(loginSettings([membershipA, membershipB]))
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
    const fetcher = vi.fn().mockResolvedValueOnce(loginSettings([]))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    expect(await client.login('lonely', 'password')).toEqual({ status: 'noTenant' })
    expect(fetcher).toHaveBeenCalledTimes(1)
    await expect(client.selectTenant('membership-a')).rejects.toThrow('auth.failed')
  })

  it('列出 Membership 时携带 Access Token', async () => {
    signedIn()
    const fetcher = vi.fn().mockResolvedValueOnce(ok([membershipA, membershipB]))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    expect(await client.memberships()).toEqual([membershipA, membershipB])
    expect(fetcher.mock.calls[0]?.[0]).toBe(location.origin + '/iam/auth/memberships')
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe(
      'Bearer access-a',
    )
  })

  it('切换租户用刷新凭证换发新会话，切换前发出的请求作废并通知订阅者', async () => {
    signedIn()
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
    finishOrders(ok([]))
    await expect(staleOrders).rejects.toThrow('auth.sessionChanged')
  })

  it('切换被拒绝时保留原会话', async () => {
    signedIn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(fail('auth.tenantAccessDenied', 403)))
    const client = createTenantClient(sessionStorage)
    await expect(client.switchTenant('membership-x')).rejects.toMatchObject({
      code: 'auth.tenantAccessDenied',
      userTip: '提示：auth.tenantAccessDenied',
    })
    expect(client.session()?.accessToken).toBe('access-a')
  })

  it('切换时刷新凭证被拒绝则清除会话', async () => {
    signedIn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(fail('auth.failed', 401)))
    const client = createTenantClient(sessionStorage)
    await expect(client.switchTenant('membership-b')).rejects.toThrow('auth.failed')
    expect(client.session()).toBeNull()
  })

  it('切换与进行中的刷新串行，使用轮换后的刷新凭证', async () => {
    signedIn()
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
    signedIn()
    let finishSwitch!: (response: Response) => void
    const fetcher = vi.fn().mockImplementation((path: string) =>
      path === '/iam/auth/switch'
        ? new Promise<Response>((resolve) => {
            finishSwitch = resolve
          })
        : Promise.resolve(fail('auth.unauthenticated', 401)),
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
      .mockResolvedValueOnce(loginSettings([membershipA, membershipB]))
      .mockImplementationOnce(authorizeRedirect)
      .mockResolvedValueOnce(fail('auth.tenantAccessDenied', 403))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    await client.login('multi', 'password')
    await expect(client.selectTenant('membership-b')).rejects.toThrow('auth.tenantAccessDenied')
  })

  it('切换遇到服务端故障时保留原会话', async () => {
    signedIn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(fail('system.unavailable', 503)))
    const client = createTenantClient(sessionStorage)
    await expect(client.switchTenant('membership-b')).rejects.toThrow('system.unavailable')
    expect(client.session()?.accessToken).toBe('access-a')
  })
})

describe('统一信封', () => {
  it('api 成功时直接返回 data，并携带 Access Token', async () => {
    signedIn()
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        envelope({ code: 'ok', httpStatus: 200, data: { tenantId: 'tenant-a' }, traceId: 't-1' }),
      )
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    expect(await client.api<{ tenantId: string }>('/demo/context')).toEqual({
      tenantId: 'tenant-a',
    })
    expect(new Headers(fetcher.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe(
      'Bearer access-a',
    )
  })

  it('api 失败时抛出带 code、httpStatus、userTip、traceId、errors 的 ApiError', async () => {
    signedIn()
    const errors = [{ field: 'amount', code: 'validation.positive', userTip: '金额必须大于 0' }]
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(
        envelope(
          {
            code: 'request.invalid',
            httpStatus: 400,
            errorMessage: '1 field(s) failed validation',
            userTip: '请检查填写的内容',
            traceId: 'trace-400',
            errors,
          },
          400,
        ),
      ),
    )
    const client = createTenantClient(sessionStorage)
    const error = await client.api('/demo/orders', { method: 'POST', body: '{}' }).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      code: 'request.invalid',
      httpStatus: 400,
      userTip: '请检查填写的内容',
      traceId: 'trace-400',
      errors,
      message: 'request.invalid',
    })
  })

  it('响应不是信封（如代理返回的 HTML）时得到通用错误，traceId 取自响应头', async () => {
    signedIn()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(
        new Response('<html>Bad Gateway</html>', {
          status: 502,
          headers: { 'Content-Type': 'text/html', 'X-Trace-Id': 'trace-502' },
        }),
      ),
    )
    const client = createTenantClient(sessionStorage)
    const error = await client.api('/demo/context').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ code: 'system.error', httpStatus: 502, traceId: 'trace-502' })
    expect((error as ApiError).userTip).toBeUndefined()
  })

  it('page 返回条目与分页信息', async () => {
    signedIn()
    const pageMeta = { number: 2, size: 20, totalElements: 41, totalPages: 3 }
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(
        envelope({
          code: 'ok',
          httpStatus: 200,
          data: [{ id: 'order-21' }],
          meta: { page: pageMeta },
          traceId: 't',
        }),
      ),
    )
    const client = createTenantClient(sessionStorage)
    expect(await client.page<{ id: string }>('/demo/orders?page=2')).toEqual({
      items: [{ id: 'order-21' }],
      page: pageMeta,
    })
  })

  it('page 响应缺少分页信息时得到通用错误', async () => {
    signedIn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(ok([])))
    const client = createTenantClient(sessionStorage)
    await expect(client.page('/demo/orders')).rejects.toMatchObject({ code: 'system.error' })
  })
})

describe('注册与验证码登录', () => {
  it('验证码登录与密码登录产出同样的会话', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(ok({ expiresIn: 300, resendIn: 60 }))
      .mockResolvedValueOnce(loginSettings([membershipA]))
      .mockImplementationOnce(authorizeRedirect)
      .mockResolvedValueOnce(tokens('access-code', 'refresh-code'))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    expect(await client.sendLoginCode('13800000000')).toEqual({ expiresIn: 300, resendIn: 60 })
    expect(fetcher.mock.calls[0]?.[0]).toBe('/iam/auth/login/send-code')
    const result = await client.loginWithCode('13800000000', '123456')
    expect(result).toMatchObject({ status: 'signedIn', session: { accessToken: 'access-code' } })
    expect(fetcher.mock.calls[1]?.[0]).toBe('/iam/auth/code-login')
    expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body)).toEqual({
      target: '13800000000',
      code: '123456',
    })
    expect(client.session()?.refreshToken).toBe('refresh-code')
  })

  it('没有 Membership 的验证码登录得到 noTenant，不发起授权', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(loginSettings([]))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    expect(await client.loginWithCode('new@example.com', '123456')).toEqual({ status: 'noTenant' })
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('注册提交验证码，冲突与超频以服务端错误码失败', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(fail('verification.tooFrequent', 429))
      .mockResolvedValueOnce(
        envelope({ code: 'ok', httpStatus: 201, data: { identityId: 'identity-new' } }, 201),
      )
      .mockResolvedValueOnce(fail('registration.accountTaken', 409))
    vi.stubGlobal('fetch', fetcher)
    const client = createTenantClient(sessionStorage)
    await expect(client.sendRegistrationCode('new@example.com')).rejects.toMatchObject({
      code: 'verification.tooFrequent',
    })
    expect(fetcher.mock.calls[0]?.[0]).toBe('/iam/auth/register/send-code')
    const input = {
      account: 'newcomer',
      password: 'Register-password-123!',
      target: 'new@example.com',
      code: '123456',
    }
    expect(await client.register(input)).toEqual({ identityId: 'identity-new' })
    expect(fetcher.mock.calls[1]?.[0]).toBe('/iam/auth/register')
    expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body)).toEqual(input)
    await expect(client.register(input)).rejects.toMatchObject({
      code: 'registration.accountTaken',
      httpStatus: 409,
    })
  })
})
