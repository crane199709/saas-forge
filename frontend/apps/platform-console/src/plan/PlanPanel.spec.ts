import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElInputNumber, ElSelect } from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import PlanPanel from './PlanPanel.vue'
import { catalogTree as catalog } from './catalog-fixture'
import { createPlanApi } from './plan-api'

afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

const basic = {
  id: 'p1',
  code: 'basic',
  name: '基础版',
  permissions: ['order:read', 'legacy:report'],
  subApps: ['demo'],
  memberQuota: 5,
  storageQuotaMb: null,
  tokenQuota: null,
  updatedAt: 'x',
}
// SDK 按当前源解析请求地址，断言只比较路径与查询串
function path(url: string) {
  const parsed = new URL(url, location.origin)
  parsed.pathname = parsed.pathname.replace(/^\/api(?=\/)/, '')
  return parsed.pathname + parsed.search
}

function ok(data: unknown, httpStatus = 200) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus, data, traceId: 't' }), {
    status: httpStatus,
  })
}

function mountPanel(subApps: string[] = ['crm', 'demo']) {
  localStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '运营' }),
  )
  return mount(PlanPanel, {
    props: { api: createPlanApi(createPlatformClient(localStorage)), subApps },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}

function testid(id: string) {
  return document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement
}

const input = (id: string) => testid(id)?.querySelector('input') as HTMLInputElement

async function click(id: string) {
  input(id).click()
  await flushPromises()
}

async function chooseSubApps(wrapper: ReturnType<typeof mountPanel>, subApps: string[]) {
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', subApps)
  await flushPromises()
}

async function edit(wrapper: ReturnType<typeof mountPanel>, code: string) {
  await wrapper.get(`[data-testid="plan-edit-${code}"]`).trigger('click')
  await flushPromises()
  return document.body.querySelector('.el-drawer form') as HTMLFormElement
}

const submitted = (fetcher: ReturnType<typeof vi.fn>) =>
  JSON.parse(fetcher.mock.calls.at(-2)![1].body) as { permissions: string[]; subApps: string[] }

it('列出套餐，新建时先选 Sub-App，再按页面勾选其权限码', async () => {
  const created = {
    ...basic,
    id: 'p2',
    code: 'pro',
    name: '专业版',
    permissions: ['order:delete', 'order:read', 'report:sync'],
  }
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([basic]))
    .mockResolvedValueOnce(ok(catalog))
    .mockResolvedValueOnce(ok(created, 201))
    .mockResolvedValueOnce(ok([basic, created]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()

  expect(path(fetcher.mock.calls[0][0])).toBe('/platform/plans')
  expect(wrapper.text()).toContain('基础版')
  expect(wrapper.text()).toContain('2 项')
  // 存储容量为 null 即不限
  expect(wrapper.text()).toContain('不限')
  expect(wrapper.emitted('loaded')![0]).toEqual([[basic]])

  await wrapper.get('[data-testid="plan-create"]').trigger('click')
  await flushPromises()
  expect(path(fetcher.mock.calls[1][0])).toBe('/platform/plans/permission-tree')
  const form = document.body.querySelector('.el-drawer form') as HTMLFormElement
  for (const [name, value] of [
    ['code', 'pro'],
    ['name', '专业版'],
  ]) {
    const input = form.querySelector(`input[name="${name}"]`) as HTMLInputElement
    input.value = value!
    input.dispatchEvent(new Event('input'))
  }
  // 未选 Sub-App 时不显示 demo 的权限码，功能权限区始终显示
  expect(form.textContent).toContain('未选择子应用，没有可勾选的页面')
  expect(testid('page-demo.overview')).toBeNull()
  expect(testid('perm-report:sync')).not.toBeNull()

  await chooseSubApps(wrapper, ['demo'])
  expect(form.textContent).not.toContain('未选择子应用')
  expect(
    [...form.querySelectorAll('[data-testid^="menu-"]')].map((node) => node.textContent),
  ).toEqual(['订单概览', '订单附件'])
  expect(
    [...form.querySelectorAll('[data-testid^="page-"], [data-testid^="action-"]')].map((node) =>
      node.getAttribute('data-testid'),
    ),
  ).toEqual([
    'page-demo.overview',
    'action-demo.overview-order:create',
    'action-demo.overview-order:update',
    'action-demo.overview-order:delete',
    'action-demo.overview-order:import',
    'action-demo.overview-order:export',
    'page-demo.attachments',
  ])
  // 分组节点不显示，只选一个 Sub-App 时不标注编码
  expect(testid('menu-demo')).toBeNull()
  expect(form.textContent).toContain('与「订单附件」共用权限，会一起显示')

  await click('action-demo.overview-order:delete')
  expect(input('page-demo.overview').checked).toBe(true)
  expect(input('page-demo.attachments').checked).toBe(true)
  await click('perm-report:sync')
  form.dispatchEvent(new Event('submit'))
  await flushPromises()

  expect(path(fetcher.mock.calls[2][0])).toBe('/platform/plans')
  expect(fetcher.mock.calls[2][1].method).toBe('POST')
  expect(JSON.parse(fetcher.mock.calls[2][1].body)).toEqual({
    code: 'pro',
    name: '专业版',
    permissions: ['order:delete', 'order:read', 'report:sync'],
    subApps: ['demo'],
    memberQuota: null,
    storageQuotaMb: null,
    tokenQuota: null,
  })
  expect(wrapper.text()).toContain('专业版')
  expect(wrapper.emitted('loaded')![1]).toEqual([[basic, created]])
})

it('编辑时保留已下线的权限码，删除被拒时展示服务端提示', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([basic]))
    .mockResolvedValueOnce(ok(catalog))
    .mockResolvedValueOnce(ok(basic))
    .mockResolvedValueOnce(ok([basic]))
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: 'plan.inUse',
          httpStatus: 409,
          userTip: '仍有租户使用该套餐',
          traceId: 'trace-2',
        }),
        { status: 409 },
      ),
    )
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()

  await wrapper.get('[data-testid="plan-edit-basic"]').trigger('click')
  await flushPromises()
  const form = document.body.querySelector('.el-drawer form') as HTMLFormElement
  expect((form.querySelector('input[name="code"]') as HTMLInputElement).disabled).toBe(true)
  expect(form.textContent).toContain('已从清单下线（保留）')
  expect(input('perm-legacy:report').checked).toBe(true)
  expect(input('page-demo.overview').checked).toBe(true)
  form.dispatchEvent(new Event('submit'))
  await flushPromises()

  expect(path(fetcher.mock.calls[2][0])).toBe('/platform/plans/p1')
  expect(fetcher.mock.calls[2][1].method).toBe('PUT')
  expect(JSON.parse(fetcher.mock.calls[2][1].body)).toEqual({
    name: '基础版',
    permissions: ['order:read', 'legacy:report'],
    subApps: ['demo'],
    memberQuota: 5,
    storageQuotaMb: null,
    tokenQuota: null,
  })

  await wrapper.get('[data-testid="plan-delete-basic"]').trigger('click')
  await flushPromises()
  expect(path(fetcher.mock.calls[4][0])).toBe('/platform/plans/p1')
  expect(fetcher.mock.calls[4][1].method).toBe('DELETE')
  expect(wrapper.get('[role="alert"]').text()).toContain('仍有租户使用该套餐')
  expect(wrapper.text()).toContain('trace-2')
})

