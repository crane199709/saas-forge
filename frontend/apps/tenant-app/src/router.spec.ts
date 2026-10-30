/// <reference types="node" />
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { safeRedirect } from './login-flow'
import { fakeFetch, mountShell } from './test-shell'

beforeEach(() => vi.stubGlobal('crypto', webcrypto))
enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function ok(data?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 't' }))
}

function unauthenticated(code: string) {
  return new Response(JSON.stringify({ code, httpStatus: 401, traceId: 't' }), { status: 401 })
}

function signedIn() {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', identityName: '演示身份' }),
  )
}

// 账号只有一个 Membership，登录后直接进入租户。
function loginBackend() {
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/auth/login': ok({
        clientId: 'tenant-app',
        redirectUri: location.origin + '/',
        memberships: [{ membershipId: 'm', tenantId: 't', tenantName: '演示租户' }],
      }),
      '/iam/oauth2/authorize': (path) => {
        const state = new URL(path, location.origin).searchParams.get('state')
        const response = new Response('')
        Object.defineProperty(response, 'url', {
          value: `${location.origin}/?code=c&state=${state}`,
        })
        return response
      },
      '/iam/auth/exchange': ok({
        accessToken: 'a',
        refreshToken: 'r',
        identityName: '演示身份',
        membershipId: 'm',
      }),
    }),
  )
}

async function login(wrapper: Awaited<ReturnType<typeof mountShell>>['wrapper']) {
  await wrapper.get('input[name="account"]').setValue('demo')
  await wrapper.get('input[name="password"]').setValue('Demo-password-123!')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('路由守卫', () => {
  it('未登录访问需登录页面时跳转登录并记下原路径，登录后回到原页面', async () => {
    loginBackend()
    const { wrapper, router } = await mountShell('/members', {
      access: async () => ({ permissions: ['iam:member:manage'] }),
    })
    expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/members')

    await login(wrapper)
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/members'))
  })

  it('未登录访问 Sub-App 深链接也先登录', async () => {
    const { router } = await mountShell('/apps/demo/overview')
    expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/apps/demo/overview')
  })

  it('redirect 为站外地址时登录后回首页', async () => {
    loginBackend()
    const { wrapper, router } = await mountShell('/login?redirect=//evil.example/members')
    await login(wrapper)
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/'))
  })

  it('已登录访问登录页回首页', async () => {
    signedIn()
    vi.stubGlobal('fetch', fakeFetch({}))
    const { router } = await mountShell('/login')
    expect(router.currentRoute.value.fullPath).toBe('/')
  })

  it('缺少路由声明的权限码时进入 403 页', async () => {
    signedIn()
    vi.stubGlobal('fetch', fakeFetch({}))
    const { wrapper, router } = await mountShell('/roles', {
      access: async () => ({ permissions: ['iam:member:manage'] }),
    })
    expect(router.currentRoute.value.fullPath).toBe('/403')
    expect(wrapper.find('[data-testid="status-403"]').exists()).toBe(true)
    expect(wrapper.find('.shell-header').exists()).toBe(false)
  })

  it('不存在的路径显示 404 页，无需登录', async () => {
    const { wrapper, router } = await mountShell('/no-such-page')
    expect(router.currentRoute.value.fullPath).toBe('/no-such-page')
    expect(wrapper.find('[data-testid="status-404"]').exists()).toBe(true)
  })

  it('Token 失效且刷新失败时清除会话并回到登录页', async () => {
    signedIn()
    // Membership 已停用：业务请求与刷新都被拒绝。
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation((path: string) =>
          Promise.resolve(
            unauthenticated(path === '/iam/auth/refresh' ? 'auth.failed' : 'auth.unauthenticated'),
          ),
        ),
    )
    const { router } = await mountShell('/')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(sessionStorage.getItem('forge.tenant.session')).toBeNull()
  })
})

describe('safeRedirect', () => {
  it.each([
    ['/members?tab=1', '/members?tab=1'],
    ['//evil.example', '/'],
    ['/\\evil.example', '/'],
    ['https://evil.example', '/'],
    [undefined, '/'],
    [['/members'], '/'],
  ])('%s → %s', (value, expected) => {
    expect(safeRedirect(value)).toBe(expected)
  })
})
