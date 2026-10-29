import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { createAccessControl, createTenantClient, tenantClientKey } from '@forge/sdk'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { subAppHostKey } from './shell'
import { createSubAppHost } from './sub-app-host'

// 与 main.ts 相同：Shell 在应用级提供客户端与权限上下文。
function shell() {
  const client = createTenantClient(sessionStorage)
  return {
    plugins: [i18n, ElementPlus, createAccessControl(client)],
    provide: { [tenantClientKey]: client },
  }
}

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

describe('App', () => {
  it('按 i18n key 渲染简体中文文案', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
    })
    await router.push('/')
    const global = shell()
    const wrapper = mount(App, { global: { ...global, plugins: [...global.plugins, router] } })

    expect(wrapper.text()).toContain('欢迎使用 SaaS Forge 租户端')
  })

  it('Token 失效且刷新失败时清除会话并回到登录页', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'revoked', refreshToken: 'revoked', identityName: '演示身份' }),
    )
    // Membership 已停用：业务请求与刷新都被拒绝。
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((path: string) => {
        const code = path === '/iam/auth/refresh' ? 'auth.failed' : 'auth.unauthenticated'
        return Promise.resolve(
          new Response(JSON.stringify({ code, httpStatus: 401, traceId: 'trace-401' }), {
            status: 401,
          }),
        )
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
    })
    await router.push('/apps/demo/overview')
    const global = shell()
    mount(App, { global: { ...global, plugins: [...global.plugins, router] } })

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/'))
    expect(sessionStorage.getItem('forge.tenant.session')).toBeNull()
  })
})

describe('App 导航', () => {
  it('按后端下发的菜单生成导航入口，只列出可加载的 Sub-App', async () => {
    const client = createTenantClient(sessionStorage)
    const access = createAccessControl({
      api: vi.fn().mockResolvedValue({
        permissions: ['order:read'],
        menus: [
          {
            code: 'demo',
            name: '示例应用',
            children: [
              { code: 'demo.overview', name: '订单概览', app: 'demo', path: 'overview' },
              // 未在当前环境登记入口地址的 Sub-App 不在可加载列表中
              { code: 'report.monthly', name: '月报', app: 'report', path: 'monthly' },
            ],
          },
        ],
        subApps: [
          {
            code: 'demo',
            name: '示例',
            version: '1',
            remoteEntry: 'http://127.0.0.1:5175/remoteEntry.js',
            sharedRequirements: {},
          },
        ],
      }),
    })
    await access.load()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
    })
    await router.push('/')
    const host = createSubAppHost({
      router,
      sharedVersions: {},
      loadModule: () => new Promise(() => {}),
      timeoutMs: 1000,
    })
    host.apply(access.menus(), access.subApps())
    const wrapper = mount(App, {
      global: {
        plugins: [i18n, ElementPlus, access, router],
        provide: { [tenantClientKey]: client, [subAppHostKey]: host },
      },
    })

    const link = wrapper.get('[data-testid="menu-demo.overview"]')
    expect(link.text()).toBe('订单概览')
    expect(link.attributes('href')).toBe('/apps/demo/overview')
    expect(wrapper.find('[data-testid="menu-report.monthly"]').exists()).toBe(false)
  })

  it('租户管理入口按 iam 管理权限码显示，权限收回后随之隐藏', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'm' }),
    )
    const client = createTenantClient(sessionStorage)
    const api = vi
      .fn()
      .mockResolvedValueOnce({ permissions: ['iam:role:manage'], menus: [] })
      .mockResolvedValueOnce({ permissions: [], menus: [] })
    const access = createAccessControl({ api })
    await access.load()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
    })
    await router.push('/')
    const wrapper = mount(App, {
      global: {
        plugins: [i18n, ElementPlus, access, router],
        provide: { [tenantClientKey]: client },
      },
    })

    expect(wrapper.get('[data-testid="menu-roles"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="menu-org"]').isVisible()).toBe(false)

    await access.load()
    await nextTick()
    expect(wrapper.get('[data-testid="menu-roles"]').isVisible()).toBe(false)
  })
})
