import { expect, test, type Page, type Route } from '@playwright/test'

// 浏览器只替换公开 HTTP 边界，渲染、路由、权限与 SDK 客户端均真实运行。
function ok(data: unknown, page?: unknown) {
  return {
    json: {
      code: 'ok',
      httpStatus: 200,
      data,
      meta: page ? { page } : undefined,
      traceId: 'impact-ok',
    },
  }
}

function unavailable(route: Route, code: string, userTip: string) {
  return route.fulfill({
    status: 503,
    json: { code, httpStatus: 503, userTip, traceId: 'impact-503' },
  })
}

const problems = new WeakMap<Page, string[]>()
test.beforeEach(async ({ page }) => {
  const errors: string[] = []
  problems.set(page, errors)
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (
      ['error', 'warning'].includes(message.type()) &&
      // 503 是用例刻意模拟的预览失败，Chrome 会记录资源错误。
      !/Failed to load resource: the server responded with a status of 503/.test(message.text())
    )
      errors.push(message.text())
  })
})
test.afterEach(({ page }) => {
  expect(problems.get(page)).toEqual([])
})

const viewer = {
  id: 'r-viewer',
  code: 'order-viewer',
  name: '订单查看员',
  builtin: false,
  owner: false,
  memberCount: 1,
  permissions: ['order:read'],
  dataPolicies: [{ resource: 'order', scope: 'department' }],
}

const lossOfRead = {
  affectedCount: 1,
  members: [{ membershipId: 'm-1', account: 'alice', displayName: 'Alice' }],
  lostPermissions: ['order:read'],
}

// 返回记录到的写请求，用例据此断言「取消不提交、确认才提交」。
async function openRoleEditor(
  page: Page,
  permissionImpact: (route: Route) => Promise<void> | void,
  dataPolicyImpact: (route: Route) => Promise<void> | void = (route) =>
    route.fulfill(ok({ affectedCount: 0, members: [] })),
) {
  const writes: { path: string; body: unknown }[] = []
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({
        accessToken: 'tenant-access',
        refreshToken: 'tenant-refresh',
        membershipId: 'm',
        tenantId: 't',
        tenantName: '测试租户',
        identityName: '测试成员',
      }),
    ),
  )
  await page.route('**/iam/**', (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/iam/auth/access')
      return route.fulfill(ok({ permissions: ['iam:role:manage'], menus: [], subApps: [] }))
    if (path === '/iam/auth/memberships')
      return route.fulfill(ok([{ membershipId: 'm', tenantId: 't', tenantName: '测试租户' }]))
    if (path.endsWith('/unread-count')) return route.fulfill(ok({ count: 0 }))
    if (path === '/iam/roles') return route.fulfill(ok([viewer]))
    if (path === '/iam/roles/permission-tree')
      return route.fulfill(
        ok({
          menus: [],
          others: [
            { code: 'order:read', name: '查看订单', service: 'demo-service', group: '订单' },
          ],
        }),
      )
    if (path === '/iam/roles/data-resources')
      return route.fulfill(
        ok([
          {
            code: 'order',
            name: '订单',
            service: 'demo-service',
            scopes: ['self', 'department'],
            attributes: [],
          },
        ]),
      )
    if (path === '/iam/roles/condition-options')
      return route.fulfill(ok({ subject: [], environment: [], departments: [], positions: [] }))
    if (path === '/iam/roles/r-viewer/permissions/impact') return permissionImpact(route)
    if (path === '/iam/roles/r-viewer/data-policies/impact') return dataPolicyImpact(route)
    if (request.method() === 'PUT') {
      writes.push({ path, body: request.postDataJSON() })
      return route.fulfill(ok(null))
    }
    return route.fulfill(ok([]))
  })
  await page.goto('/roles')
  await page.getByTestId('role-edit-order-viewer').click()
  return writes
}

const permissionWrite = {
  path: '/iam/roles/r-viewer/permissions',
  body: { permissions: [] },
}

