import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElSelect } from 'element-plus'
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

// el-table 在列注册后的下一拍才渲染行，取到的 wrapper 须已完成首屏。
async function mountPanel(fetcher: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'admin' }),
  )
  const wrapper = mount(RoleListPanel, {
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
  await flushPromises()
  return wrapper
}

async function chooseScope(wrapper: VueWrapper, scope: string) {
  const select = wrapper
    .findAllComponents(ElSelect)
    .find((item) => item.attributes('data-testid') === 'scope-order')!
  select.vm.$emit('update:modelValue', scope)
  await flushPromises()
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

it('预置角色不可编辑或删除，自定义角色展示授权名称', async () => {
  const wrapper = await mountPanel(vi.fn())

  expect(wrapper.get('[data-testid="role-permissions-tenant-admin"]').text()).toContain('全部权限')
  expect(wrapper.find('[data-testid="role-edit-tenant-admin"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="role-delete-tenant-admin"]').exists()).toBe(false)
  expect(wrapper.get('[data-testid="role-permissions-order-viewer"]').text()).toContain('查看订单')
  expect(wrapper.get('[data-testid="role-scopes-tenant-admin"]').text()).toBe('全部数据')
  expect(wrapper.get('[data-testid="role-scopes-order-viewer"]').text()).toBe('订单：本部门')
})

it('编辑角色时整体提交勾选的权限码', async () => {
  const fetcher = noImpact()
  const wrapper = await mountPanel(fetcher)

  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  // 勾选审批、取消查看
  ;(document.querySelector('[data-testid="perm-order:approve"] input') as HTMLElement).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="perm-order:read"] input') as HTMLElement).click()
  await flushPromises()
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()

  expect(fetcher).toHaveBeenCalledTimes(2)
  const [url, init] = fetcher.mock.calls[1]!
  expect(url).toBe(`${location.origin}/api/iam/roles/r-viewer/permissions`)
  expect(JSON.parse(init.body)).toEqual({ permissions: ['order:approve'] })
  expect(wrapper.emitted('changed')).toHaveLength(1)
})

it('编辑角色时提交改动后的数据范围，未配置的数据资源不提交', async () => {
  const fetcher = noImpact()
  const wrapper = await mountPanel(fetcher)

  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await chooseScope(wrapper, 'self')
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()

  const [url, init] = fetcher.mock.calls.at(-1)!
  expect(url).toBe(`${location.origin}/api/iam/roles/r-viewer/data-policies`)
  expect(init.method).toBe('PUT')
  expect(JSON.parse(init.body)).toEqual({ policies: [{ resource: 'order', scope: 'self' }] })

  // 改为未配置：整体替换为空，持有者对订单不可见
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await chooseScope(wrapper, '')
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()
  expect(JSON.parse(fetcher.mock.calls.at(-1)![1].body)).toEqual({ policies: [] })
})

it('新建角色时授权与数据范围一次提交', async () => {
  const fetcher = vi.fn().mockImplementation(() => Promise.resolve(ok(roles[1])))
  const wrapper = await mountPanel(fetcher)

  await wrapper.get('[data-testid="role-create"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-testid="scope-order"] .el-select__placeholder').text()).toBe(
    '未配置（不可见）',
  )
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
  await chooseScope(wrapper, 'department_and_below')
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()

  expect(fetcher).toHaveBeenCalledTimes(1)
  const [url, init] = fetcher.mock.calls[0]!
  expect(url).toBe(`${location.origin}/api/iam/roles`)
  expect(JSON.parse(init.body)).toEqual({
    code: 'order-auditor',
    name: '订单审计',
    permissions: ['order:read'],
    dataPolicies: [{ resource: 'order', scope: 'department_and_below' }],
  })
})

