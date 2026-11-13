import { flushPromises, mount, enableAutoUnmount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createAccessControl, createTenantClient, tenantClientKey } from '@crane199709/sdk'
import { i18n } from '../i18n'
import DictionaryPage from './DictionaryPage.vue'

const dictionary = {
  service: 'crm',
  code: 'source',
  name: '客户来源',
  extensible: true,
  items: [
    {
      code: 'web',
      name: '官网新版',
      sort: 3,
      custom: false,
      status: 'enabled',
      nameConflict: false,
    },
  ],
}
const versions = [
  {
    version: 2,
    summary: '修改 custom.expo',
    changedBy: '019a0110-0000-7000-8000-0000000002c2',
    changedByName: '审计员甲',
    changedAt: '2026-11-13T02:00:00Z',
  },
  { version: 1, summary: '新增 custom.expo', changedBy: null, changedAt: '2026-11-12T02:00:00Z' },
]
const oldItem = { code: 'custom.expo', custom: true, name: '展会', sort: 2, enabled: true }
const newItem = { ...oldItem, name: '行业展会', sort: 5, enabled: false }
const base = '/api/iam/reference-data/dictionaries/crm/source/history'

enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

async function openHistory(overrides: Record<string, unknown> = {}) {
  const responses = {
    '/api/iam/reference-data/dictionaries': [dictionary],
    [base]: versions,
    [`${base}/1`]: {
      ...versions[1],
      snapshot: [oldItem],
      current: dictionary,
      manifest: { code: 'source', name: '客户来源', items: [{ code: 'web', name: '官网默认' }] },
    },
    [`${base}/diff?from=1&to=2`]: {
      from: 1,
      to: 2,
      added: [{ ...newItem, code: 'custom.direct' }],
      removed: [oldItem],
      changed: [{ before: oldItem, after: newItem }],
    },
    ...overrides,
  }
  const fetcher = vi.fn(async (url: string) => {
    const value = await responses[url.replace(location.origin, '') as keyof typeof responses]
    return value instanceof Response
      ? value
      : new Response(
          JSON.stringify({ code: 'ok', data: value, httpStatus: 200, traceId: 'history-trace' }),
        )
  })
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r' }),
  )
  const client = createTenantClient(sessionStorage)
  const access = createAccessControl({
    api: vi
      .fn()
      .mockResolvedValue({ permissions: ['iam:dictionary-history:read'], menus: [], subApps: [] }),
  })
  await access.load()
  const wrapper = mount(DictionaryPage, {
    global: { plugins: [i18n, ElementPlus, access], provide: { [tenantClientKey]: client } },
  })
  await flushPromises()
  return { wrapper, fetcher }
}

it('审计员默认打开历史列表，查看完整覆盖快照与当前上下文，不显示修改操作', async () => {
  const { wrapper } = await openHistory()
  expect(wrapper.text()).toContain('修改 custom.expo')
  expect(wrapper.text()).toContain('新增 custom.expo')
  await wrapper.get('[data-testid="history-detail-1"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-testid="history-snapshot"]').text()).toContain('展会')
  expect(wrapper.get('[data-testid="history-current"]').text()).toContain('官网新版')
  expect(wrapper.get('[data-testid="history-manifest"]').text()).toContain('官网默认')
  await wrapper.get('#tab-items').trigger('click')
  expect(wrapper.find('[data-testid="new-item-create"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="item-edit-web"]').exists()).toBe(false)
})

it('变更人显示可识别姓名，身份 ID 只在有说明的提示中，系统变更明确标注', async () => {
  const { wrapper } = await openHistory({
    [base]: [
      ...versions,
      {
        version: 3,
        summary: '未知姓名的历史',
        changedBy: 'missing-identity',
        changedByName: null,
        changedAt: versions[0]!.changedAt,
      },
    ],
  })
  const list = wrapper.get('[data-testid="history-list"]')
  expect(list.text()).toContain('审计员甲')
  expect(list.text()).toContain('系统')
  expect(list.text()).toContain('姓名未知')
  expect(list.text()).not.toContain('missing-identity')
  expect(list.get('[title="身份 ID：missing-identity"]').text()).toBe('姓名未知')
  expect(list.text()).not.toContain('019a0110-0000-7000-8000-0000000002c2')
  expect(list.get('[title="身份 ID：019a0110-0000-7000-8000-0000000002c2"]').text()).toBe(
    '审计员甲',
  )
})

