import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fakeFetch, mountShell } from '../test-shell'

enableAutoUnmount(afterEach)
beforeEach(() => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', identityName: '管理员' }),
  )
})
afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function ok(data?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 'trace-ok' }))
}

it('持有 iam:security:manage 时菜单显示安全设置，开关保存 MFA Requirement', async () => {
  const fetcher = vi.fn(
    fakeFetch({
      '/iam/auth/memberships': ok([]),
      '/iam/security-settings': [ok({ mfaRequired: false }), ok({ mfaRequired: true })],
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper } = await mountShell('/security-settings', {
    access: () => Promise.resolve({ permissions: ['iam:security:manage'] }),
  })
  expect(wrapper.text()).toContain('安全设置')
  const toggle = wrapper.get('[data-testid="mfa-required"]')
  expect(toggle.get('[role="switch"]').attributes('aria-checked')).toBe('false')
  await toggle.trigger('click')
  await flushPromises()
  const calls = fetcher.mock.calls as unknown as [string, RequestInit | undefined][]
  const put = calls.find(([, init]) => init?.method === 'PUT')!
  expect(new URL(put[0]).pathname.replace(/^\/api(?=\/)/, '')).toBe('/iam/security-settings')
  expect(JSON.parse(put[1]!.body as string)).toEqual({ mfaRequired: true })
  expect(
    wrapper.get('[data-testid="mfa-required"] [role="switch"]').attributes('aria-checked'),
  ).toBe('true')
})

it('没有 iam:security:manage 时菜单不显示安全设置，直接访问进入 403', async () => {
  vi.stubGlobal('fetch', fakeFetch({ '/iam/auth/memberships': ok([]) }))
  const { wrapper, router } = await mountShell('/')
  expect(wrapper.text()).not.toContain('安全设置')
  await router.push('/security-settings')
  expect(router.currentRoute.value.path).toBe('/403')
})

it('显示可信当前 IP 与生效提示，保存规则并定位服务端条目错误', async () => {
  const fetcher = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    const path = new URL(url, location.origin).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/iam/security-settings') return Promise.resolve(ok({ mfaRequired: false }))
    if (path === '/iam/security-settings/ip-allowlist') {
      if (init?.method === 'PUT')
        return Promise.resolve(
          new Response(
            JSON.stringify({
              code: 'validation.failed',
              httpStatus: 400,
              userTip: '规则无效',
              traceId: 'bad-rule',
              errors: [{ field: 'rules[1]', code: 'invalid', userTip: '请输入 IP 地址或 CIDR' }],
            }),
            { status: 400 },
          ),
        )
      return Promise.resolve(ok({ rules: ['192.0.2.1', 'bad'], currentIp: '192.0.2.1' }))
    }
    return Promise.resolve(ok([]))
  })
  vi.stubGlobal('fetch', fetcher)
  const { wrapper } = await mountShell('/security-settings', {
    access: async () => ({ permissions: ['iam:security:manage'] }),
  })
  expect(wrapper.text()).toContain('192.0.2.1')
  expect(wrapper.text()).toContain('每次请求')
  await wrapper.get('.ip-section form').trigger('submit')
  await flushPromises()
  await vi.waitFor(() =>
    expect(wrapper.get('[data-testid="ip-rule-1"]').text()).toContain('请输入 IP 地址或 CIDR'),
  )
  const put = fetcher.mock.calls.find(
    ([url, init]) =>
      new URL(url, location.origin).pathname.replace(/^\/api(?=\/)/, '').endsWith('ip-allowlist') &&
      init?.method === 'PUT',
  )!
  expect(JSON.parse(put[1]!.body as string)).toEqual({ rules: ['192.0.2.1', 'bad'] })
})

it.each([undefined, '', ' \t '])('字段提示为 %j 时显示通用提示，不暴露错误码', async (userTip) => {
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    const path = new URL(url, location.origin).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/iam/security-settings') return Promise.resolve(ok({ mfaRequired: false }))
    if (path === '/iam/security-settings/ip-allowlist') {
      if (init?.method === 'PUT')
        return Promise.resolve(
          new Response(
            JSON.stringify({
              code: 'validation.failed',
              httpStatus: 400,
              userTip: '规则无效',
              errors: [{ field: 'rules[0]', code: 'tenant.invalidMachineCode', userTip }],
            }),
            { status: 400 },
          ),
        )
      return Promise.resolve(ok({ rules: ['bad'], currentIp: '192.0.2.1' }))
    }
    return Promise.resolve(ok([]))
  })
  const { wrapper } = await mountShell('/security-settings', {
    access: async () => ({ permissions: ['iam:security:manage'] }),
  })
  await wrapper.get('.ip-section form').trigger('submit')
  await flushPromises()
  await vi.waitFor(() =>
    expect(wrapper.get('[data-testid="ip-rule-0"]').text()).toContain('请求失败，请稍后重试'),
  )
  expect(wrapper.text()).not.toContain('tenant.invalidMachineCode')
})

it('IP 规则保存失败后重试成功，清除错误文案与追踪 ID', async () => {
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/auth/memberships': ok([]),
      '/iam/security-settings': ok({ mfaRequired: false }),
      '/iam/security-settings/ip-allowlist': [
        ok({ rules: ['192.0.2.1'], currentIp: '192.0.2.1' }),
        new Response(
          JSON.stringify({
            code: 'tenant.currentIpNotAllowed',
            httpStatus: 400,
            userTip: '规则必须包含当前 IP',
            traceId: 'failed-ip-save',
          }),
          { status: 400 },
        ),
        ok({ rules: ['192.0.2.1'], currentIp: '192.0.2.1' }),
      ],
    }),
  )
  const { wrapper } = await mountShell('/security-settings', {
    access: async () => ({ permissions: ['iam:security:manage'] }),
  })
  await wrapper.get('.ip-section form').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('规则必须包含当前 IP')
  expect(wrapper.text()).toContain('failed-ip-save')

  await wrapper.get('.ip-section form').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('租户 IP 允许列表已保存')
  expect(wrapper.text()).not.toContain('规则必须包含当前 IP')
  expect(wrapper.text()).not.toContain('failed-ip-save')
})

it('修正出错的 IP 条目后，清除该条目与本次提交的旧错误', async () => {
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    const path = new URL(url, location.origin).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/iam/security-settings') return Promise.resolve(ok({ mfaRequired: false }))
    if (path === '/iam/security-settings/ip-allowlist') {
      if (init?.method === 'PUT')
        return Promise.resolve(
          new Response(
            JSON.stringify({
              code: 'validation.failed',
              httpStatus: 400,
              userTip: '规则无效',
              traceId: 'failed-rule',
              errors: [{ field: 'rules[0]', code: 'invalid', userTip: '请输入 IP 地址或 CIDR' }],
            }),
            { status: 400 },
          ),
        )
      return Promise.resolve(ok({ rules: ['bad'], currentIp: '192.0.2.1' }))
    }
    return Promise.resolve(ok([]))
  })
  const { wrapper } = await mountShell('/security-settings', {
    access: async () => ({ permissions: ['iam:security:manage'] }),
  })
  await wrapper.get('.ip-section form').trigger('submit')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.text()).toContain('请输入 IP 地址或 CIDR'))

  await wrapper.get('#ip-rule-0').setValue('192.0.2.1')
  expect(wrapper.text()).not.toContain('请输入 IP 地址或 CIDR')
  expect(wrapper.text()).not.toContain('规则无效')
  expect(wrapper.text()).not.toContain('failed-rule')
})
