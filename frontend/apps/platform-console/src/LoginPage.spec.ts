/// <reference types="node" />
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { loginFlow } from './login-flow'
import { mountConsole } from './test-console'

beforeEach(() => vi.stubGlobal('crypto', webcrypto))
enableAutoUnmount(afterEach)
afterEach(() => {
  localStorage.clear()
  loginFlow.logoutFailed = false
  vi.unstubAllGlobals()
})

function ok(data?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 'trace-ok' }))
}

function authorizeRedirect(path: string) {
  const state = new URL(path, location.origin).searchParams.get('state')
  const response = new Response('')
  Object.defineProperty(response, 'url', {
    value: location.origin + '/?code=authorization-code&state=' + state,
  })
  return Promise.resolve(response)
}

function fail(code: string, status: number, userTip: string, traceId = 'trace') {
  return new Response(
    JSON.stringify({ code, httpStatus: status, errorMessage: code, userTip, traceId }),
    { status },
  )
}

function tokens() {
  return ok({
    accessToken: 'platform-access',
    refreshToken: 'platform-refresh',
    tokenType: 'Bearer',
    expiresIn: 900,
    operatorName: '演示运营',
  })
}

// 第二步通过后的登录响应，形态与 nextStep=authorize 的第一步相同；当场绑定时另带恢复码。
function signedInStep(recoveryCodes?: string[], status = 200) {
  return new Response(
    JSON.stringify({
      code: 'ok',
      httpStatus: status,
      data: {
        nextStep: 'authorize',
        clientId: 'platform-console',
        redirectUri: location.origin + '/',
        recoveryCodes,
      },
      traceId: 'trace-ok',
    }),
    { status },
  )
}

async function submit(wrapper: Awaited<ReturnType<typeof mountConsole>>['wrapper']) {
  await wrapper.get('input[name="account"]').setValue('operator')
  await wrapper.get('input[name="password"]').setValue('Operator-password-123!')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

it('运营账号经 Platform Realm 登录，凭证存于 Platform Realm 的存储键', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok({ clientId: 'platform-console', redirectUri: location.origin + '/' }))
    .mockImplementationOnce(authorizeRedirect)
    .mockResolvedValueOnce(
      ok({
        accessToken: 'platform-access',
        refreshToken: 'platform-refresh',
        tokenType: 'Bearer',
        expiresIn: 900,
        operatorName: '演示运营',
      }),
    )
    .mockResolvedValue(ok({ roles: [], permissions: [] }))
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, router } = await mountConsole('/login')
  await submit(wrapper)
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
  expect(wrapper.get('[data-testid="user-menu"]').text()).toBe('演示运营')

  expect(fetcher.mock.calls[0]![0]).toBe('/iam/platform/auth/login')
  const authorize = new URL(fetcher.mock.calls[1]![0] as string, location.origin)
  expect(authorize.pathname).toBe('/iam/oauth2/authorize')
  expect(authorize.searchParams.get('client_id')).toBe('platform-console')
  expect(authorize.searchParams.get('scope')).toBe('platform')
  expect(fetcher.mock.calls[2]![0]).toBe('/iam/platform/auth/exchange')
  expect(fetcher.mock.calls[3]![0]).toBe(location.origin + '/api/iam/platform/access')
  const headers = new Headers((fetcher.mock.calls[3]![1] as RequestInit).headers)
  expect(headers.get('Authorization')).toBe('Bearer platform-access')
  // 两个 Realm 的凭证分开存放，平台登录不会写入 Tenant Realm 的会话。
  expect(localStorage.getItem('forge.platform.session')).toContain('platform-access')
  expect(localStorage.getItem('forge.tenant.session')).toBeNull()
})

it('登录失败时展示服务端提示与 traceId，不发起授权', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(fail('auth.failed', 401, '账号或密码错误，或会话已失效', 'trace-failed'))
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, router } = await mountConsole('/login')
  await submit(wrapper)
  await vi.waitFor(() => expect(wrapper.text()).toContain('账号或密码错误'))
  expect(wrapper.text()).toContain('trace-failed')
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(router.currentRoute.value.path).toBe('/login')
})

it('账号被锁定时按 Retry-After 展示剩余等待时间', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: 'auth.locked',
          httpStatus: 429,
          errorMessage: 'locked',
          traceId: 'trace-locked',
        }),
        { status: 429, headers: { 'Retry-After': '125' } },
      ),
    ),
  )
  const { wrapper } = await mountConsole('/login')
  await submit(wrapper)
  await vi.waitFor(() => expect(wrapper.text()).toContain('3 分钟后再试'))
})