it('历史快照与当前值用状态标签区分启用、停用和下线', async () => {
  const { wrapper } = await openHistory({
    [`${base}/1`]: {
      ...versions[1],
      snapshot: [oldItem, { ...newItem, code: 'custom.disabled' }],
      current: {
        ...dictionary,
        items: [
          dictionary.items[0],
          { ...dictionary.items[0], code: 'custom.disabled', status: 'disabled' },
          { ...dictionary.items[0], code: 'retired', status: 'retired' },
        ],
      },
      manifest: null,
    },
  })
  await wrapper.get('[data-testid="history-detail-1"]').trigger('click')
  await flushPromises()
  const snapshot = wrapper.get('[data-testid="history-snapshot"]').findAll('.el-tag')
  expect(snapshot.map((tag) => tag.text())).toEqual(['启用', '停用'])
  expect(snapshot[0]!.classes()).toContain('el-tag--success')
  expect(snapshot[1]!.classes()).toContain('el-tag--info')
  const current = wrapper.get('[data-testid="history-current"]').findAll('.el-tag')
  expect(current.map((tag) => tag.text())).toEqual(['启用', '已停用', '已下线'])
  expect(current[0]!.classes()).toContain('el-tag--success')
  expect(current[1]!.classes()).toContain('el-tag--info')
  expect(current[2]!.classes()).toContain('el-tag--warning')
})

it('比较任意两个版本显示新增、移除和每个修改字段的前后值', async () => {
  const { wrapper, fetcher } = await openHistory()
  await wrapper.get('[data-testid="history-compare"]').trigger('click')
  await flushPromises()
  expect(fetcher.mock.calls.some(([url]) => url.endsWith('/diff?from=1&to=2'))).toBe(true)
  expect(wrapper.get('[data-testid="history-added"]').text()).toContain('custom.direct')
  expect(wrapper.get('[data-testid="history-removed"]').text()).toContain('展会')
  const changes = wrapper.get('[data-testid="history-changed"]').text()
  expect(changes).toContain('展会')
  expect(changes).toContain('行业展会')
  expect(changes).toContain('启用')
  expect(changes).toContain('停用')
  expect(changes).toContain('2')
  expect(changes).toContain('5')
})

it('历史请求失败时显示错误信息与追踪号', async () => {
  const { wrapper } = await openHistory({
    [base]: new Response(
      JSON.stringify({
        code: 'auth.forbidden',
        userTip: '没有历史查看权限',
        httpStatus: 403,
        traceId: 'denied-history',
      }),
      { status: 403 },
    ),
  })
  expect(wrapper.get('[role="alert"]').text()).toContain('没有历史查看权限')
  expect(wrapper.get('[role="alert"]').text()).toContain('denied-history')
})

it('历史为空时显示空状态，隐藏版本详情和比较入口', async () => {
  const { wrapper } = await openHistory({ [base]: [] })
  expect(wrapper.text()).toContain('暂无变更历史')
  expect(wrapper.find('[data-testid="history-compare"]').exists()).toBe(false)
})

it('更晚选择的版本保留在详情中，旧响应不能覆盖它', async () => {
  let resolveFirst!: (value: unknown) => void
  const first = new Promise((resolve) => {
    resolveFirst = resolve
  })
  const { wrapper } = await openHistory({
    [`${base}/1`]: first,
    [`${base}/2`]: { ...versions[0], snapshot: [newItem], current: dictionary, manifest: null },
  })
  await wrapper.get('[data-testid="history-detail-1"]').trigger('click')
  await wrapper.get('[data-testid="history-detail-2"]').trigger('click')
  await flushPromises()
  resolveFirst({ ...versions[1], snapshot: [oldItem], current: dictionary, manifest: null })
  await flushPromises()
  expect(wrapper.get('[data-testid="history-snapshot"]').text()).toContain('行业展会')
})
