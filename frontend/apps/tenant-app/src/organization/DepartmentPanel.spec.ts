import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient } from '@forge/sdk'
import { i18n } from '../i18n'
import DepartmentPanel from './DepartmentPanel.vue'
import { createOrganizationApi } from './org-api'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

const departments = [
  { id: 'a', parentId: null, name: '总部', path: 'a' },
  { id: 'b', parentId: 'a', name: '研发', path: 'a.b' },
]

function respond(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify({ httpStatus: status, traceId: 'trace-dept', ...body }), {
    status,
  })
}

function mountPanel(fetcher: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'm' }),
  )
  return mount(DepartmentPanel, {
    props: { api: createOrganizationApi(createTenantClient(sessionStorage)), departments },
    global: { plugins: [i18n, ElementPlus] },
  })
}

it('在选中的部门下新建下级部门，完成后通知刷新', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValue(respond(201, { code: 'ok', data: { id: 'c', parentId: 'a' } }))
  const wrapper = mountPanel(fetcher)
  expect(wrapper.text()).toContain('研发')

  await wrapper.get('[data-testid="dept-node-a"]').trigger('click')
  await wrapper.get('input[data-testid="dept-name"]').setValue(' 市场 ')
  await wrapper.get('[data-testid="dept-create-child"]').trigger('click')
  await flushPromises()

  const [url, init] = fetcher.mock.calls[0]!
  expect(url).toBe(`${location.origin}/iam/org/departments`)
  expect(JSON.parse(init.body)).toEqual({ name: '市场', parentId: 'a' })
  expect(wrapper.emitted('changed')).toHaveLength(1)
})

it('删除被拒绝时展示 userTip 与 traceId', async () => {
  const fetcher = vi.fn().mockResolvedValue(
    respond(409, {
      code: 'organization.departmentNotEmpty',
      errorMessage: 'not empty',
      userTip: '部门下还有子部门或成员，不能删除',
    }),
  )
  const wrapper = mountPanel(fetcher)

  await wrapper.get('[data-testid="dept-node-a"]').trigger('click')
  await wrapper.get('[data-testid="dept-delete"]').trigger('click')
  await flushPromises()

  expect(fetcher.mock.calls[0]![1].method).toBe('DELETE')
  const alert = wrapper.get('[role="alert"]')
  expect(alert.text()).toContain('部门下还有子部门或成员')
  expect(alert.text()).toContain('trace-dept')
  expect(wrapper.emitted('changed')).toBeUndefined()
})
