import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient } from '@forge/sdk'
import { i18n } from '../i18n'
import MemberPanel from './MemberPanel.vue'
import { createOrganizationApi } from './org-api'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

const member = {
  membershipId: 'm1',
  account: 'alice',
  displayName: 'Alice',
  status: 'active',
  primaryDepartmentId: 'b',
  departmentIds: ['a'],
  positionIds: ['p1'],
  attributes: { level: 3 },
}

function ok(data: unknown, meta?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, meta, traceId: 't' }))
}

it('列出成员的部门与岗位，保存时整体提交分配', async () => {
  const page = { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } }
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
  const wrapper = mount(MemberPanel, {
    props: {
      api: createOrganizationApi(createTenantClient(sessionStorage)),
      departments: [
        { id: 'a', parentId: null, name: '总部', path: 'a' },
        { id: 'b', parentId: 'a', name: '研发', path: 'a.b' },
      ],
      positions: [{ id: 'p1', name: '经理' }],
      attributes: [{ id: 'x', code: 'level', name: '职级', type: 'number', required: true }],
    },
    global: { plugins: [i18n, ElementPlus] },
  })
  await flushPromises()

  const row = wrapper.get('[data-testid="member-m1"]')
  expect(row.text()).toContain('Alice')
  expect(row.text()).toContain('研发')
  expect(row.text()).toContain('经理')

  await wrapper.get('[data-testid="member-edit-m1"]').trigger('click')
  await wrapper.get('[data-testid="assignment-save"]').trigger('click')
  await flushPromises()

  const [url, init] = fetcher.mock.calls[1]!
  expect(url).toBe(`${location.origin}/iam/org/memberships/m1/assignment`)
  expect(JSON.parse(init.body)).toEqual({
    primaryDepartmentId: 'b',
    departmentIds: ['a'],
    positionIds: ['p1'],
    attributes: { level: 3 },
  })
  // 保存后重新读取列表
  expect(fetcher).toHaveBeenCalledTimes(3)
})