it('自定义条件读回编辑器，修改取值后整体提交表达式树', async () => {
  const fetcher = noImpact()
  const wrapper = await mountPanel(fetcher)
  expect(wrapper.get('[data-testid="role-scopes-large-orders"]').text()).toBe('订单：自定义条件')

  await wrapper.get('[data-testid="role-edit-large-orders"]').trigger('click')
  await flushPromises()
  const editor = document.querySelector('[data-testid="condition-order"]')!
  expect(editor.querySelectorAll('[data-testid="condition-rule"]')).toHaveLength(2)
  // 未改动时不提交数据范围，持有者的 Token 不失效
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()
  expect(fetcher.mock.calls.map(([url]) => url)).not.toContain(
    `${location.origin}/api/iam/roles/r-large/data-policies`,
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
  expect(url).toBe(`${location.origin}/api/iam/roles/r-large/data-policies`)
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
  const wrapper = await mountPanel(vi.fn())

  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await chooseScope(wrapper, 'custom')

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
  const wrapper = await mountPanel(fetcher)

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
  expect(url).toBe(`${location.origin}/api/iam/roles/data-policies/preview`)
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
  const fetcher = noImpact()
  const wrapper = await mountPanel(fetcher)
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
              actions: [
                { code: 'order:create', name: '创建订单', sharedWith: [] },
                { code: 'order:delete', name: '删除订单', sharedWith: [] },
              ],
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
  input('menu-orders').click()
  await flushPromises()
  expect(input('action-orders-order:create').checked).toBe(true)
  expect(input('action-orders-order:delete').checked).toBe(true)
  input('action-orders-order:delete').click()
  await flushPromises()
  expect(input('menu-orders').checked).toBe(true)
  expect(input('action-orders-order:create').checked).toBe(true)
  expect(input('action-orders-order:delete').checked).toBe(false)
  input('menu-orders').click()
  await flushPromises()
  expect(input('action-orders-order:create').checked).toBe(false)
  input('action-orders-order:create').click()
  await flushPromises()
  expect(input('menu-orders').checked).toBe(true)
  expect(input('menu-attachments').checked).toBe(true)
  expect(input('action-orders-order:delete').checked).toBe(false)
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
  const wrapper = await mountPanel(fetcher)
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
  expect(document.body.textContent).toContain('租户管理')
  ;(document.querySelector('[data-testid="role-save"]') as HTMLElement).click()
  await flushPromises()
  const [url, init] = fetcher.mock.calls.at(-1)!
  expect(url).toBe(`${location.origin}/api/iam/roles`)
  expect(JSON.parse(init.body)).toEqual({
    code: 'tree-role',
    name: '树角色',
    permissions: ['b:create', 'b:read', 'a:read', 'iam:role:manage'],
    dataPolicies: [],
  })
})

it('角色页从授权树接口加载菜单及功能权限', async () => {
  const fetcher = vi.fn().mockImplementation((url: string) => {
    const path = new URL(url).pathname.replace(/^\/api(?=\/)/, '')
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

// 影响预览按 URL 区分：/impact 返回给定结果，其余写操作成功
function impactFetcher(impacts: { permissions?: Response; dataPolicies?: Response }) {
  return vi.fn().mockImplementation((url: string) => {
    if (url.endsWith('/permissions/impact')) return Promise.resolve(impacts.permissions!.clone())
    if (url.endsWith('/data-policies/impact')) return Promise.resolve(impacts.dataPolicies!.clone())
    return Promise.resolve(ok(null))
  })
}

const noImpact = () =>
  impactFetcher({
    permissions: ok({ affectedCount: 0, members: [], lostPermissions: [] }),
    dataPolicies: ok({ affectedCount: 0, members: [] }),
  })

const writes = (fetcher: ReturnType<typeof vi.fn>) =>
  fetcher.mock.calls.filter(([, init]) => init.method === 'PUT').map(([url]) => url as string)

const click = async (selector: string) => {
  ;(document.querySelector(selector) as HTMLElement).click()
  await flushPromises()
}

const impactDialog = () => document.querySelector('[data-testid="role-impact"]')

it('保存角色失败后关闭抽屉，再编辑另一个角色时不残留旧错误', async () => {
  const wrapper = await mountPanel(vi.fn().mockImplementation(() => Promise.resolve(failure())))
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="role-save"]')
  expect(document.body.textContent).toContain('服务暂不可用')
  expect(document.body.textContent).toContain('trace-preview')

  await click('.el-drawer__close-btn')
  await wrapper.get('[data-testid="role-edit-large-orders"]').trigger('click')
  await flushPromises()
  expect(document.body.textContent).not.toContain('服务暂不可用')
  expect(document.body.textContent).not.toContain('trace-preview')
})

it('权限变更有净影响时列出成员与失去的权限，确认后才提交', async () => {
  const fetcher = impactFetcher({
    permissions: ok({
      affectedCount: 25,
      members: [{ membershipId: 'm-1', account: 'alice', displayName: 'Alice' }],
      lostPermissions: ['order:read', 'legacy:code'],
    }),
  })
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="perm-order:read"] input')
  await click('[data-testid="role-save"]')

  const [url, init] = fetcher.mock.calls[0]!
  expect(url).toBe(`${location.origin}/api/iam/roles/r-viewer/permissions/impact`)
  expect(init.method).toBe('POST')
  expect(JSON.parse(init.body)).toEqual({ permissions: [] })
  expect(writes(fetcher)).toEqual([])
  expect(impactDialog()!.textContent).toContain('25')
  expect(impactDialog()!.textContent).toContain('Alice')
  expect(impactDialog()!.textContent).toContain('alice')
  expect(impactDialog()!.textContent).toContain('查看订单')
  // 已下线的权限码没有名称，直接展示编码
  expect(impactDialog()!.textContent).toContain('legacy:code')
  // 只返回前 20 名，总数更大时要说明列表不完整
  expect(impactDialog()!.textContent).toContain('共 25 名')

  await click('[data-testid="role-impact-confirm"]')

  expect(writes(fetcher)).toEqual([`${location.origin}/api/iam/roles/r-viewer/permissions`])
  expect(JSON.parse(fetcher.mock.calls.at(-1)![1].body)).toEqual({ permissions: [] })
  expect(impactDialog()).toBeNull()
  expect(wrapper.emitted('changed')).toHaveLength(1)
})

it('取消确认时不提交，编辑框保留已改动的内容', async () => {
  const fetcher = impactFetcher({
    permissions: ok({
      affectedCount: 1,
      members: [{ membershipId: 'm-1', account: 'alice', displayName: 'Alice' }],
      lostPermissions: ['order:read'],
    }),
  })
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="perm-order:read"] input')
  await click('[data-testid="role-save"]')

  await click('[data-testid="role-impact-cancel"]')

  expect(writes(fetcher)).toEqual([])
  expect(impactDialog()).toBeNull()
  const permission = document.querySelector('[data-testid="perm-order:read"] input')
  expect((permission as HTMLInputElement).checked).toBe(false)
  expect(wrapper.emitted('changed')).toBeUndefined()
})

it('权限净影响为 0 时不打断保存；未修改权限和数据范围时不发预览', async () => {
  const fetcher = impactFetcher({
    permissions: ok({ affectedCount: 0, members: [], lostPermissions: [] }),
  })
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="perm-order:approve"] input')
  await click('[data-testid="role-save"]')

  expect(impactDialog()).toBeNull()
  expect(writes(fetcher)).toEqual([`${location.origin}/api/iam/roles/r-viewer/permissions`])

  fetcher.mockClear()
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="role-save"]')

  expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
    `${location.origin}/api/iam/roles/r-viewer/permissions`,
  ])
})

