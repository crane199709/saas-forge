import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import RoleMemberPanel from './RoleMemberPanel.vue'
import { createRoleApi, type Role } from './role-api'

enableAutoUnmount(afterEach)
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
  dataPolicies: [],
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
  expect(wrapper.get('[data-testid="role-member-roles-m1"]').text()).toContain('所有者、订单查看员')

  await wrapper.get('[data-testid="role-member-edit-m1"]').trigger('click')
  await flushPromises()
  expect(document.querySelector('[data-testid="role-option-owner"]')).toBeNull()
  ;(document.querySelector('[data-testid="role-option-approver"] input') as HTMLElement).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="role-member-save"]') as HTMLElement).click()
  await flushPromises()

  const [url, init] = fetcher.mock.calls[1]!
  expect(url).toBe(`${location.origin}/api/iam/roles/memberships/m1`)
  expect(JSON.parse(init.body)).toEqual({ roleIds: ['viewer', 'approver'] })
  expect(wrapper.emitted('changed')).toHaveLength(1)
})

it('分配角色失败后重新打开编辑，不显示上次错误', async () => {
  const member = {
    membershipId: 'm1',
    account: 'alice',
    displayName: 'Alice',
    status: 'active',
    roleIds: [],
  }
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(
        ok([member], { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } }),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            code: 'system.unavailable',
            httpStatus: 503,
            userTip: '分配失败',
            traceId: 'failed-assignment',
          }),
          { status: 503 },
        ),
      ),
  )
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r' }),
  )
  const wrapper = mount(RoleMemberPanel, {
    props: { api: createRoleApi(createTenantClient(sessionStorage)), roles: [] },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
  await flushPromises()
  await wrapper.get('[data-testid="role-member-edit-m1"]').trigger('click')
  await flushPromises()
  ;(document.querySelector('[data-testid="role-member-save"]') as HTMLElement).click()
  await flushPromises()
  expect(wrapper.text()).toContain('分配失败')
  ;(document.querySelector('.el-dialog__headerbtn') as HTMLElement).click()
  await flushPromises()
  await wrapper.get('[data-testid="role-member-edit-m1"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('分配失败')
  expect(wrapper.text()).not.toContain('failed-assignment')
})