test('角色权限有净影响：取消不提交且保留修改，确认后才保存', async ({ page }) => {
  const writes = await openRoleEditor(page, (route) => route.fulfill(ok(lossOfRead)))
  await page.getByTestId('perm-order:read').click()
  await page.getByTestId('role-save').click()

  const impact = page.getByRole('dialog', { name: '确认保存对 订单查看员 的修改' })
  await expect(impact).toContainText('1 名成员将失去权限')
  await expect(impact).toContainText('查看订单')
  await expect(impact).toContainText('Alice（alice）')
  await impact.getByRole('button', { name: '取消', exact: true }).click()
  await expect(impact).toBeHidden()
  expect(writes).toEqual([])
  await expect(page.getByRole('dialog', { name: '编辑 订单查看员' })).toBeVisible()
  await expect(page.getByTestId('perm-order:read').locator('input')).not.toBeChecked()

  await page.getByTestId('role-save').click()
  await impact.getByRole('button', { name: '确认保存', exact: true }).click()
  await expect(impact).toBeHidden()
  await expect(page.getByRole('dialog', { name: '编辑 订单查看员' })).toBeHidden()
  expect(writes).toEqual([permissionWrite])
})

test('数据范围变更的确认弹窗说明不判断具体数据行', async ({ page }) => {
  const writes = await openRoleEditor(
    page,
    (route) => route.fulfill(ok({ affectedCount: 0, members: [], lostPermissions: [] })),
    (route) =>
      route.fulfill(
        ok({
          affectedCount: 1,
          members: [{ membershipId: 'm-2', account: 'bob', displayName: 'Bob' }],
        }),
      ),
  )
  await page.getByTestId('scope-order').click()
  await page.getByRole('option', { name: '本人', exact: true }).click()
  await page.getByTestId('role-save').click()

  const impact = page.getByRole('dialog', { name: '确认保存对 订单查看员 的修改' })
  await expect(impact).toContainText('不判断具体数据行的变化')
  await expect(impact).toContainText('Bob（bob）')
  await impact.getByRole('button', { name: '确认保存', exact: true }).click()
  await expect(impact).toBeHidden()
  await expect
    .poll(() => writes.map((write) => write.path))
    .toEqual(['/iam/roles/r-viewer/permissions', '/iam/roles/r-viewer/data-policies'])
  expect(writes.find((write) => write.path.endsWith('/data-policies'))?.body).toEqual({
    policies: [{ resource: 'order', scope: 'self' }],
  })
})

test('角色预览失败时明确提示，由用户决定继续保存', async ({ page }) => {
  const writes = await openRoleEditor(page, (route) =>
    unavailable(route, 'system.unavailable', '服务暂不可用'),
  )
  await page.getByTestId('perm-order:read').click()
  await page.getByTestId('role-save').click()

  const impact = page.getByRole('dialog', { name: '确认保存对 订单查看员 的修改' })
  await expect(impact).toContainText('无法获取影响预览')
  await expect(impact.getByRole('alert')).toContainText('服务暂不可用')
  await expect(impact.getByRole('alert')).toContainText('impact-503')
  expect(writes).toEqual([])
  await impact.getByRole('button', { name: '仍要保存', exact: true }).click()
  await expect.poll(() => writes).toEqual([permissionWrite])
})

const tenantRow = {
  id: 't1',
  name: '示例租户',
  status: 'active',
  adminContact: 'owner@example.com',
  region: null,
  planId: 'pro',
  expiresAt: null,
  purgeAt: null,
  purgedAt: null,
  createdAt: '2026-10-28T00:00:00Z',
}

const plan = (id: string, name: string) => ({
  id,
  code: id,
  name,
  permissions: [],
  subApps: [],
  memberQuota: null,
  storageQuotaMb: null,
  updatedAt: '2026-10-28T00:00:00Z',
})

const downgrade = {
  lostPermissions: ['order:approve'],
  lostSubApps: ['demo'],
  memberCount: 7,
  memberQuota: 5,
  membersOverQuota: 2,
  affectedMemberships: 3,
}