const pro = {
  ...basic,
  id: 'p3',
  code: 'pro',
  name: '专业版',
  permissions: ['customer:read', 'order:export', 'order:read', 'report:sync'],
  subApps: ['crm', 'demo'],
}

it('展示每月 Token 配额，编辑后保存，清空后按不限提交', async () => {
  const limited = { ...basic, tokenQuota: 5_000_000_000 }
  const fetcher = vi.fn().mockImplementation((_url: string, init?: RequestInit) => {
    if (init?.method === 'PUT') return Promise.resolve(ok(limited))
    if (String(_url).includes('permission-tree')) return Promise.resolve(ok(catalog))
    return Promise.resolve(ok([limited]))
  })
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  expect(wrapper.text()).toContain('5000000000')
  let form = await edit(wrapper, 'basic')
  const field = wrapper
    .findAllComponents(ElInputNumber)
    .find((field) => field.attributes('data-testid') === 'plan-token-quota')!
  expect(input('plan-token-quota').value).toBe('5000000000')
  field.vm.$emit('update:modelValue', 2_000_000)
  form.dispatchEvent(new Event('submit'))
  await flushPromises()
  expect(
    JSON.parse(fetcher.mock.calls.find((call) => call[1]?.method === 'PUT')![1].body),
  ).toMatchObject({ tokenQuota: 2_000_000 })

  form = await edit(wrapper, 'basic')
  wrapper
    .findAllComponents(ElInputNumber)
    .find((field) => field.attributes('data-testid') === 'plan-token-quota')!
    .vm.$emit('update:modelValue', undefined)
  form.dispatchEvent(new Event('submit'))
  await flushPromises()
  const writes = fetcher.mock.calls.filter((call) => call[1]?.method === 'PUT')
  expect(JSON.parse(writes.at(-1)![1].body)).toMatchObject({ tokenQuota: null })
})

