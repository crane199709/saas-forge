/// <reference types="node" />
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { createTenantClient, tenantClientKey } from '@forge/sdk'
beforeEach(() => vi.stubGlobal('crypto', webcrypto))
import LoginPanel from './LoginPanel.vue'
import { i18n } from './i18n'

afterEach(() => {
  sessionStorage.clear()
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
  return Promise.resolve(response)
}

function loginSettings(memberships: object[]) {
  return ok({ clientId: 'tenant-app', redirectUri: location.origin + '/', memberships })
}

it('账号登录后展示下游返回的身份、租户与成员关系', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(
        loginSettings([
          { membershipId: 'membership-1', tenantId: 'tenant-1', tenantName: '演示租户' },
        ]),
      )
      .mockImplementationOnce(authorizeRedirect)
      .mockResolvedValueOnce(
        ok({
          accessToken: 'a',
          refreshToken: 'r',
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
      ),
  )
  const wrapper = mountPanel()
  await wrapper.get('input[name="account"]').setValue('demo')
  await wrapper.get('input[name="password"]').setValue('Demo-password-123!')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.text()).toContain('演示身份'))
  expect(wrapper.text()).toContain('演示租户')
  expect(wrapper.text()).toContain('membership-1')
  expect(wrapper.find('input[name="password"]').exists()).toBe(false)
})

it('登录失败时展示服务端 userTip，并可复制 traceId', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(
        fail('auth.failed', 401, '账号或密码错误，或登录会话已失效', 'trace-login-401'),
      ),
  )
  const wrapper = mountPanel()
  await submit(wrapper)
  const alert = wrapper.get('[role="alert"]')
  expect(alert.text()).toContain('账号或密码错误，或登录会话已失效')
  expect(alert.text()).toContain('trace-login-401')
  await wrapper.get('[data-testid="copy-trace-id"]').trigger('click')
  expect(writeText).toHaveBeenCalledWith('trace-login-401')
})

it('网络故障等没有 userTip 的错误显示通用提示', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValueOnce(new TypeError('Failed to fetch')))
  const wrapper = mountPanel()
  await submit(wrapper)
  expect(wrapper.get('[role="alert"]').text()).toContain('请求失败，请稍后重试')
  expect(wrapper.find('[data-testid="copy-trace-id"]').exists()).toBe(false)
})

it('退出失败后，较晚返回的初始化请求不能覆盖退出警告和重试入口', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', identityName: '演示身份' }),
  )
  let finishContext!: (response: Response) => void
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finishContext = resolve
          }),
      )
      .mockResolvedValueOnce(fail('system.error', 500, '系统繁忙', 'trace-500')),
  )
  const wrapper = mountPanel()
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('浏览器仍可能保持登录')
  finishContext(ok({}))
  await flushPromises()
  expect(wrapper.text()).toContain('浏览器仍可能保持登录')
  expect(wrapper.text()).toContain('重试退出')
  wrapper.unmount()
})

function mountPanel() {
  return mount(LoginPanel, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
  })
}

async function submit(wrapper: ReturnType<typeof mountPanel>) {
  await wrapper.get('input[name="account"]').setValue('multi')
  await wrapper.get('input[name="password"]').setValue('Demo-password-123!')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

it('多个 Membership 时先展示租户选择页，选择后进入该租户', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      loginSettings([
        { membershipId: 'membership-a', tenantId: 'tenant-a', tenantName: '租户 A' },
        { membershipId: 'membership-b', tenantId: 'tenant-b', tenantName: '租户 B' },
      ]),
    )
    .mockImplementationOnce(authorizeRedirect)
    .mockResolvedValueOnce(
      ok({
        accessToken: 'a',
        refreshToken: 'r',
        identityName: '多成员身份',
        tenantId: 'tenant-b',
        tenantName: '租户 B',
        membershipId: 'membership-b',
      }),
    )
    .mockResolvedValueOnce(
      ok({ identityId: 'i', tenantId: 'tenant-b', membershipId: 'membership-b' }),
    )
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await submit(wrapper)
  expect(wrapper.text()).toContain('选择租户')
  expect(fetcher).toHaveBeenCalledTimes(1)
  await wrapper.get('[data-testid="tenant-option-membership-b"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.text()).toContain('当前租户：租户 B'))
  expect(JSON.parse(fetcher.mock.calls[2]?.[1]?.body).membershipId).toBe('membership-b')
})

it('选择的租户被拒绝时停留在选择页并展示 userTip', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(
        loginSettings([
          { membershipId: 'membership-a', tenantId: 'tenant-a', tenantName: '租户 A' },
          { membershipId: 'membership-b', tenantId: 'tenant-b', tenantName: '租户 B' },
        ]),
      )
      .mockImplementationOnce(authorizeRedirect)
      .mockResolvedValueOnce(
        fail('auth.tenantAccessDenied', 403, '无法进入该租户', 'trace-select-403'),
      ),
  )
  const wrapper = mountPanel()
  await submit(wrapper)
  await wrapper.get('[data-testid="tenant-option-membership-b"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('[role="alert"]').exists()).toBe(true))
  expect(wrapper.get('[role="alert"]').text()).toContain('无法进入该租户')
  expect(wrapper.get('[role="alert"]').text()).toContain('trace-select-403')
  expect(wrapper.text()).toContain('选择租户')
})

it('没有有效 Membership 时展示无租户页', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(loginSettings([])))
  const wrapper = mountPanel()
  await submit(wrapper)
  expect(wrapper.text()).toContain('暂无可进入的租户')
  expect(wrapper.find('form').exists()).toBe(false)
})

it('会话被清除（如刷新失败）后回到登录表单', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({
      accessToken: 'a',
      refreshToken: 'r',
      identityName: '演示身份',
      tenantName: '演示租户',
    }),
  )
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation(() =>
      Promise.resolve(
        ok({
          identityId: 'identity-1',
          tenantId: 'tenant-1',
          membershipId: 'membership-1',
          version: 1,
        }),
      ),
    ),
  )
  const client = createTenantClient(sessionStorage)
  const wrapper = mount(LoginPanel, {
    global: { plugins: [i18n, ElementPlus], provide: { [tenantClientKey]: client } },
  })
  await vi.waitFor(() => expect(wrapper.text()).toContain('membership-1'))
  client.clear()
  await flushPromises()
  expect(wrapper.find('input[name="password"]').exists()).toBe(true)
  expect(wrapper.text()).not.toContain('演示身份')
})
