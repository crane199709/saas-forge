import { expect, test, type Page } from '@playwright/test'

function ok(data: unknown, page?: unknown) {
  return {
    json: {
      code: 'ok',
      httpStatus: 200,
      data,
      meta: page ? { page } : undefined,
      traceId: 'visual',
    },
  }
}

const permissions = [
  ['order:read', '查看订单', '订单'],
  ['order:approve', '审批订单', '订单'],
  ['order:export', '导出订单', '订单'],
  ['order:refund', '订单退款', '订单'],
  ['report:view', '查看报表', '报表'],
  ['report:export', '导出报表', '报表'],
  ['member:read', '查看成员', '成员'],
  ['member:invite', '邀请成员', '成员'],
].map(([code, name, group]) => ({ code, name, service: 'demo-service', group }))

const role = (
  code: string,
  name: string,
  memberCount: number,
  granted: string[],
  dataPolicies: { resource: string; scope: string }[],
  builtin = false,
) => ({
  id: code,
  code,
  name,
  builtin,
  owner: false,
  memberCount,
  permissions: granted,
  dataPolicies,
})

// 用接近真实长度的数据：截图要暴露长文本、内置角色无操作等布局问题。
const roles = [
  role('tenant-owner', '租户所有者', 1, [], [], true),
  role('tenant-admin', '租户管理员', 3, [], [], true),
  role(
    'order-operations-manager',
    '华东大区订单运营负责人',
    12,
    permissions.map((item) => item.code),
    [
      { resource: 'order', scope: 'department_and_below' },
      { resource: 'report', scope: 'department' },
    ],
  ),
  role(
    'order-viewer',
    '订单查看员',
    48,
    ['order:read'],
    [{ resource: 'order', scope: 'department' }],
  ),
  role(
    'finance-auditor',
    '财务审计',
    6,
    ['order:read', 'order:export', 'report:view', 'report:export'],
    [
      { resource: 'order', scope: 'all' },
      { resource: 'report', scope: 'all' },
    ],
  ),
  role('new-role', '待配置角色', 0, [], []),
]

const dataResources = [
  {
    code: 'order',
    name: '订单',
    service: 'demo-service',
    scopes: ['self', 'department', 'department_and_below', 'all'],
    attributes: [],
  },
  {
    code: 'report',
    name: '报表',
    service: 'demo-service',
    scopes: ['self', 'department', 'all'],
    attributes: [],
  },
]

const members = [
  ['张伟', 'zhangwei@example.com', 'active', ['tenant-admin']],
  ['李娜', 'lina@example.com', 'active', ['order-operations-manager', 'finance-auditor']],
  ['王强', 'wangqiang@example.com', 'suspended', ['order-viewer']],
  ['陈晓华', 'chenxiaohua@example.com', 'invited', []],
].map(([displayName, account, status, roleIds], index) => ({
  membershipId: `m-${index}`,
  displayName,
  account,
  status,
  roleIds,
}))

async function openRoles(page: Page) {
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({
        accessToken: 'tenant-access',
        refreshToken: 'tenant-refresh',
        membershipId: 'm',
        tenantId: 't',
        tenantName: '示例科技有限公司',
        identityName: '测试成员',
      }),
    ),
  )
  await page.route('**/iam/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/iam/auth/access')
      return route.fulfill(
        ok({
          permissions: [
            'announcement:manage',
            'iam:member:manage',
            'iam:org:manage',
            'iam:role:manage',
            'iam:audit:read',
            'iam:reference-data:manage',
            'iam:security:manage',
          ],
          menus: [],
          subApps: [],
        }),
      )
    if (path === '/iam/auth/memberships')
      return route.fulfill(
        ok([{ membershipId: 'm', tenantId: 't', tenantName: '示例科技有限公司' }]),
      )
    if (path.endsWith('/unread-count')) return route.fulfill(ok({ count: 3 }))
    if (path === '/iam/roles') return route.fulfill(ok(roles))
    if (path.endsWith('/memberships'))
      return route.fulfill(
        ok(members, { number: 1, size: 20, totalElements: members.length, totalPages: 1 }),
      )
    if (path === '/iam/roles/permission-tree')
      return route.fulfill(ok({ menus: [], others: permissions }))
    if (path === '/iam/roles/data-resources') return route.fulfill(ok(dataResources))
    if (path === '/iam/roles/condition-options')
      return route.fulfill(ok({ subject: [], environment: [], departments: [], positions: [] }))
    return route.fulfill(ok([]))
  })
  await page.goto('/roles')
  await expect(page.getByTestId('role-edit-order-viewer')).toBeVisible()
}

for (const [label, width, height] of [
  ['desktop', 1440, 900],
  ['laptop', 1024, 768],
] as const) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    test('角色列表', async ({ page }) => {
      await openRoles(page)
      await expect(page).toHaveScreenshot(`roles-list-${label}.png`)
    })

    test('成员分配', async ({ page }) => {
      await openRoles(page)
      await page.getByRole('tab', { name: '成员分配' }).click()
      await expect(page.getByTestId('role-member-edit-m-0')).toBeVisible()
      await expect(page).toHaveScreenshot(`roles-members-${label}.png`)
    })

    test('编辑角色', async ({ page }) => {
      await openRoles(page)
      await page.getByTestId('role-edit-order-viewer').click()
      await expect(page.getByTestId('role-save')).toBeVisible()
      await expect(page).toHaveScreenshot(`roles-edit-${label}.png`)
    })
  })
}
