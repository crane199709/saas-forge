import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import IdentityPanel from './IdentityPanel.vue'
import { createIdentityApi } from './identity-api'

afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

function ok(data?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 't' }))
}

function fail(code: string, status: number, userTip?: string) {
  return new Response(
    JSON.stringify({ code, httpStatus: status, errorMessage: code, userTip, traceId: 't' }),
    { status },
  )
}

function mountPanel() {
  localStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '运营' }),
  )
  return mount(IdentityPanel, {
    props: { api: createIdentityApi(createPlatformClient(localStorage)) },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}

async function search(wrapper: ReturnType<typeof mountPanel>, identifier: string) {
  await wrapper.get('input[name="identifier"]').setValue(identifier)
  await wrapper.get('[data-testid="identity-lookup"]').trigger('submit')
  await flushPromises()
}

const button = (testId: string) =>
  document.body.querySelector(`[data-testid="${testId}"]`) as HTMLButtonElement | null

it('按登录标识精确查找，展示已绑定并经确认后重置 MFA', async () => {
  const fetcher = vi.fn((url: string, _init?: RequestInit) =>
    Promise.resolve(
      url.endsWith('/lookup')
        ? ok({ identityId: 'i1', displayName: '张三', mfaBound: true })
        : ok(),
    ),
  )
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await search(wrapper, ' zhang@example.com ')

  const lookup = fetcher.mock.calls[0]!
  expect(new URL(lookup[0], location.origin).pathname.replace(/^\/api(?=\/)/, '')).toBe(
    '/iam/platform/identities/lookup',
  )
  expect(JSON.parse(lookup[1]!.body as string)).toEqual({ identifier: 'zhang@example.com' })
  expect(wrapper.text()).toContain('张三')
  expect(wrapper.get('[data-testid="identity-mfa-status"]').text()).toBe('已绑定')

  button('identity-mfa-reset')!.click()
  await flushPromises()
  // 重置前须确认：尚未发出请求。
  expect(fetcher).toHaveBeenCalledTimes(1)
  button('identity-mfa-reset-confirm')!.click()
  await flushPromises()

  const reset = fetcher.mock.calls[1]!
  expect(new URL(reset[0], location.origin).pathname.replace(/^\/api(?=\/)/, '')).toBe(
    '/iam/platform/identities/i1/mfa/reset',
  )
  expect(reset[1]!.method).toBe('POST')
  expect(wrapper.get('[data-testid="identity-mfa-status"]').text()).toBe('未绑定')
  expect(wrapper.text()).toContain('已重置')
  expect(button('identity-mfa-reset')).toBeNull()
})

it('未绑定的身份不提供重置', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(ok({ identityId: 'i1', displayName: '张三', mfaBound: false }))),
  )
  const wrapper = mountPanel()
  await search(wrapper, 'zhang')
  expect(wrapper.get('[data-testid="identity-mfa-status"]').text()).toBe('未绑定')
  expect(button('identity-mfa-reset')).toBeNull()
})

it('查不到时提示未找到并清除上一次的结果', async () => {
  const responses = [
    ok({ identityId: 'i1', displayName: '张三', mfaBound: true }),
    fail('mfa.identityNotFound', 404, '未找到该登录标识对应的身份'),
  ]
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(responses.shift()!)),
  )
  const wrapper = mountPanel()
  await search(wrapper, 'zhang')
  expect(wrapper.text()).toContain('张三')

  await search(wrapper, 'nobody')
  expect(wrapper.get('[role="alert"]').text()).toContain('未找到')
  expect(wrapper.text()).not.toContain('张三')
})

it('重置时对方已解绑：提示并更新为未绑定', async () => {
  const responses = [
    ok({ identityId: 'i1', displayName: '张三', mfaBound: true }),
    fail('mfa.notBound', 409),
  ]
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(responses.shift()!)),
  )
  const wrapper = mountPanel()
  await search(wrapper, 'zhang')
  button('identity-mfa-reset')!.click()
  await flushPromises()
  button('identity-mfa-reset-confirm')!.click()
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('尚未绑定验证器')
  expect(wrapper.get('[data-testid="identity-mfa-status"]').text()).toBe('未绑定')
})
