/// <reference types="node" />
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { fakeFetch, mountShell } from '../test-shell'

beforeEach(() => {
  vi.stubGlobal('crypto', webcrypto)
  vi.stubGlobal('navigator', {
    ...navigator,
    locks: { request: (_name: string, task: () => unknown) => task() },
  })
})
enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function ok(data?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 't' }))
}

function fail(code: string, status: number) {
  return new Response(
    JSON.stringify({ code, httpStatus: status, errorMessage: code, userTip: code, traceId: 't' }),
    { status },
  )
}

function authorizeRedirect(path: string) {
  const state = new URL(path, location.origin).searchParams.get('state')
  const response = new Response('')
  Object.defineProperty(response, 'url', { value: `${location.origin}/?code=c&state=${state}` })
  return response
}

const blocked = {
  accessToken: 'expired',
  refreshToken: 'refresh-a',
  identityName: '被拦身份',
  membershipId: 'membership-a',
}

const otpauth = { otpauthUri: 'otpauth://totp/x?secret=ABCDEF' }

async function blockedShell(routes: Parameters<typeof fakeFetch>[0]) {
  sessionStorage.setItem('forge.tenant.session', JSON.stringify(blocked))
  const fetcher = vi.fn(
    fakeFetch({
      '/iam/auth/refresh': fail('auth.mfaRequired', 403),
      '/demo/context': [fail('auth.unauthenticated', 401), ok({})],
      '/iam/auth/memberships': ok([]),
      ...routes,
    }),
  )
  vi.stubGlobal('fetch', fetcher)
  const shell = await mountShell('/members?tab=x', {
    access: () => Promise.resolve({ permissions: ['iam:member:manage'] }),
  })
  await shell.client.api('/demo/context').catch(() => undefined)
  return { ...shell, fetcher }
}

it('Access Token 已过期且刷新被拒时进入强制绑定，凭登录会话绑定后重新走授权码流程回到原页面', async () => {
  const { wrapper, router, client, fetcher } = await blockedShell({
    // 第一次带已过期的 Access Token 被网关拒绝，第二次凭登录会话。
    '/iam/auth/mfa/bind/start': [fail('auth.unauthenticated', 401), ok(otpauth)],
    '/iam/auth/mfa/bind/confirm': [
      fail('auth.unauthenticated', 401),
      ok({
        recoveryCodes: ['AAAA-BBBB-CCCC-DDDD'],
        clientId: 'tenant-app',
        redirectUri: location.origin + '/',
      }),
    ],
    '/iam/oauth2/authorize': authorizeRedirect,
    '/iam/auth/exchange': ok({
      ...blocked,
      accessToken: 'access-new',
      refreshToken: 'refresh-new',
    }),
  })
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/mfa-bind'))
  expect(router.currentRoute.value.query.redirect).toContain('/members')
  // 被拦时不清除会话，用户不必退出。
  expect(client.session()?.accessToken).toBe('expired')

  await vi.waitFor(() => expect(wrapper.find('[data-testid="mfa-qr"]').exists()).toBe(true))
  await wrapper.get('input[name="bind-code"]').setValue('654321')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  await vi.waitFor(() =>
    expect(wrapper.find('[data-testid="recovery-codes-done"]').exists()).toBe(true),
  )
  await wrapper.get('[data-testid="recovery-codes-done"]').trigger('click')
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/members'))

  expect(client.session()?.accessToken).toBe('access-new')
  const exchange = fetcher.mock.calls.find(
    ([path]) => path === '/iam/auth/exchange',
  ) as unknown as [string, RequestInit]
  expect(JSON.parse(exchange[1].body as string)).toMatchObject({ membershipId: 'membership-a' })
})

it('Token 可用时凭 Token 绑定，绑定后用原 Refresh Token 刷新进入', async () => {
  const { wrapper, router, client, fetcher } = await blockedShell({
    '/iam/auth/refresh': [
      fail('auth.mfaRequired', 403),
      ok({ ...blocked, accessToken: 'access-new', refreshToken: 'refresh-new' }),
    ],
    '/iam/auth/mfa/bind/start': ok(otpauth),
    '/iam/auth/mfa/bind/confirm': ok({ recoveryCodes: ['AAAA-BBBB-CCCC-DDDD'] }),
  })
  await vi.waitFor(() => expect(wrapper.find('[data-testid="mfa-qr"]').exists()).toBe(true))
  await wrapper.get('input[name="bind-code"]').setValue('654321')
  await wrapper.get('form').trigger('submit')
  await vi.waitFor(() =>
    expect(wrapper.find('[data-testid="recovery-codes-done"]').exists()).toBe(true),
  )
  await wrapper.get('[data-testid="recovery-codes-done"]').trigger('click')
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/members'))
  expect(client.session()?.accessToken).toBe('access-new')
  expect(fetcher.mock.calls.some(([path]) => path === '/iam/auth/exchange')).toBe(false)
})

it('登录会话也已失效时回到登录页走完整流程', async () => {
  const { wrapper, router, client } = await blockedShell({
    '/iam/auth/mfa/bind/start': fail('auth.unauthenticated', 401),
  })
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
  await flushPromises()
  expect(client.session()).toBeNull()
  expect(wrapper.get('[role="alert"]').text()).toContain('请重新登录以完成两步验证')
  expect(wrapper.find('input[name="password"]').exists()).toBe(true)
})

it('身份已在别处绑定时无法当场补记，回到登录页完成 MFA', async () => {
  const { router, client } = await blockedShell({
    '/iam/auth/mfa/bind/start': [fail('auth.unauthenticated', 401), fail('mfa.alreadyBound', 409)],
  })
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
  expect(client.session()).toBeNull()
})
