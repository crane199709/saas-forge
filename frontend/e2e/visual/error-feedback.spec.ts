import { expect, test, type Page, type Route } from '@playwright/test'
import { ok, onePage, openAsAdmin, viewports } from './support'

// Browser plugin not available：用项目 Playwright 工作流验证错误状态的生命周期。
const problems = new WeakMap<Page, string[]>()
test.beforeEach(({ page }) => {
  const errors: string[] = []
  problems.set(page, errors)
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (!['error', 'warning'].includes(message.type())) return
    if (
      /Failed to load resource: the server responded with a status of (400|503)/.test(
        message.text(),
      )
    )
      return
    errors.push(message.text())
  })
})
test.afterEach(async ({ page }) => {
  await expect(page).toHaveTitle(/SaaS Forge/)
  await expect(page.locator('body')).not.toBeEmpty()
  await expect(page.locator('vite-error-overlay, #webpack-dev-server-client-overlay')).toHaveCount(
    0,
  )
  expect(problems.get(page)).toEqual([])
})

const failure = {
  status: 400,
  json: {
    code: 'validation.failed',
    httpStatus: 400,
    userTip: '请检查填写内容后重试',
    traceId: 'failed-operation',
    errors: [{ field: 'rules[0]', code: 'invalid', userTip: '请输入 IP 地址或 CIDR' }],
  },
}
const roles = [
  {
    id: 'viewer',
    code: 'order-viewer',
    name: '订单查看员',
    builtin: false,
    owner: false,
    memberCount: 1,
    permissions: [],
    dataPolicies: [],
  },
]
const member = {
  membershipId: 'm1',
  account: 'alice@example.com',
  displayName: '张晓明',
  status: 'active',
  roleIds: ['viewer'],
  primaryDepartmentId: null,
  departmentIds: [],
  positionIds: [],
  attributes: {},
}
function respond(path: string, route: Route) {
  if (path === '/iam/roles') return route.fulfill(ok(roles))
  if (path === '/iam/roles/permission-tree') return route.fulfill(ok({ menus: [], others: [] }))
  if (path === '/iam/roles/condition-options')
    return route.fulfill(ok({ subject: [], environment: [], departments: [], positions: [] }))
  if (path === '/iam/roles/memberships' || path === '/iam/org/memberships')
    return route.fulfill(onePage([member]))
  return undefined
}

const platformTenant = {
  id: 't1',
  name: '示例科技有限公司',
  status: 'active',
  adminContact: 'owner@example.com',
  region: null,
  planId: 'basic',
  expiresAt: null,
  purgeAt: null,
  purgedAt: null,
  createdAt: '2026-11-01T00:00:00Z',
}
async function openPlatformTenant(page: Page) {
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.platform.session',
      JSON.stringify({
        accessToken: 'platform-access',
        refreshToken: 'platform-refresh',
        operatorName: '值班运营',
      }),
    ),
  )
  let creates = 0
  let retries = 0
  const unavailable = {
    status: 503,
    json: {
      code: 'system.unavailable',
      httpStatus: 503,
      userTip: '服务暂不可用，请稍后重试。',
      traceId: '0123456789abcdef0123456789abcdef',
    },
  }
  const pending = { service: 'demo-service', status: 'pending', error: null }
  const detail = {
    tenant: platformTenant,
    allowedTransitions: ['suspended', 'deleted'],
    statusChanges: [],
    initializations: [{ service: 'demo-service', status: 'failed', error: '初始化未完成' }],
  }
  await page.route('**/platform/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/iam/platform/access')
      return route.fulfill(
        ok({
          roles: ['super-admin'],
          permissions: ['platform:tenant:read', 'platform:tenant:manage'],
        }),
      )
    if (path === '/platform/plans')
      return route.fulfill(
        ok([
          {
            id: 'basic',
            code: 'basic',
            name: '基础版',
            permissions: [],
            subApps: [],
            memberQuota: 5,
            storageQuotaMb: null,
            updatedAt: '2026-11-01T00:00:00Z',
          },
        ]),
      )
    if (path === '/platform/tenants') {
      if (route.request().method() === 'POST')
        return route.fulfill(++creates === 1 ? unavailable : ok({ ...detail, initializations: [] }))
      return route.fulfill(onePage([platformTenant]))
    }
    if (path.endsWith('/retry')) return route.fulfill(++retries === 1 ? unavailable : ok(pending))
    if (path === '/platform/tenants/t1') return route.fulfill(ok(detail))
    return route.fulfill(ok([]))
  })
  await page.goto('/tenants')
}