function editFetcher(plan: typeof basic) {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([plan]))
    .mockResolvedValueOnce(ok(catalog))
    .mockResolvedValueOnce(ok(plan))
    .mockResolvedValueOnce(ok([plan]))
  vi.stubGlobal('fetch', fetcher)
  return fetcher
}

it('取消 Sub-App 时移除只归属它的码、保留共用的码，重新选回时不勾选', async () => {
  const fetcher = editFetcher(pro)
  const wrapper = mountPanel()
  await flushPromises()
  const form = await edit(wrapper, 'pro')
  // 选了多个 Sub-App 时根节点旁显示编码
  expect(testid('menu-crm.customers').parentElement!.textContent).toContain('crm')
  expect(input('page-crm.customers').checked).toBe(true)
  expect(input('action-crm.customers-order:export').checked).toBe(true)

  await chooseSubApps(wrapper, ['demo'])
  expect(testid('page-crm.customers')).toBeNull()
  expect(input('action-demo.overview-order:export').checked).toBe(true)
  expect(form.textContent).not.toContain('所属子应用未选择')

  await chooseSubApps(wrapper, ['demo', 'crm'])
  expect(input('page-crm.customers').checked).toBe(false)
  form.dispatchEvent(new Event('submit'))
  await flushPromises()
  expect(submitted(fetcher)).toMatchObject({
    permissions: ['order:export', 'order:read', 'report:sync'],
    subApps: ['demo', 'crm'],
  })
})

it('存量 Plan 中所属 Sub-App 未选择的码单独列出、保存时不丢失，选上 Sub-App 后回到树中', async () => {
  const fetcher = editFetcher({ ...pro, subApps: ['demo'] })
  const wrapper = mountPanel()
  await flushPromises()
  const form = await edit(wrapper, 'pro')
  expect(form.textContent).toContain('所属子应用未选择（保留）')
  expect(testid('perm-customer:read').textContent).toBe('查看客户（customer:read）')
  expect(input('perm-customer:read').checked).toBe(true)
  // 共用的码仍归属已选的 demo，留在树中
  expect(testid('perm-order:export')).toBeNull()

  await chooseSubApps(wrapper, ['demo', 'crm'])
  expect(testid('perm-customer:read')).toBeNull()
  expect(input('page-crm.customers').checked).toBe(true)
  await chooseSubApps(wrapper, ['demo'])
  expect(testid('perm-customer:read')).toBeNull()
  await chooseSubApps(wrapper, [])
  // 取消 demo 只移除归属它的码，功能权限区的码不受影响
  expect(testid('perm-order:export')).toBeNull()
  expect(input('perm-report:sync').checked).toBe(true)
  form.dispatchEvent(new Event('submit'))
  await flushPromises()
  expect(submitted(fetcher)).toMatchObject({ permissions: ['report:sync'], subApps: [] })
})

it('未选区里的码保存时一并提交，可逐项取消', async () => {
  const fetcher = editFetcher({ ...pro, subApps: [] })
  const wrapper = mountPanel()
  await flushPromises()
  const form = await edit(wrapper, 'pro')
  expect(input('perm-order:export').checked).toBe(true)
  await click('perm-order:read')
  form.dispatchEvent(new Event('submit'))
  await flushPromises()
  expect(submitted(fetcher).permissions).toEqual(['customer:read', 'order:export', 'report:sync'])
})

it('读取授权树失败时提示错误，不打开对话框', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([basic]))
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: 'plan.catalogUnavailable',
          httpStatus: 503,
          userTip: '暂时无法读取权限码目录',
          traceId: 'trace-3',
        }),
        { status: 503 },
      ),
    )
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('[data-testid="plan-create"]').trigger('click')
  await flushPromises()
  expect(document.body.querySelector('.el-drawer form')).toBeNull()
  expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法读取权限码目录')
})
