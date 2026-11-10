import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import {
  createAccessControl,
  createTenantClient,
  tenantClientKey,
  type NavigationLayout,
} from '@crane199709/sdk'
import { i18n } from '../i18n'
import MenuLayoutPage from './MenuLayoutPage.vue'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

const menus = [
  {
    code: 'demo',
    name: '示例应用',
    children: [
      { code: 'demo.orders', name: '订单列表', app: 'demo', path: 'orders', children: [] },
      {
        code: 'demo.attachments',
        name: '订单附件',
        app: 'demo',
        path: 'attachments',
        children: [],
      },
    ],
  },
]

const saved: NavigationLayout = {
  nodes: [
    { group: 'g1', name: '财务', children: [{ menu: 'demo.attachments' }] },
    { page: 'members' },
  ],
}

function respond(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify({ httpStatus: status, traceId: 'trace-nav', ...body }), {
    status,
  })
}

const ok = (data: unknown) => respond(200, { code: 'ok', data })
const failed = () => respond(500, { code: 'system.error', userTip: '服务暂时不可用', data: null })

/** 读取依次取 reads 中的应答，其余请求依次取 writes 中的应答。 */
async function mountPage(reads: Response[], writes: Response[] = []) {
  const fetcher = vi.fn((_input: string, init: RequestInit) =>
    Promise.resolve(init.method === 'GET' ? reads.shift()! : writes.shift()!),
  )
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'm' }),
  )
  const accessLoads = vi.fn(async () => ({ permissions: [], menus: [], subApps: [] }))
  const access = createAccessControl({ api: accessLoads as never })
  const wrapper = mount(MenuLayoutPage, {
    attachTo: document.body,
    global: {
      plugins: [i18n, ElementPlus, access],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
  })
  await flushPromises()
  const writeCalls = () => fetcher.mock.calls.filter(([, init]) => init.method !== 'GET')
  return { wrapper, writes: writeCalls, accessLoads }
}

const rows = (wrapper: ReturnType<typeof mount>) =>
  wrapper.findAll('[data-testid^="navigation-node-"]').map((row) => row.text())

it('目录为租户管理页与按套餐裁剪的清单菜单，不含首页与通知中心', async () => {
  const { wrapper } = await mountPage([ok({ layout: null, menus })])

  expect(rows(wrapper)).toEqual([
    '公告管理',
    '成员管理',
    '组织管理',
    '角色管理',
    '审计日志',
    '字典',
    '参数',
    '安全设置',
    '示例应用',
    '订单列表',
    '订单附件',
    '菜单管理',
  ])
})

it('保存整棵布局，保存后刷新当前用户的权限与导航', async () => {
  const { wrapper, writes, accessLoads } = await mountPage(
    [ok({ layout: saved, menus })],
    [ok(saved)],
  )
  expect(rows(wrapper).slice(0, 3)).toEqual([
    expect.stringContaining('财务'),
    '订单附件',
    '成员管理',
  ])

  await wrapper.get('[data-testid="navigation-save"]').trigger('click')
  await flushPromises()

  const [url, init] = writes()[0]!
  expect(url).toContain('/iam/navigation-layout')
  expect(init.method).toBe('PUT')
  const body = JSON.parse(init.body as string) as NavigationLayout
  expect(body.nodes.slice(0, 2)).toEqual(saved.nodes)
  expect(body.nodes.at(-1)).toEqual({ page: 'menu-layout' })
  expect(accessLoads).toHaveBeenCalledTimes(1)
  expect(document.body.textContent).not.toContain('已保存')
})

it('保存失败时用消息提示，不刷新权限', async () => {
  const { wrapper, accessLoads } = await mountPage([ok({ layout: null, menus })], [failed()])

  await wrapper.get('[data-testid="navigation-save"]').trigger('click')
  await flushPromises()

  expect(document.body.textContent).toContain('服务暂时不可用')
  expect(accessLoads).not.toHaveBeenCalled()
})

it('恢复默认须在确认弹窗中确认，之后按默认顺序显示并刷新导航', async () => {
  const { wrapper, writes, accessLoads } = await mountPage(
    [ok({ layout: saved, menus })],
    [ok(null)],
  )

  await wrapper.get('[data-testid="navigation-reset"]').trigger('click')
  await flushPromises()
  expect(writes()).toHaveLength(0)
  expect(document.body.textContent).toContain('全体成员的导航将恢复默认顺序')

  document.querySelector<HTMLButtonElement>('[data-testid="menu-layout-reset-confirm"]')!.click()
  await flushPromises()

  const [url, init] = writes()[0]!
  expect(url).toContain('/iam/navigation-layout')
  expect(init.method).toBe('DELETE')
  expect(rows(wrapper)[0]).toBe('公告管理')
  expect(accessLoads).toHaveBeenCalledTimes(1)
  expect(document.body.textContent).not.toContain('已恢复默认')
})

it('读取失败时显示错误，可以重试', async () => {
  const { wrapper } = await mountPage([failed(), ok({ layout: null, menus })])

  expect(wrapper.text()).toContain('服务暂时不可用')
  expect(wrapper.find('[data-testid="navigation-save"]').exists()).toBe(false)

  await wrapper.get('[data-testid="menu-layout-retry"]').trigger('click')
  await flushPromises()

  expect(wrapper.text()).not.toContain('服务暂时不可用')
  expect(rows(wrapper)).toContain('订单附件')
})
