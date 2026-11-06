import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fakeFetch, mountShell } from '../test-shell'

enableAutoUnmount(afterEach)
beforeEach(() => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', identityName: '演示身份' }),
  )
})
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

function pathsOf(fetcher: ReturnType<typeof vi.fn>) {
  return fetcher.mock.calls.map(([path]) => new URL(path as string, location.origin).pathname)
}

function button(wrapper: Awaited<ReturnType<typeof mountShell>>['wrapper'], testId: string) {
  return wrapper.get(`[data-testid="${testId}"]`)
}

it('从用户菜单进入账号安全页，修改密码时展示 password.* 错误', async () => {
  const fetcher = vi.fn(
    fakeFetch({
      '/iam/auth/memberships': ok([]),
      '/demo/context': ok({}),
      '/iam/auth/mfa': ok({ bound: false }),
      '/iam/auth/password/change': [fail('password.currentInvalid', 400, '原密码错误'), ok()],
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, router } = await mountShell('/')
  await button(wrapper, 'user-menu').trigger('click')
  await flushPromises()
  document.querySelector<HTMLElement>('[data-testid="user-account-security"]')!.click()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/account/security'))
  await flushPromises()

  await wrapper.get('input[name="current-password"]').setValue('wrong')
  await wrapper.get('input[name="new-password"]').setValue('a-new-passphrase')
  await wrapper.get('[data-testid="password-form"]').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('原密码错误')

  await wrapper.get('input[name="current-password"]').setValue('right')
  await wrapper.get('[data-testid="password-form"]').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('密码已修改')
  expect(wrapper.text()).not.toContain('原密码错误')
})

it('未绑定时可绑定验证器，保存恢复码后显示已绑定', async () => {
  const fetcher = vi.fn(
    fakeFetch({
      '/iam/auth/memberships': ok([]),
      '/demo/context': ok({}),
      '/iam/auth/mfa': [ok({ bound: false }), ok({ bound: true })],
      '/iam/auth/mfa/bind/start': ok({ otpauthUri: 'otpauth://totp/x?secret=ABCDEF' }),
      '/iam/auth/mfa/bind/confirm': ok({ recoveryCodes: ['AAAA-BBBB-CCCC-DDDD'] }),
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper } = await mountShell('/account/security')
  expect(wrapper.text()).toContain('未绑定验证器')
  await button(wrapper, 'mfa-bind').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.find('[data-testid="mfa-qr"]').exists()).toBe(true))
  await wrapper.get('input[name="bind-code"]').setValue('123456')
  await wrapper.get('[data-testid="mfa-section"] form').trigger('submit')
  await flushPromises()
  expect(button(wrapper, 'recovery-codes').text()).toContain('AAAA-BBBB-CCCC-DDDD')
  await button(wrapper, 'recovery-codes-done').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('已绑定验证器')
  // 绑定凭 Access Token 调用。
  const calls = fetcher.mock.calls as unknown as [string, RequestInit][]
  const confirm = calls.find(([path]) => path.endsWith('/bind/confirm'))!
  expect(new Headers(confirm[1].headers).get('Authorization')).toBe('Bearer a')
})

it('已绑定时可重新生成恢复码（只展示一次，可复制）并解绑', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
  const fetcher = vi.fn(
    fakeFetch({
      '/iam/auth/memberships': ok([]),
      '/demo/context': ok({}),
      '/iam/auth/mfa': [ok({ bound: true }), ok({ bound: false })],
      '/iam/auth/mfa/recovery-codes/regenerate': ok({
        recoveryCodes: ['NEW1-NEW1-NEW1-NEW1', 'NEW2-NEW2-NEW2-NEW2'],
      }),
      '/iam/auth/mfa/unbind': ok(),
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper } = await mountShell('/account/security')
  expect(wrapper.text()).toContain('已绑定验证器')

  await wrapper.get('input[name="mfa-manage-code"]').setValue('111111')
  await button(wrapper, 'mfa-regenerate').trigger('click')
  await flushPromises()
  expect(button(wrapper, 'recovery-codes').text()).toContain('NEW2-NEW2-NEW2-NEW2')
  await button(wrapper, 'recovery-codes-copy').trigger('click')
  expect(writeText).toHaveBeenCalledWith('NEW1-NEW1-NEW1-NEW1\nNEW2-NEW2-NEW2-NEW2')
  await button(wrapper, 'recovery-codes-done').trigger('click')
  await flushPromises()
  expect(wrapper.find('[data-testid="recovery-codes"]').exists()).toBe(false)

  await wrapper.get('input[name="mfa-manage-code"]').setValue('222222')
  await button(wrapper, 'mfa-unbind').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('未绑定验证器')
  expect(pathsOf(fetcher)).toContain('/iam/auth/mfa/unbind')
})
