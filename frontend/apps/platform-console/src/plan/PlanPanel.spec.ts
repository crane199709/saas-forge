import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@forge/sdk'
import { i18n } from '../i18n'
import PlanPanel from './PlanPanel.vue'
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
  updatedAt: 'x',
}
const catalog = [
  { code: 'order:read', name: '查看订单', service: 'demo-service', group: '订单' },
  { code: 'order:approve', name: '审批订单', service: 'demo-service', group: '订单' },
]

// SDK 按当前源解析请求地址，断言只比较路径与查询串
function path(url: string) {
  const parsed = new URL(url, location.origin)
  return parsed.pathname + parsed.search
}

function ok(data: unknown, httpStatus = 200) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus, data, traceId: 't' }), {
    status: httpStatus,
  })
}

function mountPanel() {
  localStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '运营' }),
  )
  return mount(PlanPanel, {
    props: { api: createPlanApi(createPlatformClient(localStorage)) },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}

function testid(id: string) {
  return document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement
}

it('列出套餐，新建时从权限码目录中选择 Feature Pack', async () => {
  const created = {
    ...basic,
    id: 'p2',
    code: 'pro',
    name: '专业版',
    permissions: ['order:approve'],
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
  expect(path(fetcher.mock.calls[1][0])).toBe('/platform/plans/permissions')
  const form = document.body.querySelector('.el-dialog form') as HTMLFormElement
  for (const [name, value] of [
    ['code', 'pro'],
    ['name', '专业版'],
  ]) {
    const input = form.querySelector(`input[name="${name}"]`) as HTMLInputElement
    input.value = value!
    input.dispatchEvent(new Event('input'))
  }
  expect(form.textContent).toContain('demo-service · 订单')
  ;(testid('plan-perm-order:approve').querySelector('input') as HTMLInputElement).click()
  await flushPromises()
  form.dispatchEvent(new Event('submit'))
  await flushPromises()

  expect(path(fetcher.mock.calls[2][0])).toBe('/platform/plans')
  expect(fetcher.mock.calls[2][1].method).toBe('POST')
  expect(JSON.parse(fetcher.mock.calls[2][1].body)).toEqual({
    code: 'pro',
    name: '专业版',
    permissions: ['order:approve'],
    subApps: [],
    memberQuota: null,
    storageQuotaMb: null,
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
  const form = document.body.querySelector('.el-dialog form') as HTMLFormElement
  expect((form.querySelector('input[name="code"]') as HTMLInputElement).disabled).toBe(true)
  expect(form.textContent).toContain('已从清单下线（保留）')
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
  })

  await wrapper.get('[data-testid="plan-delete-basic"]').trigger('click')
  await flushPromises()
  expect(path(fetcher.mock.calls[4][0])).toBe('/platform/plans/p1')
  expect(fetcher.mock.calls[4][1].method).toBe('DELETE')
  expect(wrapper.get('[role="alert"]').text()).toContain('仍有租户使用该套餐')
  expect(wrapper.text()).toContain('trace-2')
})