it('数据范围变更列出持有者，并说明不判断具体数据行', async () => {
  const fetcher = impactFetcher({
    dataPolicies: ok({
      affectedCount: 1,
      members: [{ membershipId: 'm-2', account: 'bob', displayName: 'Bob' }],
    }),
  })
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await chooseScope(wrapper, 'self')
  await click('[data-testid="role-save"]')

  const [url, init] = fetcher.mock.calls[0]!
  expect(url).toBe(`${location.origin}/api/iam/roles/r-viewer/data-policies/impact`)
  expect(JSON.parse(init.body)).toEqual({ policies: [{ resource: 'order', scope: 'self' }] })
  expect(writes(fetcher)).toEqual([])
  expect(impactDialog()!.textContent).toContain('Bob')
  expect(impactDialog()!.textContent).toContain('不判断具体数据行的变化')

  await click('[data-testid="role-impact-confirm"]')

  expect(writes(fetcher)).toEqual([
    `${location.origin}/api/iam/roles/r-viewer/permissions`,
    `${location.origin}/api/iam/roles/r-viewer/data-policies`,
  ])
})

it('数据范围变更没有持有者时不打断保存', async () => {
  const fetcher = impactFetcher({ dataPolicies: ok({ affectedCount: 0, members: [] }) })
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await chooseScope(wrapper, 'self')
  await click('[data-testid="role-save"]')

  expect(impactDialog()).toBeNull()
  expect(writes(fetcher)).toContain(`${location.origin}/api/iam/roles/r-viewer/data-policies`)
})

function failure() {
  return new Response(
    JSON.stringify({
      code: 'system.unavailable',
      httpStatus: 503,
      userTip: '服务暂不可用',
      traceId: 'trace-preview',
    }),
    { status: 503 },
  )
}

it('预览失败时明确提示且不显示零影响，由用户决定继续保存', async () => {
  const fetcher = impactFetcher({ permissions: failure() })
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="perm-order:read"] input')
  await click('[data-testid="role-save"]')

  expect(writes(fetcher)).toEqual([])
  const text = impactDialog()!.textContent!
  expect(text).toContain('无法获取影响预览')
  expect(text).toContain('服务暂不可用')
  expect(text).toContain('trace-preview')
  expect(text).not.toContain('0 名成员')
  const confirm = document.querySelector('[data-testid="role-impact-confirm"]') as HTMLElement
  expect(confirm.textContent).toContain('仍要保存')

  confirm.click()
  await flushPromises()

  expect(writes(fetcher)).toEqual([`${location.origin}/api/iam/roles/r-viewer/permissions`])
})

