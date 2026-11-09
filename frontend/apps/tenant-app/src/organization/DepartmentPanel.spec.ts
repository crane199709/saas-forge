import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import DepartmentPanel from './DepartmentPanel.vue'
import { createOrganizationApi } from './org-api'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
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

async function mountPanel(fetcher: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'm' }),
  )
  const wrapper = mount(DepartmentPanel, {
    props: { api: createOrganizationApi(createTenantClient(sessionStorage)), departments },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

it('在选中的部门下新建下级部门，完成后通知刷新', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValue(respond(201, { code: 'ok', data: { id: 'c', parentId: 'a' } }))
  const wrapper = await mountPanel(fetcher)
  expect(wrapper.text()).toContain('研发')

  await wrapper.get('[data-testid="dept-create-child-a"]').trigger('click')
  await flushPromises()
  await wrapper.get('input[data-testid="dept-name"]').setValue(' 市场 ')
  await wrapper.get('[data-testid="dept-save"]').trigger('click')
  await flushPromises()

  const [url, init] = fetcher.mock.calls[0]!
  expect(url).toBe(`${location.origin}/iam/org/departments`)
  expect(JSON.parse(init.body)).toEqual({ name: '市场', parentId: 'a' })
  expect(wrapper.emitted('changed')).toHaveLength(1)
})

it('编辑时只提交变化的字段，没有变化不发请求', async () => {
  const fetcher = vi.fn().mockImplementation(async () => respond(200, { code: 'ok', data: {} }))
  const wrapper = await mountPanel(fetcher)

  await wrapper.get('[data-testid="dept-edit-b"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="dept-save"]').trigger('click')
  await flushPromises()
  expect(fetcher).not.toHaveBeenCalled()

  await wrapper.get('[data-testid="dept-edit-b"]').trigger('click')
  await flushPromises()
  await wrapper.get('input[data-testid="dept-name"]').setValue('研发中心')
  await wrapper.get('[data-testid="dept-save"]').trigger('click')
  await flushPromises()
  expect(fetcher.mock.calls).toHaveLength(1)
  expect(fetcher.mock.calls[0]![0]).toBe(`${location.origin}/iam/org/departments/b`)
  expect(JSON.parse(fetcher.mock.calls[0]![1].body)).toEqual({ name: '研发中心' })
})

it('删除被拒绝时展示 userTip 与 traceId', async () => {
  const fetcher = vi.fn().mockResolvedValue(
    respond(409, {
      code: 'organization.departmentNotEmpty',
      errorMessage: 'not empty',
      userTip: '部门下还有子部门或成员，不能删除',
    }),
  )
  const wrapper = await mountPanel(fetcher)

  await wrapper.get('[data-testid="dept-delete-a"]').trigger('click')
  await flushPromises()

  expect(fetcher.mock.calls[0]![1].method).toBe('DELETE')
  const alert = wrapper.get('[role="alert"]')
  expect(alert.text()).toContain('部门下还有子部门或成员')
  expect(alert.text()).toContain('trace-dept')
  expect(wrapper.emitted('changed')).toBeUndefined()
})

it('已删除部门平铺显示，父部门已删除时保留记录并展示恢复受阻原因', async () => {
  const fetcher = vi.fn(async (_url: string, init?: RequestInit) =>
    init?.method === 'POST'
      ? respond(409, {
          code: 'resource.restoreBlocked',
          userTip: '请先恢复上级部门',
          errorMessage: 'parent deleted',
        })
      : respond(200, {
          code: 'ok',
          data: [
            {
              id: 'child',
              name: '已删除子部门',
              parentId: 'deleted-parent',
              path: 'old.child',
              updatedAt: null,
              updatedBy: 'identity-admin',
            },
          ],
        }),
  )
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="restore-view-deleted"]').trigger('click')
  await flushPromises()
  expect(wrapper.find('[data-testid="dept-create-root"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="dept-name"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('identity-admin')
  await wrapper.get('[data-testid="restore-child"]').trigger('click')
  await flushPromises()
  document.querySelector<HTMLButtonElement>('[data-testid="restore-confirm"]')!.click()
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('请先恢复上级部门')
  expect(wrapper.get('[role="alert"]').text()).toContain('trace-dept')
  expect(wrapper.find('[data-testid="restore-child"]').exists()).toBe(true)
  expect(wrapper.emitted('changed')).toBeUndefined()
  wrapper.unmount()
})
