/// <reference types="node" />
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { permissions } from './access'
import { safeRedirect } from './login-flow'
import { fakeFetch, grants, mountConsole, ok, signedIn } from './test-console'

beforeEach(() => vi.stubGlobal('crypto', webcrypto))
enableAutoUnmount(afterEach)
afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

function authorizeRedirect(input: string) {
  const state = new URL(input, location.origin).searchParams.get('state')
  const response = new Response('')
  Object.defineProperty(response, 'url', {
    value: `${location.origin}/?code=c&state=${state}`,
  })
  return response
}

function loginBackend(...granted: string[]) {
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/platform/auth/login': [
        { clientId: 'platform-console', redirectUri: location.origin + '/' },
      ],
      '/iam/oauth2/authorize': authorizeRedirect,
      '/iam/platform/auth/exchange': [
        { accessToken: 'access', refreshToken: 'r', operatorName: '华东运营' },
      ],
      '/iam/platform/access': [grants(...granted)],
    }),
  )
}

async function login(wrapper: Awaited<ReturnType<typeof mountConsole>>['wrapper']) {
  await wrapper.get('input[name="account"]').setValue('operator')
  await wrapper.get('input[name="password"]').setValue('Operator-password-123!')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

describe('路由守卫', () => {
  it('未登录访问管理页时跳转登录并记下原路径，登录后回到原页面', async () => {
    loginBackend(permissions.operatorManage)
    const { wrapper, router } = await mountConsole('/operators')
    expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/operators')

    await login(wrapper)
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/operators'))
  })

  it('登录后没有 redirect 时进入第一个有权限的页面', async () => {
    loginBackend(permissions.subAppManage, permissions.roleManage)
    const { wrapper, router } = await mountConsole('/login')
    await login(wrapper)
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/sub-apps'))
  })

  it('redirect 为站外地址时登录后回首页', async () => {
    loginBackend(permissions.planManage)
    const { wrapper, router } = await mountConsole('/login?redirect=//evil.example/plans')
    await login(wrapper)
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/plans'))
  })

  it('已登录访问登录页回首页', async () => {
    signedIn()
    vi.stubGlobal('fetch', fakeFetch({ '/iam/platform/access': [grants(permissions.tenantRead)] }))
    const { router } = await mountConsole('/login')
    expect(router.currentRoute.value.fullPath).toBe('/tenants')
  })

  it('缺少页面权限码时进入 403 页', async () => {
    signedIn()
    vi.stubGlobal('fetch', fakeFetch({ '/iam/platform/access': [grants(permissions.tenantRead)] }))
    const { wrapper, router } = await mountConsole('/operators')
    expect(router.currentRoute.value.fullPath).toBe('/403')
    expect(wrapper.find('[data-testid="status-403"]').exists()).toBe(true)
    expect(wrapper.find('.shell-header').exists()).toBe(false)
  })

  it('身份查询页与菜单按 platform:identity-mfa:reset 显示', async () => {
    signedIn()
    vi.stubGlobal('fetch', fakeFetch({ '/iam/platform/access': [grants(permissions.tenantRead)] }))
    const denied = await mountConsole('/identities')
    expect(denied.router.currentRoute.value.fullPath).toBe('/403')
    denied.wrapper.unmount()

    vi.stubGlobal(
      'fetch',
      fakeFetch({ '/iam/platform/access': [grants(permissions.identityMfaReset)] }),
    )
    const { wrapper, router } = await mountConsole('/identities')
    expect(router.currentRoute.value.fullPath).toBe('/identities')
    expect(wrapper.find('.shell-menu').text()).toContain('身份查询')
  })

  it('账号安全页无需页面权限码，任何已登录的运营人员都可进入', async () => {
    signedIn()
    vi.stubGlobal('fetch', fakeFetch({ '/iam/platform/access': [grants()] }))
    const { router } = await mountConsole('/account/security')
    expect(router.currentRoute.value.fullPath).toBe('/account/security')
  })

  it('不存在的路径显示 404 页，无需登录', async () => {
    const { wrapper, router } = await mountConsole('/no-such-page')
    expect(router.currentRoute.value.fullPath).toBe('/no-such-page')
    expect(wrapper.find('[data-testid="status-404"]').exists()).toBe(true)
  })

  it('从用户菜单退出后回到登录页', async () => {
    signedIn()
    vi.stubGlobal(
      'fetch',
      fakeFetch({
        '/iam/platform/access': [grants(permissions.tenantRead)],
        '/iam/platform/auth/logout': () => ok(),
      }),
    )
    const { wrapper, router } = await mountConsole('/tenants')
    await wrapper.get('[data-testid="user-menu"]').trigger('click')
    await flushPromises()
    ;(document.body.querySelector('[data-testid="user-logout"]') as HTMLElement).click()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(localStorage.getItem('forge.platform.session')).toBeNull()
  })

  it('其他标签页退出时回到登录页，登录时停在登录页的标签页随之进入', async () => {
    signedIn()
    vi.stubGlobal('fetch', fakeFetch({ '/iam/platform/access': [grants(permissions.planManage)] }))
    const { router } = await mountConsole('/plans')
    const session = localStorage.getItem('forge.platform.session')

    localStorage.removeItem('forge.platform.session')
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'forge.platform.session',
        oldValue: session,
        storageArea: localStorage,
      }),
    )
    await vi.waitFor(() =>
      expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/plans'),
    )

    localStorage.setItem('forge.platform.session', session!)
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'forge.platform.session',
        newValue: session,
        storageArea: localStorage,
      }),
    )
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/plans'))
  })
})

describe('safeRedirect', () => {
  it.each([
    ['/tenants?page=2', '/tenants?page=2'],
    ['//evil.example', '/'],
    ['/\\evil.example', '/'],
    ['https://evil.example', '/'],
    [undefined, '/'],
    [['/tenants'], '/'],
  ])('%s → %s', (value, expected) => {
    expect(safeRedirect(value)).toBe(expected)
  })
})
