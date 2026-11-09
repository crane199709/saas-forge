import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { fakeFetch, mountShell } from './test-shell'

enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function ok(data?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 'trace-ok' }))
}

function fail(code: string, status: number, userTip?: string) {
  return new Response(
    JSON.stringify({ code, httpStatus: status, errorMessage: code, userTip, traceId: 'trace' }),
    { status },
  )
}

it('从登录页进入忘记密码，验证码通过后设置新密码；已绑定验证器时须同时输入动态码', async () => {
  const fetcher = vi.fn(
    fakeFetch({
      '/iam/auth/password/forgot/send-code': ok({ expiresIn: 300, resendIn: 60 }),
      '/iam/auth/password/forgot/verify': ok({ resetToken: 'reset-1', mfaRequired: true }),
      '/iam/auth/password/forgot/reset': [fail('password.tooCommon', 400), ok()],
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, router } = await mountShell('/login')
  await wrapper.get('[data-testid="forgot-password"]').trigger('click')
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/forgot-password'))
  await flushPromises()

  await wrapper.get('input[name="reset-target"]').setValue('a@example.com')
  await wrapper.get('[data-testid="send-code"]').trigger('click')
  await wrapper.get('input[name="reset-code"]').setValue('123456')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(wrapper.text()).toContain('动态码')
  await wrapper.get('input[name="new-password"]').setValue('password')
  await wrapper.get('input[name="mfa-code"]').setValue('654321')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  // 没有 userTip 时按错误码展示前端文案。
  expect(wrapper.get('[role="alert"]').text()).toContain('该密码过于常见')

  await wrapper.get('input[name="new-password"]').setValue('a-much-better-passphrase')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('密码已重设')
  const resets = fetcher.mock.calls.filter(([path]) => path === '/iam/auth/password/forgot/reset')
  expect(JSON.parse((resets[1] as unknown as [string, RequestInit])[1].body as string)).toEqual({
    resetToken: 'reset-1',
    password: 'a-much-better-passphrase',
    code: '654321',
  })
})