describe('已绑定验证器的两步登录', () => {
  function mfaBackend(...steps: Response[]) {
    const fetcher = vi.fn((input: string, _init?: RequestInit) => {
      const path = new URL(input, location.origin).pathname
      if (path === '/iam/platform/auth/login') return Promise.resolve(ok({ nextStep: 'mfa' }))
      if (path === '/iam/platform/auth/login/mfa') return Promise.resolve(steps.shift()!)
      if (path === '/iam/oauth2/authorize') return authorizeRedirect(input)
      if (path === '/iam/platform/auth/exchange') return Promise.resolve(tokens())
      return Promise.resolve(ok({ roles: [], permissions: [] }))
    })
    vi.stubGlobal('fetch', fetcher)
    return fetcher
  }

  function bodies(fetcher: ReturnType<typeof mfaBackend>, path: string) {
    return fetcher.mock.calls
      .filter(([input]) => input === path)
      .map(([, init]) => JSON.parse(init!.body as string))
  }

  it('密码通过后不发起授权，提交动态码后才进入', async () => {
    const fetcher = mfaBackend(signedInStep())
    const { wrapper, router } = await mountConsole('/login')
    await submit(wrapper)
    await vi.waitFor(() => expect(wrapper.find('input[name="mfa-code"]').exists()).toBe(true))
    expect(fetcher).toHaveBeenCalledTimes(1)

    await wrapper.get('input[name="mfa-code"]').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
    expect(bodies(fetcher, '/iam/platform/auth/login/mfa')).toEqual([{ code: '123456' }])
    expect(localStorage.getItem('forge.platform.session')).toContain('platform-access')
  })

  it('可改用恢复码；错误时留在此步可重试', async () => {
    const fetcher = mfaBackend(
      fail('mfa.recoveryCodeInvalid', 400, '恢复码错误或已使用'),
      signedInStep(),
    )
    const { wrapper, router } = await mountConsole('/login')
    await submit(wrapper)
    await vi.waitFor(() => expect(wrapper.find('input[name="mfa-code"]').exists()).toBe(true))
    await wrapper.get('[data-testid="use-recovery-code"]').trigger('click')

    await wrapper.get('input[name="recovery-code"]').setValue('AAAA-BBBB-CCCC-DDDD')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(wrapper.text()).toContain('恢复码错误或已使用'))
    expect(wrapper.find('input[name="recovery-code"]').exists()).toBe(true)

    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
    expect(bodies(fetcher, '/iam/platform/auth/login/mfa')[1]).toEqual({
      recoveryCode: 'AAAA-BBBB-CCCC-DDDD',
    })
  })

  it('待 MFA 已过期时回到账号密码步骤并提示', async () => {
    mfaBackend(fail('auth.mfaExpired', 401, '验证已超时，请重新登录'))
    const { wrapper } = await mountConsole('/login')
    await submit(wrapper)
    await vi.waitFor(() => expect(wrapper.find('input[name="mfa-code"]').exists()).toBe(true))
    await wrapper.get('input[name="mfa-code"]').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(wrapper.text()).toContain('验证已超时'))
    expect(wrapper.find('input[name="account"]').exists()).toBe(true)
  })
})

describe('mfa-required 时的强制绑定', () => {
  function bindBackend(...confirms: Response[]) {
    const fetcher = vi.fn((input: string) => {
      const path = new URL(input, location.origin).pathname
      if (path === '/iam/platform/auth/login') return Promise.resolve(ok({ nextStep: 'bind' }))
      if (path === '/iam/platform/auth/login/bind/start') {
        return Promise.resolve(ok({ otpauthUri: 'otpauth://totp/x?secret=ABCDEF' }))
      }
      if (path === '/iam/platform/auth/login/bind/confirm')
        return Promise.resolve(confirms.shift()!)
      if (path === '/iam/oauth2/authorize') return authorizeRedirect(input)
      if (path === '/iam/platform/auth/exchange') return Promise.resolve(tokens())
      return Promise.resolve(ok({ roles: [], permissions: [] }))
    })
    vi.stubGlobal('fetch', fetcher)
    return fetcher
  }

  const paths = (fetcher: ReturnType<typeof bindBackend>) =>
    fetcher.mock.calls.map(([input]) => new URL(input, location.origin).pathname)

  async function reachBinding(wrapper: Awaited<ReturnType<typeof mountConsole>>['wrapper']) {
    await submit(wrapper)
    await vi.waitFor(() => expect(wrapper.find('[data-testid="mfa-qr"]').exists()).toBe(true))
  }

  it('当场绑定后先展示恢复码，保存后才签发 Token 进入', async () => {
    const fetcher = bindBackend(signedInStep(['AAAA-BBBB-CCCC-DDDD'], 201))
    const { wrapper, router } = await mountConsole('/login')
    await reachBinding(wrapper)
    expect(wrapper.get('[data-testid="mfa-secret"]').text()).toBe('ABCDEF')
    expect(paths(fetcher)).not.toContain('/iam/oauth2/authorize')

    await wrapper.get('input[name="bind-code"]').setValue('654321')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() =>
      expect(wrapper.get('[data-testid="recovery-codes"]').text()).toContain('AAAA-BBBB-CCCC-DDDD'),
    )
    // 恢复码只在这个响应中出现，必须在跳转前留给用户保存。
    expect(paths(fetcher)).not.toContain('/iam/oauth2/authorize')
    expect(router.currentRoute.value.path).toBe('/login')

    await wrapper.get('[data-testid="recovery-codes-done"]').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
    expect(localStorage.getItem('forge.platform.session')).toContain('platform-access')
  })

  it('动态码错误时留在绑定步骤可重试', async () => {
    bindBackend(
      fail('mfa.codeInvalid', 400, '动态码错误或已使用'),
      signedInStep(['AAAA-BBBB-CCCC-DDDD'], 201),
    )
    const { wrapper } = await mountConsole('/login')
    await reachBinding(wrapper)
    await wrapper.get('input[name="bind-code"]').setValue('000000')
    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() => expect(wrapper.text()).toContain('动态码错误或已使用'))

    await wrapper.get('form').trigger('submit')
    await vi.waitFor(() =>
      expect(wrapper.find('[data-testid="recovery-codes"]').exists()).toBe(true),
    )
    expect(wrapper.text()).not.toContain('动态码错误或已使用')
  })
})

it('IAM 退出失败时登录页提示浏览器可能仍保持登录', async () => {
  loginFlow.logoutFailed = true
  const { wrapper } = await mountConsole('/login')
  expect(wrapper.get('[role="alert"]').text()).toContain('IAM 退出失败')
})
