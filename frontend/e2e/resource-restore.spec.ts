import { expect, test, type Page } from '@playwright/test'

// Browser 插件不可用，因此通过公开 HTTP 边界验证真实 UI、路由与 SDK。
const problems = new WeakMap<Page, string[]>()
function ok(data: unknown, page?: unknown) {
  return {
    json: {
      code: 'ok',
      httpStatus: 200,
      data,
      meta: page ? { page } : undefined,
      traceId: 'restore-ok',
    },
  }
}

test.beforeEach(({ page }) => {
  const errors: string[] = []
  problems.set(page, errors)
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (
      ['error', 'warning'].includes(message.type()) &&
      !/Failed to load resource: the server responded with a status of 409/.test(message.text())
    )
      errors.push(message.text())
  })
})
test.afterEach(async ({ page }) => {
  await expect(page).toHaveTitle('SaaS Forge 租户端')
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  expect(problems.get(page)).toEqual([])
})

async function session(page: Page, permissions: string[]) {
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({
        accessToken: 'access',
        refreshToken: 'refresh',
        membershipId: 'm',
        tenantId: 't',
        tenantName: '恢复测试租户',
        identityName: '管理员',
      }),
    ),
  )
  await page.route('**/iam/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/iam/auth/access')
      return route.fulfill(
        ok({
          permissions,
          menus: [
            {
              code: 'demo',
              name: '示例应用',
              children: [
                {
                  code: 'demo.overview',
                  name: '示例子应用',
                  app: 'demo',
                  path: 'overview',
                  permission: 'order:read',
                },
              ],
            },
          ],
          subApps: [
            {
              code: 'demo',
              name: '示例',
              version: '1.0.0',
              remoteEntry: 'http://127.0.0.1:5175/remoteEntry.js',
              sharedRequirements: { vue: '3.5.0' },
            },
          ],
        }),
      )
    if (path === '/iam/auth/memberships')
      return route.fulfill(ok([{ membershipId: 'm', tenantId: 't', tenantName: '恢复测试租户' }]))
    if (path.endsWith('/unread-count')) return route.fulfill(ok({ count: 0 }))
    return route.fulfill(ok([]))
  })
  await page.route('**/demo/context', (route) =>
    route.fulfill(ok({ identityId: 'identity', tenantId: 't', membershipId: 'm', version: 1 })),
  )
  await page.route('**/demo/io/tasks?*', (route) =>
    route.fulfill(ok([], { number: 1, size: 10, totalElements: 0, totalPages: 0 })),
  )
}

test('订单切换重置页码，恢复末页最后一条后回到有效页并刷新主列表', async ({ page }) => {
  await session(page, [
    'order:read',
    'order:delete',
    'order:update',
    'order:import',
    'order:export',
  ])
  const active = Array.from({ length: 21 }, (_, i) => ({
    id: `a${i + 1}`,
    title: `有效订单 ${i + 1}`,
    amount: 100,
    status: 'unpaid',
  }))
  const deleted = Array.from({ length: 21 }, (_, i) => ({
    id: `d${i + 1}`,
    title: `已删除订单 ${i + 1}`,
    amount: 200,
    status: 'closed',
    updatedAt: '2026-11-09T01:00:00Z',
    updatedBy: null,
  }))
  await page.route('**/demo/orders**', (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() === 'POST') {
      expect(url.pathname).toBe('/demo/orders/d21/restore')
      active.unshift(deleted.pop()!)
      return route.fulfill(ok(null))
    }
    const data = url.searchParams.get('deleted') === 'true' ? deleted : active
    const number = Number(url.searchParams.get('page'))
    expect(url.searchParams.get('size')).toBe('20')
    expect(url.searchParams.get('sort')).toBe('createdAt,asc')
    return route.fulfill(
      ok(data.slice((number - 1) * 20, number * 20), {
        number,
        size: 20,
        totalElements: data.length,
        totalPages: Math.ceil(data.length / 20),
      }),
    )
  })
  await page.goto('/apps/demo/overview')
  await expect(page.getByRole('heading', { name: '订单', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '下一页' }).click()
  // 翻页响应返回前旧的 20 行还在，多元素时 toHaveText 会直接报严格模式错误。
  await expect(page.getByTestId('order-title')).toHaveCount(1)
  await expect(page.getByTestId('order-title')).toHaveText('有效订单 21')
  await page.getByRole('button', { name: '已删除', exact: true }).click()
  await expect(page.getByTestId('order-title').first()).toHaveText('已删除订单 1')
  await expect(page.getByRole('button', { name: '关闭订单' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '导入订单' })).toHaveCount(0)
  await expect(page.getByTestId('export-submit')).toHaveCount(0)
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.getByTestId('order-title')).toHaveCount(1)
  await expect(page.getByTestId('order-title')).toHaveText('已删除订单 21')
  await page.getByRole('button', { name: '恢复', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '确认恢复' })
  await expect(confirmation).toContainText('已删除订单 21')
  await confirmation.getByRole('button', { name: '确认恢复', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '确认恢复' })).toBeHidden()
  await expect(page.getByTestId('order-title').first()).toHaveText('已删除订单 1')
  await expect(page.getByTestId('list-page-total')).toHaveText('共 20 条')
  await page.getByRole('button', { name: '未删除', exact: true }).click()
  await expect(page.getByTestId('order-title').first()).toHaveText('已删除订单 21')
  await expect(page.getByRole('button', { name: '未删除', exact: true })).toBeEnabled()
  await page.screenshot({
    path: '/tmp/resource-restore-ui-orders.png',
    fullPage: false,
    animations: 'disabled',
  })
})

