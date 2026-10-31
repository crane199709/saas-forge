import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient, tenantClientKey } from '@crane199709/sdk'
import RolePage from './RolePage.vue'
import { i18n } from '../i18n'
import RoleListPanel from './RoleListPanel.vue'
import {
  createRoleApi,
  groupPermissions,
  type ConditionJson,
  type ConditionOptions,
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
    attributes: [
      { name: 'resource.ownerId', label: '归属成员', type: 'uuid', multiValued: false },
      { name: 'resource.departmentPath', label: '归属部门', type: 'ltree', multiValued: false },
      { name: 'resource.amount', label: '金额', type: 'number', multiValued: false },
    ],
  },
]

const conditionOptions: ConditionOptions = {
  subject: [
    { name: 'subject.membershipId', label: null, type: 'uuid', multiValued: false },
    { name: 'subject.departmentPaths', label: null, type: 'ltree', multiValued: true },
    { name: 'subject.attributes.vip', label: 'VIP', type: 'boolean', multiValued: false },
  ],
  environment: [{ name: 'env.now', label: null, type: 'number', multiValued: false }],
  departments: [{ value: 'a', label: '总部' }],
  positions: [],
}

// 编辑器保存的「金额大于 1000 且属于本部门及下级」
const largeInMyDepartments: ConditionJson = {
  and: [
    { attr: 'resource.amount', op: 'gt', value: 1000 },
    { attr: 'resource.departmentPath', op: 'under', value: { ref: 'subject.departmentPaths' } },
  ],
}

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
  {
    id: 'r-large',
    code: 'large-orders',
    name: '大额订单',
    builtin: false,
    owner: false,
    memberCount: 1,
    permissions: ['order:read'],
    dataPolicies: [{ resource: 'order', scope: 'custom', condition: largeInMyDepartments }],
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
      permissionTree: { menus: [], others: permissions },
      dataResources,
      conditionOptions,
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

it('新建角色时授权与数据范围一次提交', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(ok(roles[1])))
  const wrapper = mountPanel(fetcher)

  await wrapper.get('[data-testid="role-create"]').trigger('click')
  await flushPromises()
  // 对话框渲染在 body 上；el-input 把 data-testid 透传到原生 input
  for (const [testid, value] of [
    ['role-code', 'order-auditor'],
    ['role-name', '订单审计'],
  ]) {
    const input = document.querySelector(`input[data-testid="${testid}"]`) as HTMLInputElement
    input.value = value!
    input.dispatchEvent(new Event('input'))
  }
  await flushPromises()
  ;(document.querySelector('[data-testid="perm-order:read"] input') as HTMLElement).click()
  await flushPromises()
  ;(
    document.querySelector('[data-testid="scope-order-department_and_below"] input') as HTMLElement
  ).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()

  expect(fetcher).toHaveBeenCalledTimes(1)
  const [url, init] = fetcher.mock.calls[0]!
  expect(url).toBe(`${location.origin}/iam/roles`)
  expect(JSON.parse(init.body)).toEqual({
    code: 'order-auditor',
    name: '订单审计',
    permissions: ['order:read'],
    dataPolicies: [{ resource: 'order', scope: 'department_and_below' }],
  })
})

it('自定义条件读回编辑器，修改取值后整体提交表达式树', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(ok(null)))
  const wrapper = mountPanel(fetcher)
  expect(wrapper.get('[data-testid="role-scopes-large-orders"]').text()).toBe('订单：自定义条件')

  await wrapper.get('[data-testid="role-edit-large-orders"]').trigger('click')
  await flushPromises()
  const editor = document.querySelector('[data-testid="condition-order"]')!
  expect(editor.querySelectorAll('[data-testid="condition-rule"]')).toHaveLength(2)
  // 未改动时不提交数据范围，持有者的 Token 不失效
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()
  expect(fetcher.mock.calls.map(([url]) => url)).not.toContain(
    `${location.origin}/iam/roles/r-large/data-policies`,
  )

  await wrapper.get('[data-testid="role-edit-large-orders"]').trigger('click')
  await flushPromises()
  const amount = document.querySelector(
    '[data-testid="condition-order"] [data-testid="condition-value"] input',
  ) as HTMLInputElement
  amount.value = '5000'
  amount.dispatchEvent(new Event('input'))
  amount.dispatchEvent(new Event('change'))
  await flushPromises()
  // 外层改为「任一满足」
  ;(document.querySelector('[data-testid="condition-logic-or"] input') as HTMLElement).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()

  const [url, init] = fetcher.mock.calls.at(-1)!
  expect(url).toBe(`${location.origin}/iam/roles/r-large/data-policies`)
  expect(JSON.parse(init.body)).toEqual({
    policies: [
      {
        resource: 'order',
        scope: 'custom',
        condition: {
          or: [
            { attr: 'resource.amount', op: 'gt', value: 5000 },
            {
              attr: 'resource.departmentPath',
              op: 'under',
              value: { ref: 'subject.departmentPaths' },
            },
          ],
        },
      },
    ],
  })
})

