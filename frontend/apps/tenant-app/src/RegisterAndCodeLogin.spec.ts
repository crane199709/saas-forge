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
  vi.useRealTimers()
})

function ok(data?: unknown, status = 200) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: status, data, traceId: 't' }), {
    status,
  })
}

function fail(code: string, status: number, userTip: string) {
  return new Response(
    JSON.stringify({ code, httpStatus: status, errorMessage: code, userTip, traceId: 'trace-x' }),
    { status },
  )
}

function noMemberships() {
  return ok({ clientId: 'tenant-app', redirectUri: location.origin + '/', memberships: [] })
}

function mountPanel() {
  return mount(LoginPanel, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
  })
}

it('注册成功后以新账号登录并进入无租户页', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok({ expiresIn: 300, resendIn: 60 }))
    .mockResolvedValueOnce(ok({ identityId: 'identity-new' }, 201))
    .mockResolvedValueOnce(noMemberships())
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await wrapper.get('[data-testid="mode-register"]').trigger('click')
  await wrapper.get('input[name="register-account"]').setValue('newcomer')
  await wrapper.get('input[name="register-password"]').setValue('Register-password-123!')
  await wrapper.get('input[name="register-target"]').setValue('new@example.com')
  await wrapper.get('[data-testid="send-code"]').trigger('click')
  await flushPromises()
  expect(fetcher.mock.calls[0]?.[0]).toBe('/iam/auth/register/send-code')
  expect(wrapper.get('[data-testid="send-code"]').text()).toContain('60')
  await wrapper.get('input[name="register-code"]').setValue('123456')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(JSON.parse(fetcher.mock.calls[1]?.[1]?.body)).toMatchObject({
    account: 'newcomer',
    target: 'new@example.com',
    code: '123456',
  })
  expect(JSON.parse(fetcher.mock.calls[2]?.[1]?.body)).toEqual({
    account: 'newcomer',
    password: 'Register-password-123!',
  })
  await vi.waitFor(() => expect(wrapper.text()).toContain('暂无可进入的租户'))
})

it('注册冲突时展示服务端提示并留在注册页', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValueOnce(fail('registration.accountTaken', 409, '该账号已被注册')),
  )
  const wrapper = mountPanel()
  await wrapper.get('[data-testid="mode-register"]').trigger('click')
  await wrapper.get('input[name="register-account"]').setValue('demo')
  await wrapper.get('input[name="register-password"]').setValue('Register-password-123!')
  await wrapper.get('input[name="register-target"]').setValue('dup@example.com')
  await wrapper.get('input[name="register-code"]').setValue('123456')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('该账号已被注册')
  expect(wrapper.find('input[name="register-account"]').exists()).toBe(true)
})

it('验证码登录走与密码登录相同的结果处理', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      fail('verification.tooFrequent', 429, '获取验证码过于频繁，请 42 秒后再试'),
    )
    .mockResolvedValueOnce(noMemberships())
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await wrapper.get('[data-testid="mode-code"]').trigger('click')
  await wrapper.get('input[name="code-target"]').setValue('13800000000')
  await wrapper.get('[data-testid="send-code"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('获取验证码过于频繁，请 42 秒后再试')
  await wrapper.get('input[name="code"]').setValue('123456')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(fetcher.mock.calls[1]?.[0]).toBe('/iam/auth/code-login')
  await vi.waitFor(() => expect(wrapper.text()).toContain('暂无可进入的租户'))
})