async function roleFixture(page: Page, conflict = false) {
  await session(page, ['iam:role:manage'])
  const original = {
    id: 'old',
    code: 'old-role',
    name: '订单查看员',
    builtin: false,
    owner: false,
    memberCount: 0,
    permissions: ['order:read'],
    dataPolicies: [],
    updatedAt: '2026-11-09T01:00:00Z',
    updatedBy: 'identity-admin',
  }
  const active = [{ ...original, id: 'new', code: conflict ? original.code : 'new-role' }]
  const deleted = [original]
  await page.route('**/iam/roles**', (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/iam/roles')
      return route.fulfill(ok(url.searchParams.get('deleted') === 'true' ? deleted : active))
    if (url.pathname.endsWith('/restore')) {
      if (conflict)
        return route.fulfill({
          status: 409,
          json: {
            code: 'resource.restoreConflict',
            httpStatus: 409,
            userTip: '角色编码已被占用，请先处理冲突记录',
            traceId: 'role-conflict-trace',
            errorMessage: 'internal duplicate role code',
          },
        })
      active.push(deleted.pop()!)
      return route.fulfill(ok(null))
    }
    if (url.pathname.endsWith('/permission-tree'))
      return route.fulfill(
        ok({
          menus: [],
          others: [
            { code: 'order:read', name: '查看订单', service: 'demo-service', group: '订单' },
          ],
        }),
      )
    if (url.pathname.endsWith('/condition-options'))
      return route.fulfill(ok({ subject: [], environment: [], departments: [], positions: [] }))
    if (url.pathname.endsWith('/memberships'))
      return route.fulfill(
        ok(
          [
            {
              membershipId: 'member',
              account: 'alice',
              displayName: 'Alice',
              status: 'active',
              roleIds: [],
            },
          ],
          { number: 1, size: 20, totalElements: 1, totalPages: 1 },
        ),
      )
    return route.fulfill(ok([]))
  })
}

test('角色同名恢复配置可见，已删除角色不会进入成员分配选项', async ({ page }) => {
  await roleFixture(page)
  await page.goto('/roles')
  await page.getByRole('button', { name: '已删除', exact: true }).click()
  await expect(page.getByTestId('restore-old')).toBeVisible()
  await expect(page.getByRole('columnheader', { name: '删除时间' })).toBeVisible()
  await expect(page.getByText('identity-admin', { exact: true })).toBeVisible()
  await page.getByRole('tab', { name: '成员分配' }).click()
  await page.getByTestId('role-member-edit-member').click()
  await expect(page.getByTestId('role-option-new-role')).toBeVisible()
  await expect(page.getByTestId('role-option-old-role')).toHaveCount(0)
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('tab', { name: '角色', exact: true }).click()
  await page.getByTestId('restore-old').click()
  await page
    .getByRole('dialog', { name: '确认恢复' })
    .getByRole('button', { name: '确认恢复', exact: true })
    .click()
  await expect(page.getByRole('dialog', { name: '确认恢复' })).toBeHidden()
  await expect(page.getByTestId('restore-old')).toHaveCount(0)
  await expect(page.getByRole('status')).toHaveText('恢复成功')
  await page.getByRole('button', { name: '未删除', exact: true }).click()
  await expect(page.getByTestId('role-permissions-old-role')).toContainText('查看订单')
  await expect(page.getByTestId('role-edit-new-role')).toBeVisible()
  await page.screenshot({ path: '/tmp/resource-restore-ui-roles.png', fullPage: false })
})

