import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElDrawer, ElSelect } from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import TenantPanel from './TenantPanel.vue'
import { createTenantApi } from './tenant-api'

enableAutoUnmount(afterEach)
afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

const tenant = {
  id: 't1',
  name: '示例租户',
  status: 'active',
  adminContact: 'owner@example.com',
  planId: 'p1',
  expiresAt: null,
  purgeAt: null,
  purgedAt: null,
  createdAt: '2026-10-28T00:00:00Z',
}
const lifecycle = {
  allowedTransitions: ['suspended', 'expired', 'deleted'],
  statusChanges: [
    {
      fromStatus: null,
      toStatus: 'active',
      reason: null,
      changedBy: 'o1',
      changedAt: '2026-10-28T00:00:00Z',
    },
  ],
}
const page = { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } }

// SDK 按当前源解析请求地址，断言只比较路径与查询串
function path(url: string) {
  const parsed = new URL(url, location.origin)
  parsed.pathname = parsed.pathname.replace(/^\/api(?=\/)/, '')
  return parsed.pathname + parsed.search
}

function ok(data: unknown, meta?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, meta, traceId: 't' }))
}

const plans = [
  {
    id: 'p1',
    code: 'basic',
    name: '基础版',
    permissions: ['order:read'],
    subApps: [],
    memberQuota: 5,
    storageQuotaMb: null,
    updatedAt: 'x',
  },
  {
    id: 'p2',
    code: 'pro',
    name: '专业版',
    permissions: ['order:read', 'order:approve'],
    subApps: ['demo'],
    memberQuota: null,
    storageQuotaMb: 1024,
    updatedAt: 'x',
  },
]

function mountPanel() {
  localStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '运营' }),
  )
  return mount(TenantPanel, {
    props: { api: createTenantApi(createPlatformClient(localStorage)), plans },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}

it('列出租户，打开详情展示各服务初始化状态，失败项可重试', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([tenant], page))
    .mockResolvedValueOnce(
      ok({
        tenant,
        ...lifecycle,
        initializations: [
          { service: 'demo-service', status: 'failed', error: 'boom', updatedAt: 'x' },
          { service: 'forge-iam', status: 'succeeded', error: null, updatedAt: 'x' },
        ],
      }),
    )
    .mockResolvedValueOnce(
      ok({ service: 'demo-service', status: 'pending', error: null, updatedAt: 'y' }),
    )
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()

  expect(path(fetcher.mock.calls[0][0])).toBe('/platform/tenants?page=1&size=20')
  expect(wrapper.text()).toContain('示例租户')
  expect(wrapper.text()).toContain('正常')

  await wrapper.get('tbody button').trigger('click')
  await flushPromises()
  expect(path(fetcher.mock.calls[1][0])).toBe('/platform/tenants/t1')
  const drawer = document.body.querySelector('.el-drawer') as HTMLElement
  expect(drawer.textContent).toContain('失败')
  expect(drawer.textContent).toContain('boom')
  expect(drawer.textContent).toContain('成功')

  const retry = [...drawer.querySelectorAll('button')].find((button) =>
    button.textContent?.includes('重试'),
  )
  expect(retry).toBeDefined()
  retry!.click()
  await flushPromises()
  expect(path(fetcher.mock.calls[2][0])).toBe(
    '/platform/tenants/t1/initializations/demo-service/retry',
  )
  expect(fetcher.mock.calls[2][1].method).toBe('POST')
  expect(drawer.textContent).toContain('进行中')
  expect(drawer.textContent).not.toContain('boom')
  // 只有失败项可以重试
  expect([...drawer.querySelectorAll('button')].some((b) => b.textContent?.includes('重试'))).toBe(
    false,
  )
})