it('新选自定义条件时先填完条件才能保存', async () => {
  const wrapper = mountPanel(vi.fn())

  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  ;(document.querySelector('[data-testid="scope-order-custom"] input') as HTMLElement).click()
  await flushPromises()

  expect(
    document.querySelectorAll('[data-testid="condition-order"] [data-testid="condition-rule"]'),
  ).toHaveLength(1)
  expect(document.querySelector('[data-testid="condition-order"]')!.textContent).toContain('请填完')
  expect((document.querySelector('[data-testid="role-save"]') as HTMLButtonElement).disabled).toBe(
    true,
  )
  ;(document.querySelector('[data-testid="condition-add-group"]') as HTMLElement).click()
  await flushPromises()
  expect(
    document.querySelectorAll('[data-testid="condition-order"] [data-testid="condition-group"]'),
  ).toHaveLength(2)
})

it('预览以所选成员对样例数据求值并展示命中情况', async () => {
  const fetcher = vi.fn().mockImplementation((url: string) => {
    if (url.includes('/memberships'))
      return Promise.resolve(
        new Response(
          JSON.stringify({
            code: 'ok',
            httpStatus: 200,
            data: [
              {
                membershipId: 'm-1',
                account: 'alice',
                displayName: 'Alice',
                status: 'active',
                roleIds: [],
              },
            ],
            meta: { page: { number: 1, size: 100, totalElements: 1, totalPages: 1 } },
            traceId: 't',
          }),
        ),
      )
    return Promise.resolve(
      ok({ context: { 'subject.departmentPaths': ['a'], 'env.now': 1 }, matches: [true] }),
    )
  })
  const wrapper = mountPanel(fetcher)

  await wrapper.get('[data-testid="role-edit-large-orders"]').trigger('click')
  await flushPromises()
  ;(document.querySelector('[data-testid="preview-order"]') as HTMLElement).click()
  await flushPromises()
  const amount = document.querySelector(
    '[data-testid="preview-amount-0"] input',
  ) as HTMLInputElement
  amount.value = '2000'
  amount.dispatchEvent(new Event('input'))
  amount.dispatchEvent(new Event('change'))
  await flushPromises()
  ;(document.querySelector('[data-testid="preview-run"]') as HTMLElement).click()
  await flushPromises()

  const [url, init] = fetcher.mock.calls.at(-1)!
  expect(url).toBe(`${location.origin}/iam/roles/data-policies/preview`)
  expect(JSON.parse(init.body)).toEqual({
    policy: { resource: 'order', scope: 'custom', condition: largeInMyDepartments },
    membershipId: 'm-1',
    samples: [{ amount: 2000 }],
  })
  expect(document.querySelector('[data-testid="preview-result-0"]')!.textContent).toContain('命中')
  expect(document.querySelector('[data-testid="preview-context"]')!.textContent).toContain(
    '所在部门',
  )
})

it('编辑树中勾选操作自动授予页面，同码页面同步，取消页面移除操作', async () => {
  const fetcher = vi.fn().mockResolvedValue(ok(null))
  const wrapper = mountPanel(fetcher)
  await wrapper.setProps({
    permissionTree: {
      menus: [
        {
          code: 'demo',
          name: '示例',
          icon: null,
          permission: null,
          actions: [],
          children: [
            {
              code: 'orders',
              name: '订单页面',
              icon: null,
              permission: { code: 'order:read', name: '查看订单', sharedWith: ['attachments'] },
              actions: [{ code: 'order:create', name: '创建订单', sharedWith: [] }],
              children: [],
            },
            {
              code: 'attachments',
              name: '附件页面',
              icon: null,
              permission: { code: 'order:read', name: '查看订单', sharedWith: ['orders'] },
              actions: [],
              children: [],
            },
            { code: 'help', name: '帮助', icon: null, permission: null, actions: [], children: [] },
          ],
        },
      ],
      others: permissions.filter((p) => p.code !== 'order:read'),
    },
  })
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  const input = (id: string) =>
    document.querySelector(`[data-testid="${id}"] input`) as HTMLInputElement
  expect(input('menu-orders').checked).toBe(true)
  expect(input('menu-attachments').checked).toBe(true)
  expect(input('menu-help').disabled).toBe(true)
  expect(document.body.textContent).toContain('始终可见')
  expect(document.body.textContent).toContain('与「附件页面」共用权限，会一起显示')
  input('menu-orders').click()
  await flushPromises()
  input('action-orders-order:create').click()
  await flushPromises()
  expect(input('menu-orders').checked).toBe(true)
  expect(input('menu-attachments').checked).toBe(true)
  input('action-orders-order:create').click()
  await flushPromises()
  expect(input('menu-orders').checked).toBe(true)
  expect(input('action-orders-order:create').checked).toBe(false)
  input('action-orders-order:create').click()
  await flushPromises()
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()
  expect(JSON.parse(fetcher.mock.calls[0]![1].body).permissions.sort()).toEqual([
    'order:create',
    'order:read',
  ])
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  input('action-orders-order:create').click()
  await flushPromises()
  input('menu-attachments').click()
  await flushPromises()
  expect(input('action-orders-order:create').checked).toBe(false)
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()
  expect(JSON.parse(fetcher.mock.calls.at(-1)![1].body)).toEqual({ permissions: [] })
})