it('预览失败时取消不提交', async () => {
  const fetcher = impactFetcher({ permissions: failure() })
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="perm-order:read"] input')
  await click('[data-testid="role-save"]')

  await click('[data-testid="role-impact-cancel"]')

  expect(writes(fetcher)).toEqual([])
  expect(wrapper.emitted('changed')).toBeUndefined()
})

it('数据范围预览失败时仍说明不判断具体数据行，无影响的权限不出现在弹窗中', async () => {
  const fetcher = impactFetcher({
    permissions: ok({ affectedCount: 0, members: [], lostPermissions: [] }),
    dataPolicies: failure(),
  })
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="perm-order:approve"] input')
  await chooseScope(wrapper, 'self')
  await click('[data-testid="role-save"]')

  const text = impactDialog()!.textContent!
  expect(text).toContain('不判断具体数据行的变化')
  expect(text).toContain('无法获取影响预览')
  expect(text).not.toContain('权限影响')
})

// 让预览停在进行中，用于验证等待期间的操作
function pendingPreview() {
  let resolve!: (response: Response) => void
  const preview = new Promise<Response>((done) => (resolve = done))
  const fetcher = vi.fn().mockImplementation((url: string) => {
    if (url.endsWith('/permissions/impact')) return preview
    return Promise.resolve(ok(null))
  })
  return { fetcher, resolve }
}

const lossOfRead = () =>
  ok({
    affectedCount: 1,
    members: [{ membershipId: 'm-1', account: 'alice', displayName: 'Alice' }],
    lostPermissions: ['order:read'],
  })

it('确认后提交的是预览时的内容，不含等待预览期间的改动', async () => {
  const { fetcher, resolve } = pendingPreview()
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="perm-order:read"] input')
  await click('[data-testid="role-save"]')
  await click('[data-testid="perm-order:approve"] input')

  resolve(lossOfRead())
  await flushPromises()
  await click('[data-testid="role-impact-confirm"]')

  expect(JSON.parse(fetcher.mock.calls.at(-1)![1].body)).toEqual({ permissions: [] })
})

it('等待预览期间关闭编辑框后，不再弹出确认也不提交', async () => {
  const { fetcher, resolve } = pendingPreview()
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="perm-order:read"] input')
  await click('[data-testid="role-save"]')
  const footer = document.querySelector('[data-testid="role-save"]')!.parentElement!
  footer.querySelector('button')!.click()
  await flushPromises()

  resolve(lossOfRead())
  await flushPromises()

  expect(impactDialog()).toBeNull()
  expect(writes(fetcher)).toEqual([])
})

it('无影响的保存在等待预览期间关闭编辑框后也不提交', async () => {
  const { fetcher, resolve } = pendingPreview()
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="role-edit-order-viewer"]').trigger('click')
  await flushPromises()
  await click('[data-testid="perm-order:approve"] input')
  await click('[data-testid="role-save"]')
  const footer = document.querySelector('[data-testid="role-save"]')!.parentElement!
  footer.querySelector('button')!.click()
  await flushPromises()

  resolve(ok({ affectedCount: 0, members: [], lostPermissions: [] }))
  await flushPromises()

  expect(writes(fetcher)).toEqual([])
})

it('角色已删除视图保留删除信息，确认恢复后移除记录并通知主列表刷新', async () => {
  let restored = false
  const deletedRole = { ...roles[1]!, updatedAt: '2026-11-09T01:00:00Z', updatedBy: null }
  const fetcher = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      restored = true
      return ok(null)
    }
    expect(url).toBe(`${location.origin}/api/iam/roles?deleted=true`)
    return ok(restored ? [] : [deletedRole])
  })
  const wrapper = await mountPanel(fetcher)
  await wrapper.get('[data-testid="restore-view-deleted"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('删除时间')
  expect(wrapper.text()).toContain('系统')
  expect(wrapper.find('[data-testid="role-create"]').exists()).toBe(false)
  await wrapper.get('[data-testid="restore-r-viewer"]').trigger('click')
  await flushPromises()
  expect(restored).toBe(false)
  document.querySelector<HTMLButtonElement>('[data-testid="restore-confirm"]')!.click()
  await flushPromises()
  expect(restored).toBe(true)
  expect(wrapper.find('[data-testid="restore-r-viewer"]').exists()).toBe(false)
  expect(wrapper.text()).toContain('恢复成功')
  expect(wrapper.emitted('changed')).toHaveLength(1)
  wrapper.unmount()
})
