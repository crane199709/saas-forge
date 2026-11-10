/// <reference types="node" />
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { loginFlow } from './login-flow'
import { fakeFetch, mountShell } from './test-shell'

beforeEach(() => vi.stubGlobal('crypto', webcrypto))
enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  loginFlow.memberships = null
  loginFlow.logoutFailed = false
  vi.unstubAllGlobals()
})

function ok(data?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 'trace-ok' }))
}

function fail(code: string, status: number, userTip: string, traceId: string) {
  return new Response(
    JSON.stringify({ code, httpStatus: status, errorMessage: code, userTip, traceId }),
    { status },
  )
}

function authorizeRedirect(path: string) {
  const state = new URL(path, location.origin).searchParams.get('state')
  const response = new Response('')
  Object.defineProperty(response, 'url', {
    value: location.origin + '/?code=authorization-code&state=' + state,
  })
  return response
}

function loginSettings(memberships: object[]) {
  return ok({ clientId: 'tenant-app', redirectUri: location.origin + '/', memberships })
}

const memberships = [
  { membershipId: 'membership-a', tenantId: 'tenant-a', tenantName: '租户 A' },
  { membershipId: 'membership-b', tenantId: 'tenant-b', tenantName: '租户 B' },
]

async function submit(wrapper: Awaited<ReturnType<typeof mountShell>>['wrapper']) {
  await wrapper.get('input[name="account"]').setValue('multi')
  await wrapper.get('input[name="password"]').setValue('Demo-password-123!')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

it('账号登录后进入首页，展示下游返回的身份、租户与成员关系', async () => {
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/auth/login': loginSettings([memberships[0]!]),
      '/iam/oauth2/authorize': authorizeRedirect,
      '/iam/auth/exchange': ok({
        accessToken: 'a',
        refreshToken: 'r',
        tokenType: 'Bearer',
        expiresIn: 900,
        identityName: '演示身份',
        tenantName: '演示租户',
        membershipId: 'membership-a',
      }),
      '/iam/auth/memberships': ok([memberships[0]]),
      '/demo/context': ok({
        identityId: 'identity-1',
        tenantId: 't',
        membershipId: 'membership-a',
      }),
    }),
  )
  const { wrapper, router } = await mountShell('/login')
  await submit(wrapper)
  await vi.waitFor(() =>
    expect(wrapper.find('[data-testid="home-identity"]').text()).toBe('演示身份'),
  )
  expect(router.currentRoute.value.fullPath).toBe('/')
  expect(wrapper.find('[data-testid="home-tenant"]').text()).toBe('演示租户')
  expect(wrapper.text()).toContain('membership-a')
  expect(wrapper.find('input[name="password"]').exists()).toBe(false)
})

it('登录失败时展示服务端 userTip，并可复制 traceId', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/auth/login': fail(
        'auth.failed',
        401,
        '账号或密码错误，或登录会话已失效',
        'trace-login-401',
      ),
    }),
  )
  const { wrapper } = await mountShell('/login')
  await submit(wrapper)
  const alert = wrapper.get('[role="alert"]')
  expect(alert.text()).toContain('账号或密码错误，或登录会话已失效')
  expect(alert.text()).toContain('trace-login-401')
  await wrapper.get('[data-testid="copy-trace-id"]').trigger('click')
  expect(writeText).toHaveBeenCalledWith('trace-login-401')
})

it('网络故障等没有 userTip 的错误显示通用提示', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
  const { wrapper } = await mountShell('/login')
  await submit(wrapper)
  expect(wrapper.get('[role="alert"]').text()).toContain('请求失败，请稍后重试')
  expect(wrapper.find('[data-testid="copy-trace-id"]').exists()).toBe(false)
})

it('从用户菜单退出后回到登录页；IAM 退出失败时提示并可重试', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', identityName: '演示身份' }),
  )
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/auth/memberships': ok([]),
      '/demo/context': ok({}),
      '/iam/auth/logout': [fail('system.error', 500, '系统繁忙', 'trace-500'), ok()],
    }),
  )
  const { wrapper, router } = await mountShell('/')
  expect(wrapper.get('[data-testid="user-menu"]').text()).toBe('演示身份')
  // 下拉菜单渲染在 body 下
  await wrapper.get('[data-testid="user-menu"]').trigger('click')
  await flushPromises()
  document.querySelector<HTMLElement>('[data-testid="user-logout"]')!.click()
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
  expect(sessionStorage.getItem('forge.tenant.session')).toBeNull()
  expect(wrapper.text()).toContain('浏览器仍可能保持登录')
  const retry = wrapper.findAll('button').find((button) => button.text() === '重试退出')!
  await retry.trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('浏览器仍可能保持登录')
})