test('角色编码冲突保留已删除行并显示 userTip 和 traceId', async ({ page }) => {
  await roleFixture(page, true)
  await page.goto('/roles')
  await page.getByRole('button', { name: '已删除', exact: true }).click()
  await page.getByTestId('restore-old').click()
  await page
    .getByRole('dialog', { name: '确认恢复' })
    .getByRole('button', { name: '确认恢复', exact: true })
    .click()
  await expect(page.getByRole('dialog', { name: '确认恢复' })).toBeHidden()
  await expect(page.getByRole('alert')).toContainText('角色编码已被占用，请先处理冲突记录')
  await expect(page.getByRole('alert')).toContainText('role-conflict-trace')
  await expect(page.getByRole('alert')).not.toContainText('internal duplicate')
  await expect(page.getByTestId('restore-old')).toBeVisible()
  await page.screenshot({ path: '/tmp/resource-restore-ui-role-conflict.png', fullPage: false })
})

test('部门平铺呈现父级受阻，先恢复父再恢复子，岗位允许同名恢复', async ({ page }) => {
  await session(page, ['iam:org:manage'])
  const activeDepartments = [{ id: 'head', name: '总部', parentId: null, path: 'head' }]
  const deletedDepartments = [
    { id: 'child', name: '误删子部门', parentId: 'parent', path: 'old.child' },
    { id: 'parent', name: '误删父部门', parentId: null, path: 'old' },
  ]
  const activePositions = [{ id: 'engineer', name: '工程师' }]
  const deletedPositions = [{ id: 'old-engineer', name: '工程师' }]
  await page.route('**/iam/org/**', (route) => {
    const url = new URL(route.request().url())
    const positions = url.pathname.includes('/positions')
    const active = positions ? activePositions : activeDepartments
    const deleted = positions ? deletedPositions : deletedDepartments
    if (url.pathname.endsWith('/restore')) {
      const id = url.pathname.split('/').at(-2)!
      if (id === 'child' && deletedDepartments.some((row) => row.id === 'parent'))
        return route.fulfill({
          status: 409,
          json: {
            code: 'resource.restoreBlocked',
            httpStatus: 409,
            userTip: '请先恢复上级部门',
            traceId: 'parent-blocked-trace',
          },
        })
      const index = deleted.findIndex((row) => row.id === id)
      active.push(deleted.splice(index, 1)[0]!)
      return route.fulfill(ok(null))
    }
    if (/\/(departments|positions)$/.test(url.pathname))
      return route.fulfill(ok(url.searchParams.get('deleted') === 'true' ? deleted : active))
    return route.fulfill(ok([]))
  })
  await page.goto('/org')
  await expect(page.getByTestId('dept-node-head')).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.shell-header')).toHaveCount(1)
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  )
  await page.screenshot({
    path: '/tmp/resource-restore-ui-mobile-active-baseline.png',
    fullPage: false,
    animations: 'disabled',
  })
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.getByRole('button', { name: '已删除', exact: true }).click()
  await expect(page.getByRole('tree')).toHaveCount(0)
  await expect(page.getByTestId('restore-child')).toBeVisible()
  await expect(page.getByTestId('restore-parent')).toBeVisible()
  await page.getByTestId('restore-child').click()
  await page
    .getByRole('dialog', { name: '确认恢复' })
    .getByRole('button', { name: '确认恢复', exact: true })
    .click()
  await expect(page.getByRole('dialog', { name: '确认恢复' })).toBeHidden()
  await expect(page.getByRole('alert')).toContainText('请先恢复上级部门')
  await expect(page.getByRole('alert')).toContainText('parent-blocked-trace')
  await expect(page.getByTestId('restore-child')).toBeVisible()
  await page.screenshot({
    path: '/tmp/resource-restore-ui-department-blocked.png',
    fullPage: false,
  })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByTestId('restore-child')).toBeVisible()
  await expect(page.locator('.shell-header')).toHaveCount(1)
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  )
  await page.screenshot({
    path: '/tmp/resource-restore-ui-mobile.png',
    fullPage: false,
    animations: 'disabled',
  })
  await page.setViewportSize({ width: 1280, height: 900 })
  for (const id of ['parent', 'child']) {
    await page.getByTestId(`restore-${id}`).click()
    await page
      .getByRole('dialog', { name: '确认恢复' })
      .getByRole('button', { name: '确认恢复', exact: true })
      .click()
    await expect(page.getByRole('dialog', { name: '确认恢复' })).toBeHidden()
    await expect(page.getByTestId(`restore-${id}`)).toHaveCount(0)
  }
  await page.getByRole('button', { name: '未删除', exact: true }).click()
  await expect(page.getByTestId('dept-node-parent')).toBeVisible()
  await expect(page.getByTestId('dept-node-child')).toBeVisible()
  await page.getByRole('tab', { name: '岗位', exact: true }).click()
  const panel = page.getByRole('tabpanel', { name: '岗位', exact: true })
  await panel.getByRole('button', { name: '已删除', exact: true }).click()
  await page.getByTestId('restore-old-engineer').click()
  await page
    .getByRole('dialog', { name: '确认恢复' })
    .getByRole('button', { name: '确认恢复', exact: true })
    .click()
  await expect(page.getByRole('dialog', { name: '确认恢复' })).toBeHidden()
  await expect(page.getByTestId('restore-old-engineer')).toHaveCount(0)
  await panel.getByRole('button', { name: '未删除', exact: true }).click()
  await expect(page.getByTestId('position-engineer')).toBeVisible()
  await expect(page.getByTestId('position-old-engineer')).toBeVisible()
  await expect(panel.getByRole('button', { name: '未删除', exact: true })).toBeEnabled()
  await page.screenshot({
    path: '/tmp/resource-restore-ui-positions.png',
    fullPage: false,
    animations: 'disabled',
  })
})

