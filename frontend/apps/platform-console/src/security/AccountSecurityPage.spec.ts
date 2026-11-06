import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fakeFetch, mountConsole, ok, signedIn } from '../test-console'

enableAutoUnmount(afterEach)
beforeEach(signedIn)
afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

function fail(code: string, status: number, userTip?: string) {
  return new Response(
    JSON.stringify({ code, httpStatus: status, errorMessage: code, userTip, traceId: 'trace' }),
    { status },
  )
}

// 同一路径依次给出的响应，覆盖 fakeFetch 的数组形式无法表达的错误响应。
const inOrder = (...responses: Response[]) => {
  return () => responses.shift()!
}

function backend(routes: Parameters<typeof fakeFetch>[0]) {
  const fetcher = vi.fn(
    fakeFetch({ '/iam/platform/access': [{ roles: [], permissions: [] }], ...routes }),
  )
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}

function bodyOf(fetcher: ReturnType<typeof backend>, path: string) {
  const call = (fetcher.mock.calls as unknown as [string, RequestInit][]).find(([input]) =>
    input.endsWith(path),
  )
  return call && JSON.parse(call[1].body as string)
}

const newUri = 'otpauth://totp/x?secret=NEWSECRET'

it('从用户菜单进入账号安全页，修改密码时展示 password.* 错误', async () => {
  const fetcher = backend({
    '/iam/platform/auth/password/change': inOrder(
      fail('password.currentInvalid', 400, '原密码错误'),
      ok(),
    ),
  })
  const { wrapper, router } = await mountConsole('/')
  await wrapper.get('[data-testid="user-menu"]').trigger('click')
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
  expect(bodyOf(fetcher, '/iam/platform/auth/password/change')).toEqual({
    currentPassword: 'wrong',
    newPassword: 'a-new-passphrase',
  })
})

it('已绑定时验证旧动态码后更换验证器，保存新恢复码后结束', async () => {
  const fetcher = backend({
    '/iam/platform/auth/mfa/bind/start': () => fail('mfa.alreadyBound', 409, '已绑定验证器'),
    '/iam/platform/auth/mfa/replace/start': inOrder(
      fail('mfa.codeInvalid', 400, '动态码错误或已使用'),
      ok({ otpauthUri: newUri }),
    ),
    '/iam/platform/auth/mfa/bind/confirm': [{ recoveryCodes: ['NEW1-NEW1-NEW1-NEW1'] }],
  })
  const { wrapper } = await mountConsole('/account/security')
  await wrapper.get('[data-testid="mfa-change"]').trigger('click')
  await flushPromises()
  // 已绑定是正常分支，不作为错误提示。
  expect(wrapper.find('[role="alert"]').exists()).toBe(false)

  await wrapper.get('input[name="replace-code"]').setValue('111111')
  await wrapper.get('[data-testid="replace-form"]').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('动态码错误或已使用')
  expect(wrapper.find('[data-testid="mfa-secret"]').exists()).toBe(false)

  await wrapper.get('input[name="replace-code"]').setValue('222222')
  await wrapper.get('[data-testid="replace-form"]').trigger('submit')
  await flushPromises()
  expect(bodyOf(fetcher, '/iam/platform/auth/mfa/replace/start')).toEqual({ code: '111111' })
  expect(wrapper.get('[data-testid="mfa-secret"]').text()).toBe('NEWSECRET')

  await wrapper.get('input[name="bind-code"]').setValue('333333')
  await wrapper.get('[data-testid="mfa-section"] form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[data-testid="recovery-codes"]').text()).toContain('NEW1-NEW1-NEW1-NEW1')
  expect(bodyOf(fetcher, '/iam/platform/auth/mfa/bind/confirm')).toEqual({ code: '333333' })

  await wrapper.get('[data-testid="recovery-codes-done"]').trigger('click')
  await flushPromises()
  expect(wrapper.find('[data-testid="recovery-codes"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('验证器已更新')
})

it('未绑定时直接进入绑定，不要求旧动态码', async () => {
  const fetcher = backend({
    '/iam/platform/auth/mfa/bind/start': [{ otpauthUri: newUri }],
  })
  const { wrapper } = await mountConsole('/account/security')
  await wrapper.get('[data-testid="mfa-change"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-testid="mfa-secret"]').text()).toBe('NEWSECRET')
  expect(wrapper.find('input[name="replace-code"]').exists()).toBe(false)
  // 确认凭 Access Token 调用，不是登录中的会话接口。
  const start = (fetcher.mock.calls as unknown as [string, RequestInit][]).find(([input]) =>
    input.endsWith('/mfa/bind/start'),
  )!
  expect(new Headers(start[1].headers).get('Authorization')).toBe('Bearer access')
})

it('绑定已超时时回到起点并提示，旧二维码不再可用', async () => {
  backend({
    '/iam/platform/auth/mfa/bind/start': [{ otpauthUri: newUri }],
    '/iam/platform/auth/mfa/bind/confirm': () => fail('mfa.bindingExpired', 400),
  })
  const { wrapper } = await mountConsole('/account/security')
  await wrapper.get('[data-testid="mfa-change"]').trigger('click')
  await flushPromises()
  await wrapper.get('input[name="bind-code"]').setValue('333333')
  await wrapper.get('[data-testid="mfa-section"] form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('绑定已超时')
  expect(wrapper.find('[data-testid="mfa-secret"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="mfa-change"]').exists()).toBe(true)
})

it('验证动态码后重新生成恢复码，只展示一次并可复制', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined)
  vi.stubGlobal('navigator', { ...navigator, locks: navigator.locks, clipboard: { writeText } })
  const fetcher = backend({
    '/iam/platform/auth/mfa/recovery-codes/regenerate': [
      { recoveryCodes: ['NEW1-NEW1-NEW1-NEW1', 'NEW2-NEW2-NEW2-NEW2'] },
    ],
  })
  const { wrapper } = await mountConsole('/account/security')
  await wrapper.get('input[name="regenerate-code"]').setValue('111111')
  await wrapper.get('[data-testid="regenerate-form"]').trigger('submit')
  await flushPromises()
  expect(bodyOf(fetcher, '/iam/platform/auth/mfa/recovery-codes/regenerate')).toEqual({
    code: '111111',
  })
  expect(wrapper.get('[data-testid="recovery-codes"]').text()).toContain('NEW2-NEW2-NEW2-NEW2')
  await wrapper.get('[data-testid="recovery-codes-copy"]').trigger('click')
  expect(writeText).toHaveBeenCalledWith('NEW1-NEW1-NEW1-NEW1\nNEW2-NEW2-NEW2-NEW2')

  await wrapper.get('[data-testid="recovery-codes-done"]').trigger('click')
  await flushPromises()
  expect(wrapper.find('[data-testid="recovery-codes"]').exists()).toBe(false)
  expect((wrapper.get('input[name="regenerate-code"]').element as HTMLInputElement).value).toBe('')
})

it('未绑定时重新生成恢复码提示尚未绑定', async () => {
  backend({
    '/iam/platform/auth/mfa/recovery-codes/regenerate': () => fail('mfa.notBound', 409),
  })
  const { wrapper } = await mountConsole('/account/security')
  await wrapper.get('input[name="regenerate-code"]').setValue('111111')
  await wrapper.get('[data-testid="regenerate-form"]').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('尚未绑定验证器')
})