for (const [label, width, height] of viewports) {
  test.describe(`平台错误反馈 ${label}`, () => {
    test.use({ viewport: { width, height }, baseURL: 'http://127.0.0.1:5183' })
    test('创建租户失败、重新打开及保存成功', async ({ page }) => {
      await openPlatformTenant(page)
      await page.getByRole('button', { name: '创建租户', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: '创建租户', exact: true })
      await page.locator('#tenant-name').fill('示例科技有限公司')
      await page.locator('#tenant-admin-contact').fill('owner@example.com')
      await page.getByTestId('tenant-plan').click()
      await page.getByRole('option', { name: '基础版', exact: true }).click()
      await dialog.getByRole('button', { name: '创建', exact: true }).click()
      await expect(dialog.locator('.el-alert--error')).toBeVisible()
      await expect(page).toHaveScreenshot(`error-feedback-platform-create-error-${label}.png`)
      await dialog.getByRole('button', { name: '关闭此对话框' }).click()
      await expect(dialog).not.toBeVisible()
      await expect(page.locator('.tenant-panel > .el-alert--error')).toHaveCount(0)
      await page.getByRole('button', { name: '创建租户', exact: true }).click()
      await expect(dialog.locator('.el-alert--error')).toHaveCount(0)
      await expect(page).toHaveScreenshot(`error-feedback-platform-create-reopened-${label}.png`)
      await dialog.getByRole('button', { name: '创建', exact: true }).click()
      await expect(dialog).not.toBeVisible()
      await expect(
        page.getByRole('dialog', { name: '示例科技有限公司', exact: true }),
      ).toBeVisible()
      await expect(page.locator('.el-alert--error')).toHaveCount(0)
    })
    test('详情重试失败后成功，旧错误消失', async ({ page }) => {
      await openPlatformTenant(page)
      await page.getByRole('button', { name: '详情', exact: true }).click()
      const drawer = page.getByRole('dialog', { name: '示例科技有限公司', exact: true })
      await drawer.getByRole('button', { name: '重试', exact: true }).click()
      await expect(drawer.locator('.el-alert--error')).toBeVisible()
      await drawer.locator('.el-alert--error').scrollIntoViewIfNeeded()
      await expect(page).toHaveScreenshot(`error-feedback-platform-detail-error-${label}.png`)
      await drawer.getByRole('button', { name: '重试', exact: true }).click()
      await expect(drawer.locator('.el-alert--error')).toHaveCount(0)
      await expect(drawer).toContainText('进行中')
      await expect(page).toHaveScreenshot(`error-feedback-platform-detail-succeeded-${label}.png`)
    })
  })
  test.describe(`错误反馈 ${label}`, () => {
    test.use({ viewport: { width, height } })

    test('IP 错误在修正及保存成功后消失', async ({ page }) => {
      let writes = 0
      await openAsAdmin(page, '/security-settings', (path, route) => {
        if (path === '/iam/security-settings') return route.fulfill(ok({ mfaRequired: false }))
        if (path === '/iam/security-settings/ip-allowlist') {
          if (route.request().method() === 'PUT' && ++writes === 1) return route.fulfill(failure)
          return route.fulfill(ok({ rules: ['192.0.2.1'], currentIp: '192.0.2.1' }))
        }
        return undefined
      })
      const rule = page.getByLabel('条目 1', { exact: true })
      await rule.fill('invalid')
      await page.getByTestId('ip-save').click()
      await expect(page.locator('.ip-section .el-alert--error')).toContainText(
        '请检查填写内容后重试',
      )
      await expect(page.locator('.security-settings > .el-alert--error')).toHaveCount(0)
      await expect(page).toHaveScreenshot(`error-feedback-ip-error-${label}.png`)

      await rule.fill('192.0.2.1')
      await expect(page.locator('.el-alert--error, .el-form-item__error')).toHaveCount(0)
      await page.getByTestId('ip-save').click()
      await expect(page.getByRole('status')).toHaveText('租户 IP 允许列表已保存。')
      await expect(page.getByText('failed-operation')).toHaveCount(0)
      await expect(page).toHaveURL(/\/security-settings$/)
      await expect(page).toHaveScreenshot(`error-feedback-ip-saved-${label}.png`)
    })

    test('角色编辑重新打开及保存成功后不残留错误', async ({ page }) => {
      let writes = 0
      await openAsAdmin(page, '/roles', (path, route) => {
        if (path === '/iam/roles/viewer') return route.fulfill(++writes === 1 ? failure : ok(null))
        return respond(path, route)
      })
      await page.getByTestId('role-edit-order-viewer').click()
      const drawer = page.getByRole('dialog')
      await drawer.getByRole('textbox', { name: '名称', exact: true }).fill('订单审核查看员')
      await page.getByTestId('role-save').click()
      await expect(drawer.locator('.el-alert--error')).toBeVisible()
      await expect(page).toHaveScreenshot(`error-feedback-role-error-${label}.png`)
      await drawer.getByRole('button', { name: '关闭此对话框' }).click()
      await expect(drawer).not.toBeVisible()
      await page.getByTestId('role-edit-order-viewer').click()
      await expect(drawer).toBeVisible()
      await expect(page.locator('.el-alert--error')).toHaveCount(0)
      await expect(page).toHaveScreenshot(`error-feedback-role-reopened-${label}.png`)
      await drawer.getByRole('textbox', { name: '名称', exact: true }).fill('订单审核查看员')
      await page.getByTestId('role-save').click()
      await expect(drawer).not.toBeVisible()
      await expect(page.locator('.el-alert--error')).toHaveCount(0)
    })

    for (const kind of ['role', 'organization'] as const) {
      test(`${kind} 成员分配重新打开及保存成功后不残留错误`, async ({ page }) => {
        let writes = 0
        const endpoint =
          kind === 'role' ? '/iam/roles/memberships/m1' : '/iam/org/memberships/m1/assignment'
        await openAsAdmin(page, kind === 'role' ? '/roles' : '/org', (path, route) => {
          if (path === endpoint) return route.fulfill(++writes === 1 ? failure : ok(null))
          return respond(path, route)
        })
        await page.getByRole('tab', { name: '成员分配' }).click()
        const edit = page.getByTestId(kind === 'role' ? 'role-member-edit-m1' : 'member-edit-m1')
        const save = page.getByTestId(kind === 'role' ? 'role-member-save' : 'assignment-save')
        await edit.click()
        const dialog = page.getByRole('dialog')
        await save.click()
        await expect(dialog.locator('.el-alert--error')).toBeVisible()
        await expect(page).toHaveScreenshot(`error-feedback-${kind}-assignment-error-${label}.png`)
        await dialog.getByRole('button', { name: '关闭此对话框' }).click()
        await expect(dialog).not.toBeVisible()
        await edit.click()
        await expect(dialog).toBeVisible()
        await expect(page.locator('.el-alert--error')).toHaveCount(0)
        await expect(page).toHaveScreenshot(
          `error-feedback-${kind}-assignment-reopened-${label}.png`,
        )
        await save.click()
        await expect(dialog).not.toBeVisible()
        await expect(page.locator('.el-alert--error')).toHaveCount(0)
      })
    }
  })
}