it('创建租户后打开其详情并刷新列表', async () => {
  const created = {
    tenant,
    ...lifecycle,
    initializations: [{ service: 'demo-service', status: 'pending', error: null, updatedAt: 'x' }],
  }
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      ok([], { page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } }),
    )
    .mockResolvedValueOnce(ok(created))
    .mockResolvedValueOnce(ok([tenant], page))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()

  await wrapper.get('header button').trigger('click')
  await flushPromises()
  const form = document.body.querySelector('.el-dialog form') as HTMLFormElement
  const name = form.querySelector('input[name="name"]') as HTMLInputElement
  const contact = form.querySelector('input[name="adminContact"]') as HTMLInputElement
  name.value = '示例租户'
  name.dispatchEvent(new Event('input'))
  contact.value = 'owner@example.com'
  contact.dispatchEvent(new Event('input'))
  wrapper.findComponent(ElSelect).vm.$emit('update:modelValue', 'p1')
  await flushPromises()
  form.dispatchEvent(new Event('submit'))
  await flushPromises()

  expect(path(fetcher.mock.calls[1][0])).toBe('/platform/tenants')
  expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({
    name: '示例租户',
    adminContact: 'owner@example.com',
    status: 'active',
    planId: 'p1',
  })
  expect(wrapper.text()).toContain('示例租户')
  expect(wrapper.text()).toContain('基础版')
  expect((document.body.querySelector('.el-drawer') as HTMLElement).textContent).toContain('进行中')
})

function button(root: ParentNode, text: string) {
  return [...root.querySelectorAll('button, label')].find((element) =>
    element.textContent?.includes(text),
  ) as HTMLElement | undefined
}

it('在详情中按可迁移状态变更，展示变更历史；冷静期内可撤回删除', async () => {
  const deleted = {
    tenant: { ...tenant, status: 'deleted', purgeAt: '2026-11-27T00:00:00Z' },
    initializations: [],
    allowedTransitions: ['active'],
    statusChanges: [
      {
        fromStatus: 'active',
        toStatus: 'deleted',
        reason: '客户注销',
        changedBy: 'o1',
        changedAt: '2026-10-28T01:00:00Z',
      },
      ...lifecycle.statusChanges,
    ],
  }
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([tenant], page))
    .mockResolvedValueOnce(ok({ tenant, initializations: [], ...lifecycle }))
    .mockResolvedValueOnce(ok(deleted))
    .mockResolvedValueOnce(ok([deleted.tenant], page))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('tbody button').trigger('click')
  await flushPromises()
  const drawer = document.body.querySelector('.el-drawer') as HTMLElement
  // 只能选服务端给出的可迁移状态
  expect(button(drawer, '已归档')).toBeUndefined()
  expect(drawer.textContent).toContain('创建')

  button(drawer, '已删除')!.click()
  await flushPromises()
  expect(drawer.textContent).toContain('删除后进入冷静期')
  const reason = drawer.querySelector('input[name="reason"]') as HTMLInputElement
  reason.value = '客户注销'
  reason.dispatchEvent(new Event('input'))
  drawer.querySelector('form.transition')!.dispatchEvent(new Event('submit'))
  await flushPromises()

  expect(path(fetcher.mock.calls[2][0])).toBe('/platform/tenants/t1/transitions')
  expect(JSON.parse(fetcher.mock.calls[2][1].body)).toEqual({
    status: 'deleted',
    reason: '客户注销',
  })
  expect(drawer.textContent).toContain('冷静期结束')
  expect(drawer.textContent).toContain('正常 → 已删除')
  expect(drawer.textContent).toContain('客户注销')
  // 冷静期内唯一的迁移是撤回到删除前的状态
  expect(drawer.textContent).toContain('撤回删除，恢复为')
  expect(button(drawer, '撤回删除')).toBeDefined()
  expect(wrapper.text()).toContain('已删除')
})