it('多个 Membership 时进入选择租户页，选择后进入该租户', async () => {
  const fetcher = vi.fn(
    fakeFetch({
      '/iam/auth/login': loginSettings(memberships),
      '/iam/oauth2/authorize': authorizeRedirect,
      '/iam/auth/exchange': ok({
        accessToken: 'a',
        refreshToken: 'r',
        identityName: '多成员身份',
        tenantId: 'tenant-b',
        tenantName: '租户 B',
        membershipId: 'membership-b',
      }),
      '/iam/auth/memberships': ok(memberships),
      '/demo/context': ok({ identityId: 'i', tenantId: 'tenant-b', membershipId: 'membership-b' }),
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, router } = await mountShell('/login')
  await submit(wrapper)
  // 页面懒加载，导航完成晚于 flushPromises。
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/select-tenant'))
  expect(wrapper.text()).toContain('选择租户')
  expect(fetcher).toHaveBeenCalledTimes(1)
  await wrapper.get('[data-testid="tenant-option-membership-b"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('[data-testid="home-tenant"]').text()).toBe('租户 B'))
  const exchange = fetcher.mock.calls.find(([path]) => path === '/iam/auth/exchange')!
  expect(
    JSON.parse((exchange as unknown as [string, RequestInit])[1].body as string),
  ).toMatchObject({ membershipId: 'membership-b' })
})

it('选择的租户被拒绝时停留在选择页并展示 userTip', async () => {
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/auth/login': loginSettings(memberships),
      '/iam/oauth2/authorize': authorizeRedirect,
      '/iam/auth/exchange': fail(
        'auth.tenantAccessDenied',
        403,
        '无法进入该租户',
        'trace-select-403',
      ),
    }),
  )
  const { wrapper, router } = await mountShell('/login')
  await submit(wrapper)
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/select-tenant'))
  await wrapper.get('[data-testid="tenant-option-membership-b"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('[role="alert"]').exists()).toBe(true))
  expect(wrapper.get('[role="alert"]').text()).toContain('无法进入该租户')
  expect(wrapper.get('[role="alert"]').text()).toContain('trace-select-403')
  expect(router.currentRoute.value.path).toBe('/select-tenant')
})

it('选择租户页刷新后可选租户丢失，回到登录页', async () => {
  const { router } = await mountShell('/select-tenant?redirect=/members')
  expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/members')
})

it('没有有效 Membership 时进入无租户页，重新登录先退出 IAM 登录会话', async () => {
  const fetcher = vi.fn(
    fakeFetch({ '/iam/auth/login': loginSettings([]), '/iam/auth/logout': ok() }),
  )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, router } = await mountShell('/login')
  await submit(wrapper)
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/no-tenant'))
  expect(wrapper.text()).toContain('暂无可进入的租户')
  expect(wrapper.find('form').exists()).toBe(false)
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(fetcher.mock.calls.at(-1)?.[0]).toBe('/iam/auth/logout')
  expect(router.currentRoute.value.path).toBe('/login')
})

const signedInTokens = ok({
  accessToken: 'a',
  refreshToken: 'r',
  tokenType: 'Bearer',
  expiresIn: 900,
  identityName: '安全身份',
  tenantName: '租户 A',
  membershipId: 'membership-a',
})

it('已绑定 Authenticator 时进入 MFA 步骤，可改用 Recovery Code 后进入租户', async () => {
  const fetcher = vi.fn(
    fakeFetch({
      '/iam/auth/login': ok({ nextStep: 'mfa' }),
      '/iam/auth/login/mfa': [
        fail('mfa.codeInvalid', 400, '动态码错误或已使用', 'trace-mfa'),
        loginSettings([memberships[0]!]),
      ],
      '/iam/oauth2/authorize': authorizeRedirect,
      '/iam/auth/exchange': signedInTokens,
      '/iam/auth/memberships': ok([memberships[0]]),
      '/demo/context': ok({}),
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, router } = await mountShell('/login')
  await submit(wrapper)
  expect(wrapper.find('input[name="password"]').exists()).toBe(false)
  await wrapper.get('input[name="mfa-code"]').setValue('123456')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('动态码错误或已使用')

  await wrapper.get('[data-testid="use-recovery-code"]').trigger('click')
  await wrapper.get('input[name="recovery-code"]').setValue('AAAA-BBBB-CCCC-DDDD')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
  const mfaCalls = fetcher.mock.calls.filter(([path]) => path === '/iam/auth/login/mfa')
  expect(JSON.parse((mfaCalls[1] as unknown as [string, RequestInit])[1].body as string)).toEqual({
    recoveryCode: 'AAAA-BBBB-CCCC-DDDD',
  })
})

it('待 MFA 超时后回到账号密码表单', async () => {
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/auth/login': ok({ nextStep: 'mfa' }),
      '/iam/auth/login/mfa': fail('auth.mfaExpired', 401, '验证已超时，请重新登录', 'trace-exp'),
    }),
  )
  const { wrapper } = await mountShell('/login')
  await submit(wrapper)
  await wrapper.get('input[name="mfa-code"]').setValue('123456')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('验证已超时，请重新登录')
  expect(wrapper.find('input[name="password"]').exists()).toBe(true)
})

