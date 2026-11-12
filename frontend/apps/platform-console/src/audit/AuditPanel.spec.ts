import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import AuditPanel from './AuditPanel.vue'
import { createAuditApi, type AuditEvent } from './audit-api'

afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

const suspend: AuditEvent = {
  id: 'e1',
  occurredAt: '2026-10-29T08:00:00Z',
  category: 'operation',
  action: 'tenant.transition',
  actorId: 'o1',
  actorAccount: 'operator',
  actorName: '演示运营',
  resourceType: 'tenant',
  resourceId: 't1',
  outcome: 'failure',
  errorCode: 'tenant.illegalTransition',
  detail: {},
  service: 'forge-platform',
  traceId: null,
}

function ok(data: unknown, total: number) {
  const meta = { page: { number: 1, size: 20, totalElements: total, totalPages: 1 } }
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, meta, traceId: 't' }))
}

it('查询 Platform Realm 审计并按条件筛选', async () => {
  localStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '运营' }),
  )
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([suspend], 1))
    .mockResolvedValueOnce(ok([], 0))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mount(AuditPanel, {
    props: { api: createAuditApi(createPlatformClient(localStorage)) },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
  await flushPromises()

  expect(fetcher.mock.calls[0]![0]).toBe(
    `${location.origin}/api/iam/platform/audit/events?page=1&size=20`,
  )
  const text = wrapper.text()
  expect(text).toContain('演示运营（operator）')
  expect(text).toContain('tenant / t1')
  expect(text).toContain('失败')
  expect(text).toContain('tenant.illegalTransition')

  await wrapper.get('[data-testid="audit-resource-id"]').setValue('t1')
  await wrapper.get('[data-testid="audit-search"]').trigger('click')
  await flushPromises()

  expect(fetcher.mock.calls[1]![0]).toBe(
    `${location.origin}/api/iam/platform/audit/events?page=1&size=20&resourceId=t1`,
  )
  expect(wrapper.text()).toContain('没有符合条件的审计记录')
})