it('在详情中更换套餐', async () => {
  const detail = { tenant, initializations: [], ...lifecycle }
  const upgraded = { ...detail, tenant: { ...tenant, planId: 'p2' } }
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([tenant], page))
    .mockResolvedValueOnce(ok(detail))
    .mockResolvedValueOnce(
      ok({
        lostPermissions: [],
        lostSubApps: [],
        memberCount: null,
        memberQuota: null,
        membersOverQuota: 0,
        affectedMemberships: 0,
      }),
    )
    .mockResolvedValueOnce(ok(upgraded))
    .mockResolvedValueOnce(ok([upgraded.tenant], page))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('tbody button').trigger('click')
  await flushPromises()
  const drawer = document.body.querySelector('.el-drawer') as HTMLElement
  expect(drawer.textContent).toContain('基础版')

  const select = wrapper
    .findAllComponents(ElSelect)
    .find((item) => item.attributes('data-testid') === 'tenant-change-plan')!
  select.vm.$emit('update:modelValue', 'p2')
  await flushPromises()
  drawer.querySelector('form.change-plan')!.dispatchEvent(new Event('submit'))
  await flushPromises()

  // 升级没有影响：预览后直接更换，不打断
  expect(path(fetcher.mock.calls[2][0])).toBe('/platform/tenants/t1/plan/impact?planId=p2')
  expect(document.querySelector('[data-testid="plan-impact"]')).toBeNull()
  expect(path(fetcher.mock.calls[3][0])).toBe('/platform/tenants/t1/plan')
  expect(fetcher.mock.calls[3][1].method).toBe('PUT')
  expect(JSON.parse(fetcher.mock.calls[3][1].body)).toEqual({ planId: 'p2' })
  expect(drawer.textContent).toContain('专业版')
  expect(wrapper.text()).toContain('专业版')
})

it('冷静期结束后不能再变更状态', async () => {
  const purged = {
    tenant: {
      ...tenant,
      status: 'deleted',
      purgeAt: '2026-10-01T00:00:00Z',
      purgedAt: '2026-10-01T00:01:00Z',
    },
    initializations: [],
    allowedTransitions: [],
    statusChanges: [],
  }
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(ok([purged.tenant], page))
      .mockResolvedValueOnce(ok(purged)),
  )
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('tbody button').trigger('click')
  await flushPromises()

  const drawer = document.body.querySelector('.el-drawer') as HTMLElement
  expect(drawer.textContent).toContain('冷静期已结束')
  expect(drawer.textContent).toContain('数据清理开始')
  expect(drawer.querySelector('form.transition')).toBeNull()
})

it('展示服务端的错误提示', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: 'system.unavailable',
          httpStatus: 503,
          userTip: '服务暂不可用',
          traceId: 'trace-1',
        }),
        { status: 503 },
      ),
    ),
  )
  const wrapper = mountPanel()
  await flushPromises()

  expect(wrapper.get('[role="alert"]').text()).toContain('服务暂不可用')
  expect(wrapper.text()).toContain('trace-1')
})

const downgrade = {
  lostPermissions: ['order:approve'],
  lostSubApps: ['demo'],
  memberCount: 7,
  memberQuota: 5,
  membersOverQuota: 2,
  affectedMemberships: 3,
}

// 打开详情并选好新套餐后提交；返回抽屉和记录请求的 fetcher，供各用例断言
async function submitPlanChange(
  impact: () => Response | Promise<Response>,
  mounted?: { current: VueWrapper | null },
) {
  const detail = { tenant, initializations: [], ...lifecycle }
  const fetcher = vi.fn().mockImplementation((url: string, init: RequestInit) => {
    const target = path(url)
    if (target.includes('/plan/impact')) return Promise.resolve(impact())
    if (target === '/platform/tenants/t1' || init.method === 'PUT')
      return Promise.resolve(ok(detail))
    return Promise.resolve(ok([tenant], page))
  })
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  if (mounted) mounted.current = wrapper
  await flushPromises()
  await wrapper.get('tbody button').trigger('click')
  await flushPromises()
  const drawer = document.body.querySelector('.el-drawer') as HTMLElement
  const select = wrapper
    .findAllComponents(ElSelect)
    .find((item) => item.attributes('data-testid') === 'tenant-change-plan')!
  select.vm.$emit('update:modelValue', 'p1')
  await flushPromises()
  drawer.querySelector('form.change-plan')!.dispatchEvent(new Event('submit'))
  await flushPromises()
  return fetcher
}

const planWrites = (fetcher: ReturnType<typeof vi.fn>) =>
  fetcher.mock.calls.filter(([, init]) => init?.method === 'PUT')

const planDialog = () => document.querySelector('[data-testid="plan-impact"]')

