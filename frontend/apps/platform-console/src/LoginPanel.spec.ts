/// <reference types="node" />
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { createPlatformClient, platformClientKey } from '@forge/sdk'
import LoginPanel from './LoginPanel.vue'
import { i18n } from './i18n'

beforeEach(() => vi.stubGlobal('crypto', webcrypto))
afterEach(() => {
  sessionStorage.clear()
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

function mountPanel() {
  return mount(LoginPanel, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [platformClientKey as symbol]: createPlatformClient(sessionStorage) },
    },
  })
}

async function submit(wrapper: ReturnType<typeof mountPanel>) {
  await wrapper.get('input[name="account"]').setValue('operator')
  await wrapper.get('input[name="password"]').setValue('Operator-password-123!')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

it('运营账号经 Platform Realm 登录，展示平台服务验签后的当前 Operator', async () => {
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
    .mockResolvedValueOnce(ok({ operatorId: 'operator-1' }))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await submit(wrapper)
  await vi.waitFor(() => expect(wrapper.text()).toContain('operator-1'))
  expect(wrapper.text()).toContain('演示运营')

  expect(fetcher.mock.calls[0]![0]).toBe('/iam/platform/auth/login')
  const authorize = new URL(fetcher.mock.calls[1]![0] as string, location.origin)
  expect(authorize.pathname).toBe('/iam/oauth2/authorize')
  expect(authorize.searchParams.get('client_id')).toBe('platform-console')
  expect(authorize.searchParams.get('scope')).toBe('platform')
  expect(fetcher.mock.calls[2]![0]).toBe('/iam/platform/auth/exchange')
  expect(fetcher.mock.calls[3]![0]).toBe(location.origin + '/platform/operators/current')
  const headers = new Headers((fetcher.mock.calls[3]![1] as RequestInit).headers)
  expect(headers.get('Authorization')).toBe('Bearer platform-access')
  // 两个 Realm 的凭证分开存放，平台登录不会写入 Tenant Realm 的会话。
  expect(sessionStorage.getItem('forge.platform.session')).toContain('platform-access')
  expect(sessionStorage.getItem('forge.tenant.session')).toBeNull()
})

it('登录失败时展示服务端提示与 traceId，不发起授权', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(
    new Response(
      JSON.stringify({
        code: 'auth.mfaRequired',
        httpStatus: 403,
        errorMessage: 'Operator MFA is required but not supported yet',
        userTip: '平台已要求运营账号使用多因素认证，当前版本暂不支持，无法登录',
        traceId: 'trace-mfa',
      }),
      { status: 403 },
    ),
  )
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await submit(wrapper)
  await vi.waitFor(() => expect(wrapper.text()).toContain('多因素认证'))
  expect(wrapper.text()).toContain('trace-mfa')
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(wrapper.find('input[name="account"]').exists()).toBe(true)
})

it('退出调用 Platform Realm 的退出接口并回到登录表单', async () => {
  sessionStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', operatorName: '演示运营' }),
  )
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok({ operatorId: 'operator-1' }))
    .mockResolvedValueOnce(ok())
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await vi.waitFor(() => expect(wrapper.text()).toContain('operator-1'))
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(fetcher.mock.calls[1]![0]).toBe('/iam/platform/auth/logout')
  expect(wrapper.find('input[name="account"]').exists()).toBe(true)
  expect(sessionStorage.getItem('forge.platform.session')).toBeNull()
})
