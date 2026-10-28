import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient } from '@forge/sdk'
import { i18n } from '../i18n'
import RoleMemberPanel from './RoleMemberPanel.vue'
import { createRoleApi, type Role } from './role-api'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

const role = (id: string, code: string, name: string, owner = false): Role => ({
  id,
  code,
  name,
  builtin: owner,
  owner,
  memberCount: 0,
  permissions: [],
})

function ok(data: unknown, meta?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, meta, traceId: 't' }))
}

it('Owner 不可分配；保存时提交多选的其余角色', async () => {
  const page = { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } }
  const member = {
    membershipId: 'm1',
    account: 'alice',
    displayName: 'Alice',
    status: 'active',
    roleIds: ['owner', 'viewer'],
  }
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([member], page))
    .mockResolvedValueOnce(ok(null))
    .mockResolvedValueOnce(ok([member], page))
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'admin' }),
  )
  const wrapper = mount(RoleMemberPanel, {
    props: {
      api: createRoleApi(createTenantClient(sessionStorage)),
      roles: [
        role('owner', 'owner', '所有者', true),
        role('viewer', 'order-viewer', '订单查看员'),
        role('approver', 'approver', '审批员'),
      ],
    },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
  await flushPromises()
  expect(wrapper.get('[data-testid="role-member-m1"]').text()).toContain('所有者、订单查看员')

  await wrapper.get('[data-testid="role-member-edit-m1"]').trigger('click')
  await flushPromises()
  expect(document.querySelector('[data-testid="role-option-owner"]')).toBeNull()
  ;(document.querySelector('[data-testid="role-option-approver"] input') as HTMLElement).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="role-member-save"]') as HTMLElement).click()
  await flushPromises()

  const [url, init] = fetcher.mock.calls[1]!
  expect(url).toBe(`${location.origin}/iam/roles/memberships/m1`)
  expect(JSON.parse(init.body)).toEqual({ roleIds: ['viewer', 'approver'] })
  expect(wrapper.emitted('changed')).toHaveLength(1)
})
