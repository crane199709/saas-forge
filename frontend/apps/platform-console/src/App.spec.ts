import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { permissions } from './access'
import { fakeFetch, grants, mountConsole, signedIn } from './test-console'

enableAutoUnmount(afterEach)
afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

function menu(wrapper: Awaited<ReturnType<typeof mountConsole>>['wrapper']) {
  return wrapper.findAll('.shell-menu .menu-link').map((link) => link.text())
}

// 其他标签页刷新 Token：同一 Operator 换发新的 Access Token。
function otherTabRefreshes(accessToken: string) {
  const oldValue = localStorage.getItem('forge.platform.session')
  const newValue = JSON.stringify({ accessToken, refreshToken: 'r2', operatorName: '华东运营' })
  localStorage.setItem('forge.platform.session', newValue)
  window.dispatchEvent(
    new StorageEvent('storage', {
      key: 'forge.platform.session',
      oldValue,
      newValue,
      storageArea: localStorage,
    }),
  )
}

describe('App 布局', () => {
  it('顶栏显示产品名与当前 Operator，侧边菜单按权限列出平台管理页', async () => {
    signedIn()
    vi.stubGlobal(
      'fetch',
      fakeFetch({ '/iam/platform/access': [grants(...Object.values(permissions))] }),
    )
    const { wrapper } = await mountConsole('/plans')

    expect(wrapper.get('.brand').text()).toBe('SaaS Forge')
    expect(wrapper.get('[data-testid="user-menu"]').text()).toBe('华东运营')
    expect(menu(wrapper)).toEqual([
      '租户',
      '套餐',
      '应用注册表',
      '运营账号',
      '平台角色',
      '平台审计',
    ])
    expect(wrapper.get('.shell-menu .is-active').text()).toBe('套餐')
    expect(wrapper.find('[aria-label="套餐"]').exists()).toBe(true)
  })

  it('只读租户时菜单只有租户，页面不显示管理功能', async () => {
    signedIn()
    vi.stubGlobal('fetch', fakeFetch({ '/iam/platform/access': [grants(permissions.tenantRead)] }))
    const { wrapper, router } = await mountConsole('/')

    expect(router.currentRoute.value.path).toBe('/tenants')
    expect(menu(wrapper)).toEqual(['租户'])
    expect(wrapper.find('[aria-label="租户"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('创建租户')
  })

  it('没有任何平台权限时停在首页并提示', async () => {
    signedIn()
    vi.stubGlobal('fetch', fakeFetch({ '/iam/platform/access': [grants()] }))
    const { wrapper, router } = await mountConsole('/')

    expect(router.currentRoute.value.path).toBe('/')
    expect(menu(wrapper)).toEqual([])
    expect(wrapper.get('[data-testid="access-none"]').text()).toContain('没有任何平台权限')
  })

  it('读取权限失败时提示，而不是显示无权限', async () => {
    signedIn()
    vi.stubGlobal(
      'fetch',
      fakeFetch({ '/iam/platform/access': () => new Response('', { status: 500 }) }),
    )
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const { wrapper, router } = await mountConsole('/operators')

    expect(router.currentRoute.value.path).toBe('/')
    expect(wrapper.get('[role="alert"]').text()).toContain('读取平台权限失败')
    expect(wrapper.find('[data-testid="access-none"]').exists()).toBe(false)
  })

  it('Token 换发后权限变化时菜单随之更新，当前页面失去权限时进入 403', async () => {
    signedIn()
    vi.stubGlobal(
      'fetch',
      fakeFetch({
        '/iam/platform/access': [
          grants(permissions.tenantRead, permissions.operatorManage),
          grants(permissions.tenantRead, permissions.planManage),
        ],
      }),
    )
    const { wrapper, router } = await mountConsole('/operators')
    expect(menu(wrapper)).toEqual(['租户', '运营账号'])

    otherTabRefreshes('access-2')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/403'))
    await flushPromises()
    expect(wrapper.find('[data-testid="status-403"]').exists()).toBe(true)

    await router.push('/tenants')
    await flushPromises()
    expect(menu(wrapper)).toEqual(['租户', '套餐'])
  })
})
