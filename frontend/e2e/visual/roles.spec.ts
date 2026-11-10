import { expect, test, type Page } from '@playwright/test'
import { ok, onePage, openAsAdmin, viewports } from './support'

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

async function openRoles(page: Page, resources = dataResources) {
  await openAsAdmin(page, '/roles', (path, route) => {
    if (path === '/iam/roles') return route.fulfill(ok(roles))
    if (path.endsWith('/memberships')) return route.fulfill(onePage(members))
    if (path === '/iam/roles/permission-tree')
      return route.fulfill(ok({ menus: [], others: permissions }))
    if (path === '/iam/roles/data-resources') return route.fulfill(ok(resources))
    if (path === '/iam/roles/condition-options')
      return route.fulfill(ok({ subject: [], environment: [], departments: [], positions: [] }))
    return undefined
  })
  await expect(page.getByTestId('role-edit-order-viewer')).toBeVisible()
}

for (const [label, width, height] of viewports) {
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

    test('数据范围下拉选择与自定义条件', async ({ page }) => {
      const problems: string[] = []
      page.on('pageerror', (error) => problems.push(error.message))
      page.on('console', (message) => {
        if (['error', 'warning'].includes(message.type())) problems.push(message.text())
      })
      await openRoles(
        page,
        dataResources.map((resource) => ({
          ...resource,
          name: resource.code === 'report' ? '跨部门销售与退款统计报表' : resource.name,
        })),
      )
      await page.getByTestId('role-create').click()
      const scope = page.getByTestId('scope-order')
      await expect(scope).toContainText('未配置（不可见）')
      await expect(page.getByTestId('scope-report')).toContainText('未配置（不可见）')
      await expect(page.getByRole('combobox', { name: '订单', exact: true })).toBeVisible()
      await scope.scrollIntoViewIfNeeded()
      await expect(page).toHaveScreenshot(`roles-scope-defaults-${label}.png`)

      await scope.click()
      await expect(page.getByRole('option')).toHaveText([
        '未配置（不可见）',
        '本人',
        '本部门',
        '本部门及下级',
        '全部',
        '自定义条件',
      ])
      await expect(page).toHaveScreenshot(`roles-scope-options-${label}.png`)
      await page.getByRole('option', { name: '本部门及下级', exact: true }).click()
      await expect(scope).toContainText('本部门及下级')
      await scope.click()
      await page.getByRole('option', { name: '自定义条件', exact: true }).click()
      await expect(page.getByTestId('condition-order')).toBeVisible()
      await expect(page.getByTestId('role-save')).toBeDisabled()
      await expect(page).toHaveScreenshot(`roles-scope-custom-${label}.png`)

      await scope.click()
      await page.getByRole('option', { name: '本人', exact: true }).click()
      await expect(page.getByTestId('condition-order')).toHaveCount(0)
      await expect(scope).toContainText('本人')
      await expect(page).toHaveURL(/\/roles$/)
      await expect(page).toHaveTitle(/SaaS Forge/)
      await expect(page.getByRole('heading', { name: '角色管理', exact: true })).toBeVisible()
      await expect(
        page.locator('vite-error-overlay, #webpack-dev-server-client-overlay'),
      ).toHaveCount(0)
      expect(problems).toEqual([])
    })
  })
}
