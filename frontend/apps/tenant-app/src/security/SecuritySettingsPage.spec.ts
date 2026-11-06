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
  expect(new URL(put[0]).pathname).toBe('/iam/security-settings')
  expect(JSON.parse(put[1]!.body as string)).toEqual({ mfaRequired: true })
  expect(
    wrapper.get('[data-testid="mfa-required"] [role="switch"]').attributes('aria-checked'),
  ).toBe('true')
})

it('没有 iam:security:manage 时菜单不显示安全设置，直接访问进入 403', async () => {
  vi.stubGlobal('fetch', fakeFetch({ '/iam/auth/memberships': ok([]), '/demo/context': ok({}) }))
  const { wrapper, router } = await mountShell('/')
  expect(wrapper.text()).not.toContain('安全设置')
  await router.push('/security-settings')
  expect(router.currentRoute.value.path).toBe('/403')
})
