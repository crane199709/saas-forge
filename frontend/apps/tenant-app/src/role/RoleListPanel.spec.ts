import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient } from '@forge/sdk'
import { i18n } from '../i18n'
import RoleListPanel from './RoleListPanel.vue'
import {
  createRoleApi,
  groupPermissions,
  type DataResource,
  type GrantablePermission,
  type Role,
} from './role-api'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

const permissions: GrantablePermission[] = [
  { code: 'order:approve', name: '审批订单', service: 'demo-service', group: '订单' },
  { code: 'order:read', name: '查看订单', service: 'demo-service', group: '订单' },
  { code: 'report:view', name: '查看报表', service: 'demo-service', group: null },
  { code: 'iam:role:manage', name: '管理角色', service: 'forge-iam', group: '租户管理' },
]

const dataResources: DataResource[] = [
  {
    code: 'order',
    name: '订单',
    service: 'demo-service',
    scopes: ['self', 'department', 'department_and_below', 'all'],
  },
]

const roles: Role[] = [
  {
    id: 'r-admin',
    code: 'tenant-admin',
    name: '租户管理员',
    builtin: true,
    owner: false,
    memberCount: 1,
    permissions: [],
    dataPolicies: [],
  },
  {
    id: 'r-viewer',
    code: 'order-viewer',
    name: '订单查看员',
    builtin: false,
    owner: false,
    memberCount: 0,
    permissions: ['order:read'],
    // retired 已从清单下线，不在编辑范围内
    dataPolicies: [
      { resource: 'order', scope: 'department' },
      { resource: 'retired', scope: 'all' },
    ],
  },
]

function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 't' }))
}

function mountPanel(fetcher: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'admin' }),
  )
  return mount(RoleListPanel, {
    props: {
      api: createRoleApi(createTenantClient(sessionStorage)),
      roles,
      permissions,
      dataResources,
    },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}

it('权限码按服务与分组归并', () => {
  expect(
    groupPermissions(permissions).map((group) => [
      group.service,
      group.group,
      group.permissions.length,
    ]),
  ).toEqual([
    ['demo-service', '订单', 2],
    ['demo-service', null, 1],
    ['forge-iam', '租户管理', 1],
  ])
})

it('预置角色不可编辑或删除，自定义角色展示授权名称', () => {
  const wrapper = mountPanel(vi.fn())

  expect(wrapper.get('[data-testid="role-tenant-admin"]').text()).toContain('全部权限')
  expect(wrapper.find('[data-testid="role-edit-tenant-admin"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="role-delete-tenant-admin"]').exists()).toBe(false)
  expect(wrapper.get('[data-testid="role-order-viewer"]').text()).toContain('查看订单')
  expect(wrapper.get('[data-testid="role-scopes-tenant-admin"]').text()).toBe('全部数据')
  expect(wrapper.get('[data-testid="role-scopes-order-viewer"]').text()).toBe('订单：本部门')
})

it('编辑角色时整体提交勾选的权限码', async () => {
  const fetcher = vi.fn().mockResolvedValue(ok(null))
  const wrapper = mountPanel(fetcher)

  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  // 勾选审批、取消查看
  ;(document.querySelector('[data-testid="perm-order:approve"] input') as HTMLElement).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="perm-order:read"] input') as HTMLElement).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()

  expect(fetcher).toHaveBeenCalledTimes(1)
  const [url, init] = fetcher.mock.calls[0]!
  expect(url).toBe(`${location.origin}/iam/roles/r-viewer/permissions`)
  expect(JSON.parse(init.body)).toEqual({ permissions: ['order:approve'] })
  expect(wrapper.emitted('changed')).toHaveLength(1)
})

it('编辑角色时提交改动后的数据范围，未配置的数据资源不提交', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(ok(null)))
  const wrapper = mountPanel(fetcher)

  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  ;(document.querySelector('[data-testid="scope-order-self"] input') as HTMLElement).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()

  const [url, init] = fetcher.mock.calls.at(-1)!
  expect(url).toBe(`${location.origin}/iam/roles/r-viewer/data-policies`)
  expect(init.method).toBe('PUT')
  expect(JSON.parse(init.body)).toEqual({ policies: [{ resource: 'order', scope: 'self' }] })

  // 改为未配置：整体替换为空，持有者对订单不可见
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  ;(document.querySelector('[data-testid="scope-order-none"] input') as HTMLElement).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()
  expect(JSON.parse(fetcher.mock.calls.at(-1)![1].body)).toEqual({ policies: [] })
})