it('新建角色按分组全选，跨页面共用的页面及操作权限传递联动并去重提交', async () => {
  const fetcher = vi.fn().mockResolvedValue(ok(roles[1]))
  const wrapper = mountPanel(fetcher)
  await wrapper.setProps({
    permissionTree: {
      menus: [
        {
          code: 'app',
          name: '业务应用',
          icon: null,
          permission: null,
          actions: [],
          children: [
            {
              code: 'a',
              name: '页面 A',
              icon: null,
              permission: { code: 'a:read', name: '查看 A', sharedWith: [] },
              actions: [{ code: 'b:read', name: '操作 B', sharedWith: ['b'] }],
              children: [],
            },
            {
              code: 'b',
              name: '页面 B',
              icon: null,
              permission: { code: 'b:read', name: '查看 B', sharedWith: ['a'] },
              actions: [
                { code: 'b:create', name: '创建 B', sharedWith: [] },
                { code: 'b:delete', name: '删除 B', sharedWith: [] },
              ],
              children: [],
            },
            { code: 'help', name: '帮助', icon: null, permission: null, actions: [], children: [] },
          ],
        },
      ],
      others: permissions,
    },
  })
  await wrapper.get('[data-testid="role-create"]').trigger('click')
  await flushPromises()
  const input = (id: string) =>
    document.querySelector(`[data-testid="${id}"] input`) as HTMLInputElement
  for (const [id, value] of [
    ['role-code', 'tree-role'],
    ['role-name', '树角色'],
  ]) {
    const field = document.querySelector(`input[data-testid="${id}"]`) as HTMLInputElement
    field.value = value!
    field.dispatchEvent(new Event('input'))
  }
  input('menu-app').click()
  await flushPromises()
  expect(input('action-b-b:delete').checked).toBe(true)
  expect(input('menu-help').checked).toBe(false)
  input('menu-app').click()
  await flushPromises()
  input('action-b-b:create').click()
  await flushPromises()
  expect(input('menu-b').checked).toBe(true)
  expect(input('action-a-b:read').checked).toBe(true)
  expect(input('menu-a').checked).toBe(true)
  expect(input('action-b-b:delete').checked).toBe(false)
  expect(input('menu-app').indeterminate).toBe(true)
  input('menu-a').click()
  await flushPromises()
  expect(input('menu-b').checked).toBe(false)
  expect(input('action-b-b:create').checked).toBe(false)
  input('action-b-b:create').click()
  await flushPromises()
  input('perm-iam:role:manage').click()
  await flushPromises()
  expect(document.body.textContent).toContain('forge-iam · 租户管理')
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()
  const [url, init] = fetcher.mock.calls.at(-1)!
  expect(url).toBe(`${location.origin}/iam/roles`)
  expect(JSON.parse(init.body)).toEqual({
    code: 'tree-role',
    name: '树角色',
    permissions: ['b:create', 'b:read', 'a:read', 'iam:role:manage'],
    dataPolicies: [],
  })
})

it('角色页从授权树接口加载菜单及功能权限', async () => {
  const fetcher = vi.fn().mockImplementation((url: string) => {
    const path = new URL(url).pathname
    if (path === '/iam/roles') return Promise.resolve(ok(roles))
    if (path.endsWith('/permission-tree'))
      return Promise.resolve(
        ok({
          menus: [
            {
              code: 'help',
              name: '帮助中心',
              icon: null,
              permission: null,
              actions: [],
              children: [],
            },
          ],
          others: permissions,
        }),
      )
    if (path.endsWith('/data-resources')) return Promise.resolve(ok([]))
    if (path.endsWith('/condition-options')) return Promise.resolve(ok(conditionOptions))
    throw new Error(`Unexpected request: ${path}`)
  })
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'admin' }),
  )
  const wrapper = mount(RolePage, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey as symbol]: createTenantClient(sessionStorage) },
    },
    attachTo: document.body,
  })
  await flushPromises()
  await wrapper.get('[data-testid="role-create"]').trigger('click')
  await flushPromises()
  expect(document.querySelector('[data-testid="menu-help"]')?.textContent).toContain('帮助中心')
  expect(document.querySelector('[data-testid="perm-order:read"]')?.textContent).toContain(
    '查看订单',
  )
})
