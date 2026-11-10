import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import type { NavigationLayout } from '@crane199709/sdk'
import { permissions } from '../access'
import { grants, mountConsole, signedIn } from '../test-console'

enableAutoUnmount(afterEach)
afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

const saved: NavigationLayout = {
  nodes: [{ group: 'g1', name: '运维', children: [{ page: 'dead-events' }] }, { page: 'plans' }],
}

function respond(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify({ httpStatus: status, traceId: 'trace-nav', ...body }), {
    status,
  })
}

const ok = (data: unknown) => respond(200, { code: 'ok', data })
const failed = () => respond(500, { code: 'system.error', userTip: '服务暂时不可用', data: null })

/** 布局读取依次取 reads 中的应答，保存与恢复默认依次取 writes 中的应答；Operator 只持有菜单管理权限。 */
async function mountPage(reads: Response[], writes: Response[] = []) {
  const calls: { path: string; init?: RequestInit }[] = []
  vi.stubGlobal('fetch', (input: string, init?: RequestInit) => {
    const path = new URL(input, location.origin).pathname
    calls.push({ path, init })
    if (path === '/iam/platform/access') return Promise.resolve(ok(grants(permissions.menuManage)))
    if (path === '/iam/platform/navigation-layout') {
      return Promise.resolve((init?.method ?? 'GET') === 'GET' ? reads.shift()! : writes.shift()!)
    }
    return Promise.resolve(ok([]))
  })
  signedIn()
  const { wrapper } = await mountConsole('/menu-layout')
  const writeCalls = () =>
    calls.filter(
      (call) =>
        call.path === '/iam/platform/navigation-layout' && (call.init?.method ?? 'GET') !== 'GET',
    )
  const accessLoads = () => calls.filter((call) => call.path === '/iam/platform/access').length
  return { wrapper, writes: writeCalls, accessLoads }
}

const rows = (wrapper: Awaited<ReturnType<typeof mountPage>>['wrapper']) =>
  wrapper.findAll('[data-testid^="navigation-node-"]').map((row) => row.text())

it('目录为全部平台管理页与菜单管理页，不按操作者本人权限裁剪', async () => {
  const { wrapper } = await mountPage([ok({ layout: null })])

  expect(rows(wrapper)).toEqual([
    '租户',
    '套餐',
    '应用注册表',
    '运营账号',
    '身份查询',
    '平台角色',
    '平台审计',
    '任务运行',
    '死信事件',
    '公告管理',
    '菜单管理',
  ])
})

it('保存整棵布局，保存后刷新当前 Operator 的权限与导航', async () => {
  const { wrapper, writes, accessLoads } = await mountPage([ok({ layout: saved })], [ok(saved)])
  expect(rows(wrapper).slice(0, 3)).toEqual([expect.stringContaining('运维'), '死信事件', '套餐'])
  const loadsBefore = accessLoads()

  await wrapper.get('[data-testid="navigation-save"]').trigger('click')
  await flushPromises()

  const { init } = writes()[0]!
  expect(init!.method).toBe('PUT')
  const body = JSON.parse(init!.body as string) as NavigationLayout
  expect(body.nodes.slice(0, 2)).toEqual(saved.nodes)
  expect(body.nodes.at(-1)).toEqual({ page: 'menu-layout' })
  expect(accessLoads()).toBe(loadsBefore + 1)
  expect(document.body.textContent).toContain('已保存')
})

it('保存失败时用消息提示，不刷新权限', async () => {
  const { wrapper, accessLoads } = await mountPage([ok({ layout: null })], [failed()])
  const loadsBefore = accessLoads()

  await wrapper.get('[data-testid="navigation-save"]').trigger('click')
  await flushPromises()

  expect(document.body.textContent).toContain('服务暂时不可用')
  expect(accessLoads()).toBe(loadsBefore)
})

it('恢复默认须在确认弹窗中确认，之后按默认顺序显示并刷新导航', async () => {
  const { wrapper, writes, accessLoads } = await mountPage([ok({ layout: saved })], [ok(null)])
  const loadsBefore = accessLoads()

  await wrapper.get('[data-testid="navigation-reset"]').trigger('click')
  await flushPromises()
  expect(writes()).toHaveLength(0)
  expect(wrapper.text()).toContain('全体运营人员的导航将恢复默认顺序')

  await wrapper.get('[data-testid="menu-layout-reset-confirm"]').trigger('click')
  await flushPromises()

  expect(writes()[0]!.init!.method).toBe('DELETE')
  expect(rows(wrapper)[0]).toBe('租户')
  expect(accessLoads()).toBe(loadsBefore + 1)
})

it('读取失败时显示错误，可以重试', async () => {
  const { wrapper } = await mountPage([failed(), ok({ layout: null })])

  expect(wrapper.text()).toContain('服务暂时不可用')
  expect(wrapper.find('[data-testid="navigation-save"]').exists()).toBe(false)

  await wrapper.get('[data-testid="menu-layout-retry"]').trigger('click')
  await flushPromises()

  expect(wrapper.text()).not.toContain('服务暂时不可用')
  expect(rows(wrapper)).toContain('菜单管理')
})
