import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createSubAppHost } from './sub-app-host'
import { fakeFetch, mountShell } from './test-shell'

enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function ok(data?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 't' }))
}

function signedIn() {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({
      accessToken: 'access',
      refreshToken: 'r',
      identityName: '演示身份',
      tenantName: '演示租户',
      membershipId: 'm',
    }),
  )
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/auth/memberships': ok([{ membershipId: 'm', tenantId: 't', tenantName: '演示租户' }]),
    }),
  )
}

describe('App 布局', () => {
  it('工作台带顶栏与侧边菜单，问候语只显示当前身份名与租户名', async () => {
    signedIn()
    const { wrapper } = await mountShell('/')

    expect(wrapper.get('.shell-header').text()).toContain('SaaS Forge')
    expect(wrapper.get('[data-testid="user-menu"]').text()).toBe('演示身份')
    expect(wrapper.find('[data-testid="tenant-switcher"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="menu-home"]').attributes('href')).toBe('/')
    expect(wrapper.get('.page-header h2').text()).toBe('工作台')
    expect(wrapper.get('[data-testid="workbench-greeting"]').text()).toBe('演示身份 · 演示租户')
    expect(wrapper.find('.el-descriptions').exists()).toBe(false)
  })

  it('登录与异常页不带布局', async () => {
    const { wrapper } = await mountShell('/login')
    expect(wrapper.find('.shell-header').exists()).toBe(false)
    expect(wrapper.find('input[name="account"]').exists()).toBe(true)
  })

  it('按后端下发的菜单分组列出可加载的 Sub-App，当前路由对应的菜单项高亮', async () => {
    signedIn()
    const menus = [
      {
        code: 'demo',
        name: '示例应用',
        children: [
          { code: 'demo.overview', name: '订单概览', app: 'demo', path: 'overview' },
          // 未在当前环境登记入口地址的 Sub-App 不在可加载列表中
          { code: 'report.monthly', name: '月报', app: 'report', path: 'monthly' },
        ],
      },
    ]
    const subApps = [
      {
        code: 'demo',
        name: '示例',
        version: '1',
        remoteEntry: 'http://127.0.0.1:5175/remoteEntry.js',
        sharedRequirements: {},
      },
    ]
    // 宿主只用于提供已注册的 Sub-App 编码，路由注册在独立的路由器上
    const host = createSubAppHost({
      router: createRouter({ history: createMemoryHistory(), routes: [] }),
      sharedVersions: {},
      loadModule: () => new Promise(() => {}),
      timeoutMs: 1000,
    })
    host.apply(menus, subApps)
    const { wrapper, router } = await mountShell('/', {
      access: async () => ({ permissions: ['iam:member:manage'], menus, subApps }),
      host,
    })

    expect(wrapper.text()).toContain('示例应用')
    const link = wrapper.get('[data-testid="menu-demo.overview"]')
    expect(link.text()).toBe('订单概览')
    expect(link.attributes('href')).toBe('/apps/demo/overview')
    expect(wrapper.find('[data-testid="menu-report.monthly"]').exists()).toBe(false)
    expect(wrapper.get('.el-menu-item.is-active').text()).toBe('工作台')

    vi.stubGlobal('fetch', fakeFetch({ '/iam/members': ok([]) }))
    await router.push('/members')
    await flushPromises()
    expect(wrapper.get('.el-menu-item.is-active').text()).toBe('成员管理')
  })

  it('导航按租户布局排列，首页与通知中心固定在顶部，清单多层分组按原层级显示', async () => {
    signedIn()
    const menus = [
      {
        code: 'demo',
        name: '示例应用',
        children: [
          { code: 'demo.overview', name: '订单概览', app: 'demo', path: 'overview' },
          {
            code: 'demo.finance',
            name: '财务报表',
            children: [{ code: 'demo.bills', name: '账单', app: 'demo', path: 'bills' }],
          },
        ],
      },
    ]
    const subApps = [
      {
        code: 'demo',
        name: '示例',
        version: '1',
        remoteEntry: 'http://127.0.0.1:5175/remoteEntry.js',
        sharedRequirements: {},
      },
    ]
    const host = createSubAppHost({
      router: createRouter({ history: createMemoryHistory(), routes: [] }),
      sharedVersions: {},
      loadModule: () => new Promise(() => {}),
      timeoutMs: 1000,
    })
    host.apply(menus, subApps)
    // 首页与通知中心不进入布局，布局中引用它们也不改变位置
    const layout = {
      nodes: [
        { page: 'notifications' },
        { group: 'g1', name: '常用', children: [{ menu: 'demo.overview' }, { page: 'roles' }] },
        { page: 'members' },
      ],
    }
    const { wrapper } = await mountShell('/', {
      access: async () => ({
        permissions: [
          'iam:member:manage',
          'iam:role:manage',
          'iam:audit:read',
          'notification:read',
        ],
        menus,
        subApps,
        layout,
      }),
      host,
    })

    expect(wrapper.findAll('.shell-menu .menu-link').map((link) => link.text())).toEqual([
      '工作台',
      '通知中心',
      '订单概览',
      '角色管理',
      '成员管理',
      '审计日志',
      '账单',
    ])
    expect(wrapper.get('[data-testid="menu-group-g1"]').text()).toContain('常用')
    expect(
      wrapper.get('[data-testid="menu-group-demo"] [data-testid="menu-group-demo.finance"]').text(),
    ).toContain('账单')
  })

  it('内置管理页入口按 iam 管理权限码显示，权限收回后随之隐藏', async () => {
    signedIn()
    const read = vi
      .fn()
      .mockResolvedValueOnce({ permissions: ['iam:role:manage'] })
      .mockResolvedValueOnce({ permissions: [] })
    const { wrapper, access } = await mountShell('/', { access: read })

    expect(wrapper.find('[data-testid="menu-roles"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="menu-members"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="menu-org"]').exists()).toBe(false)

    await access.load()
    await nextTick()
    expect(wrapper.find('[data-testid="menu-roles"]').exists()).toBe(false)
  })

  it('只有历史查看权限的审计员可以导航到字典页', async () => {
    signedIn()
    const { wrapper, router } = await mountShell('/dictionaries', {
      access: async () => ({ permissions: ['iam:dictionary-history:read'] }),
    })
    expect(router.currentRoute.value.path).toBe('/dictionaries')
    expect(wrapper.get('[data-testid="menu-dictionaries"]').text()).toBe('字典')
    expect(wrapper.find('[data-testid="menu-parameters"]').exists()).toBe(false)
  })

  it('字典与参数页入口要求 iam:reference-data:manage', async () => {
    signedIn()
    const { wrapper } = await mountShell('/', {
      access: async () => ({ permissions: ['iam:reference-data:manage'] }),
    })
    expect(wrapper.get('[data-testid="menu-dictionaries"]').text()).toBe('字典')
    expect(wrapper.get('[data-testid="menu-parameters"]').text()).toBe('参数')
    expect(wrapper.find('[data-testid="menu-roles"]').exists()).toBe(false)
  })

  it('菜单管理入口要求 iam:menu:manage，默认排在清单菜单之后', async () => {
    signedIn()
    const menus = [{ code: 'demo.orders', name: '订单', app: 'demo', path: 'orders' }]
    const subApps = [
      { code: 'demo', name: '示例', version: '1', remoteEntry: 'x', sharedRequirements: {} },
    ]
    const host = createSubAppHost({
      router: createRouter({ history: createMemoryHistory(), routes: [] }),
      sharedVersions: {},
      loadModule: () => new Promise(() => {}),
      timeoutMs: 1000,
    })
    host.apply(menus, subApps)
    const { wrapper } = await mountShell('/', {
      access: async () => ({
        permissions: ['iam:menu:manage', 'iam:role:manage'],
        menus,
        subApps,
      }),
      host,
    })

    const keys = wrapper
      .findAll('[data-testid^="menu-"]')
      .map((item) => item.attributes('data-testid'))
    expect(keys.slice(-3)).toEqual(['menu-roles', 'menu-demo.orders', 'menu-menu-layout'])
    expect(wrapper.get('[data-testid="menu-menu-layout"]').text()).toBe('菜单管理')
  })
})

it.each(['todo:read', 'notification:read', 'announcement:read'])(
  '通知中心入口在拥有 %s 时显示，撤销读取权限后隐藏',
  async (permission) => {
    signedIn()
    let permissions: string[] = []
    const { wrapper, access } = await mountShell('/', { access: async () => ({ permissions }) })
    expect(wrapper.find('[data-testid="menu-notifications"]').exists()).toBe(false)

    permissions = [permission]
    await access.load()
    await nextTick()
    expect(wrapper.get('[data-testid="menu-notifications"]').attributes('href')).toBe(
      '/notifications',
    )

    access.clear()
    await nextTick()
    expect(wrapper.find('[data-testid="menu-notifications"]').exists()).toBe(false)
  },
)