it('创建失败显示在创建窗口内，重新打开并成功创建后清除旧错误', async () => {
  let attempts = 0
  vi.stubGlobal(
    'fetch',
    vi.fn((_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        if (++attempts === 1)
          return Promise.resolve(
            new Response(
              JSON.stringify({
                code: 'validation.failed',
                httpStatus: 400,
                userTip: '管理员联系方式不可用',
                traceId: 'create-failed',
              }),
              { status: 400 },
            ),
          )
        return Promise.resolve(ok({ tenant, ...lifecycle, initializations: [] }))
      }
      return Promise.resolve(ok([tenant], page))
    }),
  )
  const wrapper = mountPanel()
  await flushPromises()
  button(wrapper.element, '创建租户')!.click()
  await flushPromises()
  for (const [id, value] of [
    ['tenant-name', '示例租户'],
    ['tenant-admin-contact', 'owner@example.com'],
  ]) {
    const input = document.getElementById(id!) as HTMLInputElement
    input.value = value!
    input.dispatchEvent(new Event('input'))
  }
  const select = wrapper
    .findAllComponents(ElSelect)
    .find((item) => item.attributes('data-testid') === 'tenant-plan')!
  select.vm.$emit('update:modelValue', 'p1')
  await flushPromises()
  document.querySelector('.el-dialog form')!.dispatchEvent(new Event('submit'))
  await flushPromises()
  expect(document.querySelector('.el-dialog [role="alert"]')?.textContent).toContain(
    '管理员联系方式不可用',
  )

  ;(document.querySelector('.el-dialog__headerbtn') as HTMLElement).click()
  await flushPromises()
  expect(document.querySelector('.tenant-panel > .el-alert--error')).toBeNull()
  button(wrapper.element, '创建租户')!.click()
  await flushPromises()
  expect(document.body.textContent).not.toContain('create-failed')
  document.querySelector('.el-dialog form')!.dispatchEvent(new Event('submit'))
  await flushPromises()
  expect(attempts).toBe(2)
  expect(document.body.textContent).not.toContain('管理员联系方式不可用')
  expect(document.querySelector('.el-drawer')?.textContent).toContain('示例租户')
})

it('初始化重试失败显示在详情内，再次重试成功后清除错误', async () => {
  let attempts = 0
  const detail = {
    tenant,
    ...lifecycle,
    initializations: [{ service: 'demo-service', status: 'failed', error: 'boom', updatedAt: 'x' }],
  }
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        if (++attempts === 1)
          return Promise.resolve(
            new Response(
              JSON.stringify({
                code: 'system.unavailable',
                httpStatus: 503,
                userTip: '初始化服务暂不可用',
                traceId: 'retry-failed',
              }),
              { status: 503 },
            ),
          )
        return Promise.resolve(
          ok({ service: 'demo-service', status: 'pending', error: null, updatedAt: 'y' }),
        )
      }
      return Promise.resolve(path(url) === '/platform/tenants/t1' ? ok(detail) : ok([tenant], page))
    }),
  )
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('tbody button').trigger('click')
  await flushPromises()
  const drawer = document.querySelector('.el-drawer')!
  button(drawer, '重试')!.click()
  await flushPromises()
  expect(drawer.querySelector('[role="alert"]')?.textContent).toContain('初始化服务暂不可用')
  button(drawer, '重试')!.click()
  await flushPromises()
  expect(attempts).toBe(2)
  expect(document.body.textContent).not.toContain('retry-failed')
  expect(drawer.textContent).toContain('进行中')
})

async function clickPlan(testid: string) {
  ;(document.querySelector(`[data-testid="${testid}"]`) as HTMLElement).click()
  await flushPromises()
}

it('降级有影响时列出失去的权限、Sub-App 与成员差额，确认后才更换', async () => {
  const fetcher = await submitPlanChange(() => ok(downgrade))

  expect(planWrites(fetcher)).toEqual([])
  const text = planDialog()!.textContent!
  expect(document.querySelector('.el-dialog__title')!.textContent).toBe('确认更换为 基础版')
  expect(text).toContain('order:approve')
  expect(text).toContain('demo')
  expect(text).toContain('当前 7 名成员，新套餐上限 5 名，超出 2 名')
  expect(text).toContain('3 名成员')

  await clickPlan('plan-impact-confirm')

  expect(planWrites(fetcher)).toHaveLength(1)
  expect(path(planWrites(fetcher)[0]![0])).toBe('/platform/tenants/t1/plan')
  expect(planDialog()).toBeNull()
})

