import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@forge/sdk'
import { i18n } from '../i18n'
import TenantPanel from './TenantPanel.vue'
import { createTenantApi } from './tenant-api'

afterEach(() => {
  sessionStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

const tenant = {
  id: 't1',
  name: '示例租户',
  status: 'active',
  adminContact: 'owner@example.com',
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
  return parsed.pathname + parsed.search
}

function ok(data: unknown, meta?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, meta, traceId: 't' }))
}

function mountPanel() {
  sessionStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '运营' }),
  )
  return mount(TenantPanel, {
    props: { api: createTenantApi(createPlatformClient(sessionStorage)) },
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
  form.dispatchEvent(new Event('submit'))
  await flushPromises()

  expect(path(fetcher.mock.calls[1][0])).toBe('/platform/tenants')
  expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({
    name: '示例租户',
    adminContact: 'owner@example.com',
    status: 'active',
  })
  expect(wrapper.text()).toContain('示例租户')
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