it('租户要求 MFA 时当场绑定：扫码、输入首个动态码、保存 Recovery Code 后重新进入', async () => {
  const fetcher = vi.fn(
    fakeFetch({
      '/iam/auth/login': loginSettings([memberships[0]!]),
      '/iam/oauth2/authorize': authorizeRedirect,
      '/iam/auth/exchange': [
        fail('auth.mfaRequired', 403, '需要先完成多因素认证', 'trace-mfa-required'),
        signedInTokens,
      ],
      '/iam/auth/mfa/bind/start': ok({
        otpauthUri: 'otpauth://totp/SaaS%20Forge:multi?secret=JBSWY3DPEHPK3PXP&issuer=SaaS%20Forge',
      }),
      '/iam/auth/mfa/bind/confirm': ok({
        recoveryCodes: ['AAAA-BBBB-CCCC-DDDD', 'EEEE-FFFF-GGGG-HHHH'],
      }),
      '/iam/auth/memberships': ok([memberships[0]]),
      '/demo/context': ok({}),
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, router } = await mountShell('/login')
  await submit(wrapper)
  await vi.waitFor(() => expect(wrapper.find('[data-testid="mfa-qr"]').exists()).toBe(true))
  expect(wrapper.text()).toContain('JBSWY3DPEHPK3PXP')
  await wrapper.get('input[name="bind-code"]').setValue('654321')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[data-testid="recovery-codes"]').text()).toContain('EEEE-FFFF-GGGG-HHHH')
  expect(router.currentRoute.value.path).toBe('/login')

  await wrapper.get('[data-testid="recovery-codes-done"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
  expect(fetcher.mock.calls.filter(([path]) => path === '/iam/auth/exchange')).toHaveLength(2)
})

it('账号被临时锁定时提示剩余等待时间', async () => {
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/auth/login': new Response(
        JSON.stringify({
          code: 'auth.locked',
          httpStatus: 429,
          userTip: '已锁定',
          traceId: 'trace-locked',
        }),
        { status: 429, headers: { 'Retry-After': '540' } },
      ),
    }),
  )
  const { wrapper } = await mountShell('/login')
  await submit(wrapper)
  const alert = wrapper.get('[role="alert"]')
  expect(alert.text()).toContain('登录失败次数过多，已临时锁定，请 9 分钟后再试')
  expect(alert.text()).toContain('trace-locked')
})

it('选择的租户要求 MFA 时在选择页当场绑定，保存 Recovery Code 后进入该租户', async () => {
  const fetcher = vi.fn(
    fakeFetch({
      '/iam/auth/login': loginSettings(memberships),
      '/iam/oauth2/authorize': authorizeRedirect,
      '/iam/auth/exchange': [
        fail('auth.mfaRequired', 403, '需要先完成多因素认证', 'trace-mfa-required'),
        signedInTokens,
      ],
      '/iam/auth/mfa/bind/start': ok({ otpauthUri: 'otpauth://totp/x?secret=ABCDEF' }),
      '/iam/auth/mfa/bind/confirm': ok({ recoveryCodes: ['AAAA-BBBB-CCCC-DDDD'] }),
      '/iam/auth/memberships': ok(memberships),
      '/demo/context': ok({}),
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, router } = await mountShell('/login')
  await submit(wrapper)
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/select-tenant'))
  await wrapper.get('[data-testid="tenant-option-membership-b"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('[data-testid="mfa-qr"]').exists()).toBe(true))
  await wrapper.get('input[name="bind-code"]').setValue('654321')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  await wrapper.get('[data-testid="recovery-codes-done"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/'))
  const exchanges = fetcher.mock.calls.filter(([path]) => path === '/iam/auth/exchange')
  expect(
    JSON.parse((exchanges[1] as unknown as [string, RequestInit])[1].body as string),
  ).toMatchObject({ membershipId: 'membership-b' })
})