it('取消确认时不更换套餐', async () => {
  const fetcher = await submitPlanChange(() => ok(downgrade))

  await clickPlan('plan-impact-cancel')

  expect(planWrites(fetcher)).toEqual([])
  expect(planDialog()).toBeNull()
})

it('只有配额降低且未超出时不打断更换', async () => {
  const fetcher = await submitPlanChange(() =>
    ok({
      lostPermissions: [],
      lostSubApps: [],
      memberCount: 3,
      memberQuota: 5,
      membersOverQuota: 0,
      affectedMemberships: 0,
    }),
  )

  expect(planDialog()).toBeNull()
  expect(planWrites(fetcher)).toHaveLength(1)
})

it('预览返回 503 时提示失败而不是零影响，由用户决定继续更换', async () => {
  const fetcher = await submitPlanChange(
    () =>
      new Response(
        JSON.stringify({
          code: 'plan.impactUnavailable',
          httpStatus: 503,
          userTip: '暂时无法读取成员数据',
          traceId: 'trace-plan',
        }),
        { status: 503 },
      ),
  )

  expect(planWrites(fetcher)).toEqual([])
  const text = planDialog()!.textContent!
  expect(text).toContain('无法获取影响预览')
  expect(text).toContain('暂时无法读取成员数据')
  expect(text).toContain('trace-plan')
  expect(text).not.toContain('0 名成员')
  const confirm = document.querySelector('[data-testid="plan-impact-confirm"]') as HTMLElement
  expect(confirm.textContent).toContain('仍要更换')

  await clickPlan('plan-impact-confirm')

  expect(planWrites(fetcher)).toHaveLength(1)
})

it('预览失败时取消不更换套餐', async () => {
  const fetcher = await submitPlanChange(() => new Response('oops', { status: 500 }))

  await clickPlan('plan-impact-cancel')

  expect(planWrites(fetcher)).toEqual([])
})

it('等待预览期间关闭详情后，不再弹出确认也不更换', async () => {
  let resolve!: (response: Response) => void
  const wrapper = { current: null as VueWrapper | null }
  const fetcher = await submitPlanChange(
    () => new Promise<Response>((done) => (resolve = done)),
    wrapper,
  )
  // 测试环境桩掉了 Transition，抽屉的 close 事件要直接触发
  wrapper.current!.findComponent(ElDrawer).vm.$emit('close')
  await flushPromises()

  resolve(ok(downgrade))
  await flushPromises()

  expect(planDialog()).toBeNull()
  expect(planWrites(fetcher)).toEqual([])
})

it('清除租户 IP 允许列表必须确认并填写原因，取消不写入', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([tenant], page))
    .mockResolvedValueOnce(ok({ tenant, ...lifecycle, initializations: [] }))
    .mockResolvedValueOnce(ok(null))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '详情')!
    .trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="ip-clear-open"]').trigger('click')
  await flushPromises()
  const confirm = () =>
    document.querySelector<HTMLButtonElement>('[data-testid="ip-clear-confirm"]')!
  expect(confirm().disabled).toBe(true)
  const input = document.querySelector<HTMLTextAreaElement>('#ip-clear-reason')!
  input.value = '   '
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  expect(confirm().disabled).toBe(true)
  input.value = '  管理员误配置，申请紧急恢复  '
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  confirm().click()
  await flushPromises()
  expect(path(fetcher.mock.calls[2]![0])).toBe('/iam/platform/tenants/t1/ip-allowlist/clear')
  expect(JSON.parse(fetcher.mock.calls[2]![1].body)).toEqual({
    reason: '管理员误配置，申请紧急恢复',
  })
  expect(wrapper.text()).toContain('租户 IP 允许列表已清除')
  wrapper.unmount()
})
