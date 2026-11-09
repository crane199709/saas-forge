import { expect, test, type Route } from '@playwright/test'
import { ok, onePage, openAsAdmin, viewports } from './support'

// 数据覆盖最长值、空值和各种状态，截图才能暴露布局问题。
const members = [
  ['owner', '张伟', 'zhangwei', 'zhangwei@example.com', 'active', true],
  ['alice', '李娜', 'lina', 'lina.operations.manager@example-enterprise.com', 'active', false],
  ['bob', '王强', 'wangqiang', '13800000000', 'suspended', false],
  ['carol', '陈晓华', null, 'chenxiaohua@example.com', 'invited', false],
].map(([membershipId, displayName, account, contact, status, owner]) => ({
  membershipId,
  displayName,
  account,
  email: String(contact).includes('@') ? contact : null,
  phone: String(contact).includes('@') ? null : contact,
  status,
  owner,
}))

const audit = (
  id: string,
  category: string,
  action: string,
  actorName: string | null,
  resourceType: string | null,
  resourceId: string | null,
  errorCode: string | null = null,
) => ({
  id,
  occurredAt: '2026-11-08T09:30:00Z',
  category,
  action,
  actorId: actorName && 'u',
  actorAccount: actorName && 'zhangwei',
  actorName,
  resourceType,
  resourceId,
  outcome: errorCode ? 'failure' : 'success',
  errorCode,
  detail: {},
  service: 'forge-iam',
  traceId: 'trace',
})

const events = [
  audit('a', 'operation', 'order.create', '张伟', 'order', 'SO-20261108-000123'),
  audit('b', 'login', 'auth.login', null, null, null, 'auth.invalidCredentials'),
  audit('c', 'permission', 'iam.role.permissions.replace', '李娜', 'role', 'order-viewer'),
]

const departments = [
  { id: 'hq', parentId: null, name: '总部', path: 'hq' },
  { id: 'rd', parentId: 'hq', name: '研发中心', path: 'hq.rd' },
  { id: 'fe', parentId: 'rd', name: '前端平台组', path: 'hq.rd.fe' },
  { id: 'ops', parentId: 'hq', name: '华东大区运营部', path: 'hq.ops' },
]
const positions = [
  { id: 'p1', name: '研发经理' },
  { id: 'p2', name: '高级前端工程师' },
]
const attributes = [
  { id: 'a1', code: 'employee_number', name: '工号', type: 'string', required: true },
  { id: 'a2', code: 'cost_center', name: '成本中心', type: 'string', required: false },
  { id: 'a3', code: 'is_contractor', name: '外包人员', type: 'boolean', required: false },
]
const assignments = [
  {
    membershipId: 'm1',
    account: 'lina',
    displayName: '李娜',
    status: 'active',
    primaryDepartmentId: 'fe',
    departmentIds: ['ops', 'rd'],
    positionIds: ['p1', 'p2'],
    attributes: {},
  },
  {
    membershipId: 'm2',
    account: 'chenxiaohua',
    displayName: '陈晓华',
    status: 'invited',
    primaryDepartmentId: null,
    departmentIds: [],
    positionIds: [],
    attributes: {},
  },
]

const item = (code: string, name: string, sort: number, custom = false, status = 'enabled') => ({
  code,
  name,
  sort,
  custom,
  status,
  nameConflict: false,
})
const dictionaries = [
  {
    service: 'demo-service',
    code: 'order.channel',
    name: '订单渠道',
    extensible: true,
    items: [
      item('web', '官网', 1),
      item('app', '移动应用', 2),
      item('custom.expo', '线下展会渠道', 3, true),
      { ...item('wechat', '微信小程序', 4), nameConflict: true },
    ],
  },
  {
    service: 'crm-service',
    code: 'customer.gender',
    name: '性别',
    extensible: false,
    items: [item('male', '男', 1), item('female', '女', 2)],
  },
]
const parameter = (
  code: string,
  name: string,
  type: string,
  value: unknown,
  defaultValue: unknown,
  extra: object = {},
) => ({
  service: 'demo-service',
  code,
  name,
  type,
  dictionary: null,
  defaultValue,
  min: null,
  max: null,
  maxLength: null,
  tenantEditable: true,
  value,
  overridden: value !== defaultValue,
  ...extra,
})
const parameters = [
  parameter('order.max-lines', '订单最大行数', 'integer', 80, 50, { min: 1, max: 200 }),
  parameter('order.auto-confirm', '订单自动确认', 'boolean', false, false),
  parameter('order.default-remark', '订单默认备注', 'string', '请尽快发货', '', { maxLength: 200 }),
]

function respond(path: string, route: Route) {
  if (path === '/iam/members') return route.fulfill(onePage(members))
  if (path === '/iam/audit/events') return route.fulfill(onePage(events))
  if (path === '/iam/org/departments') return route.fulfill(ok(departments))
  if (path === '/iam/org/positions') return route.fulfill(ok(positions))
  if (path === '/iam/org/extended-attributes') return route.fulfill(ok(attributes))
  if (path === '/iam/org/memberships') return route.fulfill(onePage(assignments))
  if (path === '/iam/reference-data/dictionaries') return route.fulfill(ok(dictionaries))
  if (path === '/iam/reference-data/parameters') return route.fulfill(ok(parameters))
  return undefined
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    test('成员管理', async ({ page }) => {
      await openAsAdmin(page, '/members', respond)
      await expect(page.getByTestId('member-owner')).toBeVisible()
      await expect(page).toHaveScreenshot(`members-${label}.png`)
    })

    test('审计日志', async ({ page }) => {
      await openAsAdmin(page, '/audit', respond)
      await expect(page.getByTestId('audit-a')).toBeVisible()
      await expect(page).toHaveScreenshot(`audit-${label}.png`)
    })

    test('组织-部门', async ({ page }) => {
      await openAsAdmin(page, '/org', respond)
      await page.getByTestId('dept-node-rd').click()
      await expect(page.getByTestId('dept-delete')).toBeVisible()
      await expect(page).toHaveScreenshot(`org-departments-${label}.png`)
    })

    for (const [tab, file, anchor] of [
      ['岗位', 'positions', 'position-p1'],
      ['扩展属性', 'attributes', 'attribute-employee_number'],
      ['成员', 'members', 'member-m1'],
    ] as const) {
      test(`组织-${tab}`, async ({ page }) => {
        await openAsAdmin(page, '/org', respond)
        await page.getByRole('tab', { name: tab, exact: false }).click()
        await expect(page.getByTestId(anchor)).toBeVisible()
        await expect(page).toHaveScreenshot(`org-${file}-${label}.png`)
      })
    }

    test('字典', async ({ page }) => {
      await openAsAdmin(page, '/dictionaries', respond)
      await page.getByTestId('dictionary-demo-service/order.channel').click()
      await expect(page.getByTestId('item-web')).toBeVisible()
      await expect(page).toHaveScreenshot(`dictionaries-${label}.png`)
    })

    test('参数', async ({ page }) => {
      await openAsAdmin(page, '/parameters', respond)
      await expect(page.getByTestId('override-demo-service/order.max-lines')).toBeVisible()
      await expect(page).toHaveScreenshot(`parameters-${label}.png`)
    })
  })
}
