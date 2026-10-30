import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createTenantClient, tenantClientKey } from '@forge/sdk'
import { i18n } from '../i18n'
import AuditPage from './AuditPage.vue'
import type { AuditEvent } from './audit-api'

beforeEach(() => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'admin' }),
  )
})

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

const event = (id: string, overrides: Partial<AuditEvent> = {}): AuditEvent => ({
  id,
  occurredAt: '2026-10-29T08:00:00Z',
  category: 'operation',
  action: 'order.create',
  actorId: 'identity',
  actorAccount: 'alice',
  actorName: '爱丽丝',
  resourceType: 'order',
  resourceId: 'o-1',
  outcome: 'success',
  errorCode: null,
  detail: {},
  service: 'demo-service',
  traceId: null,
  ...overrides,
})

function ok(data: unknown, total: number) {
  const meta = { page: { number: 1, size: 20, totalElements: total, totalPages: 1 } }
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, meta, traceId: 't' }))
}

it('展示审计记录的操作人、对象与结果，按条件重新查询', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      ok(
        [
          event('a'),
          event('b', {
            category: 'login',
            action: 'auth.login',
            actorId: null,
            actorAccount: null,
            actorName: null,
            resourceType: null,
            resourceId: null,
            outcome: 'failure',
            errorCode: 'auth.failed',
          }),
        ],
        2,
      ),
    )
    .mockResolvedValueOnce(ok([], 0))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mount(AuditPage, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
    attachTo: document.body,
  })
  await flushPromises()

  expect(fetcher.mock.calls[0]![0]).toBe(`${location.origin}/iam/audit/events?page=1&size=20`)
  const success = wrapper.get('[data-testid="audit-a"]').text()
  expect(success).toContain('业务操作')
  expect(success).toContain('爱丽丝（alice）')
  expect(success).toContain('order / o-1')
  expect(success).toContain('成功')
  const failure = wrapper.get('[data-testid="audit-b"]').text()
  expect(failure).toContain('系统')
  expect(failure).toContain('失败')
  expect(failure).toContain('auth.failed')

  await wrapper.get('[data-testid="audit-actor"]').setValue(' alice ')
  await wrapper.get('[data-testid="audit-action"]').setValue('order.create')
  await wrapper.get('[data-testid="audit-search"]').trigger('click')
  await flushPromises()

  expect(fetcher.mock.calls[1]![0]).toBe(
    `${location.origin}/iam/audit/events?page=1&size=20&actor=alice&action=order.create`,
  )
  expect(wrapper.text()).toContain('没有符合条件的审计记录')
})