async function openTenantDetail(page: Page, impact: (route: Route) => Promise<void> | void) {
  const planWrites: unknown[] = []
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.platform.session',
      JSON.stringify({
        accessToken: 'operator-access',
        refreshToken: 'operator-refresh',
        operatorName: '值班运营',
      }),
    ),
  )
  await page.route('**/iam/platform/access', (route) =>
    route.fulfill(
      ok({ roles: [], permissions: ['platform:tenant:read', 'platform:tenant:manage'] }),
    ),
  )
  await page.route('**/platform/plans', (route) =>
    route.fulfill(ok([plan('pro', '专业版'), plan('basic', '基础版')])),
  )
  await page.route('**/platform/tenants**', (route) => {
    const request = route.request()
    const url = new URL(request.url())
    url.pathname = url.pathname.replace(/^\/api(?=\/)/, '')
    const detail = {
      tenant: tenantRow,
      initializations: [],
      allowedTransitions: [],
      statusChanges: [],
    }
    if (url.pathname === '/platform/tenants')
      return route.fulfill(
        ok([tenantRow], { number: 1, size: 20, totalElements: 1, totalPages: 1 }),
      )
    if (url.pathname === '/platform/tenants/t1/plan/impact') {
      expect(url.searchParams.get('planId')).toBe('basic')
      return impact(route)
    }
    if (url.pathname === '/platform/tenants/t1/plan') {
      planWrites.push(request.postDataJSON())
      return route.fulfill(ok(detail))
    }
    return route.fulfill(ok(detail))
  })
  await page.goto('http://127.0.0.1:5173/tenants')
  await page.getByRole('button', { name: '详情', exact: true }).click()
  await page.getByTestId('tenant-change-plan').click()
  await page.getByRole('option', { name: '基础版', exact: true }).click()
  return planWrites
}

test('更换 Plan 有影响：取消不更换，确认后才更换', async ({ page }) => {
  const writes = await openTenantDetail(page, (route) => route.fulfill(ok(downgrade)))
  const submit = page.getByRole('button', { name: '更换套餐', exact: true })
  await submit.click()

  const impact = page.getByRole('dialog', { name: '确认更换为 基础版' })
  await expect(impact).toContainText('3 名成员会因本次更换失去能力')
  await expect(impact).toContainText('当前 7 名成员，新套餐上限 5 名，超出 2 名')
  await expect(impact).toContainText('order:approve')
  await expect(impact).toContainText('demo')
  await impact.getByRole('button', { name: '取消', exact: true }).click()
  await expect(impact).toBeHidden()
  expect(writes).toEqual([])

  await submit.click()
  await impact.getByRole('button', { name: '确认更换', exact: true }).click()
  await expect(impact).toBeHidden()
  await expect.poll(() => writes).toEqual([{ planId: 'basic' }])
})

test('升级没有影响时不打断更换', async ({ page }) => {
  const writes = await openTenantDetail(page, (route) =>
    route.fulfill(
      ok({
        lostPermissions: [],
        lostSubApps: [],
        memberCount: null,
        memberQuota: null,
        membersOverQuota: 0,
        affectedMemberships: 0,
      }),
    ),
  )
  await page.getByRole('button', { name: '更换套餐', exact: true }).click()

  await expect.poll(() => writes).toEqual([{ planId: 'basic' }])
  await expect(page.getByRole('dialog', { name: '确认更换为 基础版' })).toHaveCount(0)
})

test('Plan 预览 503 时提示失败而不是零影响，由用户决定继续', async ({ page }) => {
  const writes = await openTenantDetail(page, (route) =>
    unavailable(route, 'plan.impactUnavailable', '暂时无法读取成员数据'),
  )
  await page.getByRole('button', { name: '更换套餐', exact: true }).click()

  const impact = page.getByRole('dialog', { name: '确认更换为 基础版' })
  await expect(impact).toContainText('无法获取影响预览')
  await expect(impact.getByRole('alert')).toContainText('暂时无法读取成员数据')
  await expect(impact).not.toContainText('0 名成员')
  expect(writes).toEqual([])
  await impact.getByRole('button', { name: '仍要更换', exact: true }).click()
  await expect.poll(() => writes).toEqual([{ planId: 'basic' }])
})