test('订单编号冲突保留记录并显示明确提示与 traceId', async ({ page }) => {
  await session(page, ['order:read', 'order:delete'])
  await page.route('**/demo/orders**', (route) => {
    if (route.request().method() === 'POST')
      return route.fulfill({
        status: 409,
        json: {
          code: 'resource.restoreConflict',
          httpStatus: 409,
          userTip: '订单编号已被占用，请先处理冲突记录',
          traceId: 'order-conflict-trace',
        },
      })
    return route.fulfill(
      ok([{ id: 'conflict', title: '编号冲突订单', amount: 50, status: 'closed' }], {
        number: 1,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      }),
    )
  })
  await page.goto('/apps/demo/overview')
  await page.getByRole('button', { name: '已删除', exact: true }).click()
  await page.getByRole('button', { name: '恢复', exact: true }).click()
  await page
    .getByRole('dialog', { name: '确认恢复' })
    .getByRole('button', { name: '确认恢复', exact: true })
    .click()
  await expect(page.getByRole('dialog', { name: '确认恢复' })).toBeHidden()
  await expect(page.getByRole('alert')).toContainText('订单编号已被占用，请先处理冲突记录')
  await expect(page.getByRole('alert')).toContainText('order-conflict-trace')
  await expect(page.getByTestId('order-title')).toHaveText('编号冲突订单')
  await page.screenshot({ path: '/tmp/resource-restore-ui-order-conflict.png', fullPage: false })
})

test('有删除权限时可删除订单，删除后出现在已删除视图；无更新权限不显示关闭', async ({ page }) => {
  await session(page, ['order:read', 'order:delete'])
  const active = [
    { id: 'o1', title: '待删除订单', amount: 100, status: 'unpaid' },
    { id: 'o2', title: '保留订单', amount: 200, status: 'unpaid' },
  ]
  const deleted: Record<string, unknown>[] = []
  const deletes: string[] = []
  await page.route('**/demo/orders**', (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (request.method() === 'DELETE') {
      deletes.push(url.pathname)
      const index = active.findIndex((order) => url.pathname.endsWith(`/${order.id}`))
      deleted.push({ ...active.splice(index, 1)[0], updatedAt: '2026-11-09T01:00:00Z' })
      return route.fulfill(ok(null))
    }
    const data = url.searchParams.get('deleted') === 'true' ? deleted : active
    return route.fulfill(
      ok(data, { number: 1, size: 20, totalElements: data.length, totalPages: 1 }),
    )
  })
  await page.goto('/apps/demo/overview')
  await expect(page.getByTestId('order-title')).toHaveCount(2)
  await expect(page.getByRole('button', { name: '关闭订单' })).toHaveCount(0)

  await page.getByTestId('order-delete-o1').click()
  await expect(page.getByTestId('order-title')).toHaveText(['保留订单'])
  expect(deletes).toEqual(['/demo/orders/o1'])

  await page.getByRole('button', { name: '已删除', exact: true }).click()
  await expect(page.getByTestId('order-title')).toHaveText(['待删除订单'])
  await expect(page.getByRole('button', { name: '恢复', exact: true })).toBeVisible()
  await expect(page.getByTestId('order-delete-o1')).toHaveCount(0)
})

test('普通查看权限不暴露订单恢复入口，也不能进入角色和组织管理', async ({ page }) => {
  await session(page, ['order:read'])
  await page.route('**/demo/orders?*', (route) => {
    expect(new URL(route.request().url()).searchParams.has('deleted')).toBe(false)
    return route.fulfill(ok([], { number: 1, size: 20, totalElements: 0, totalPages: 0 }))
  })
  for (const path of ['/roles', '/org']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/403$/)
    await expect(page.getByRole('button', { name: '已删除', exact: true })).toHaveCount(0)
  }
  await page.goto('/apps/demo/overview')
  await expect(page.getByRole('heading', { name: '订单', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '已删除', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '恢复', exact: true })).toHaveCount(0)
})
