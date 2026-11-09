import { expect, test, type Page, type Route } from '@playwright/test'

// Browser plugin not available：按项目 Playwright 工作流，以公开 HTTP 边界模拟后端。
const problems = new WeakMap<Page, string[]>()
test.beforeEach(({ page }) => {
  const errors: string[] = []
  problems.set(page, errors)
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (!['error', 'warning'].includes(message.type())) return
    if (
      /Failed to load resource: the server responded with a status of (400|401|403|503)/.test(
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
const a = { membershipId: 'ma', tenantId: 'ta', tenantName: '租户 A' }
const b = { membershipId: 'mb', tenantId: 'tb', tenantName: '租户 B' }
const session = (membership = a) => ({
  accessToken: `access-${membership.membershipId}`,
  refreshToken: `refresh-${membership.membershipId}`,
  tokenType: 'Bearer',
  expiresIn: 900,
  identityName: '测试身份',
  ...membership,
})
const ok = (data: unknown) => ({ json: { code: 'ok', httpStatus: 200, data, traceId: 'ip-ok' } })
const denied = () => ({
  status: 403,
  json: {
    code: 'tenant.ipNotAllowed',
    httpStatus: 403,
    userTip: '当前来源不在租户 IP 允许列表内',
    traceId: 'ip-denied',
  },
})
async function seed(page: Page) {
  await page.addInitScript((value) => {
    if (!localStorage.getItem('forge.tenant.session'))
      localStorage.setItem('forge.tenant.session', JSON.stringify(value))
  }, session())
}
async function common(page: Page) {
  await page.route('**/iam/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/iam/auth/access')
      return route.fulfill(ok({ permissions: ['iam:security:manage'], menus: [], subApps: [] }))
    if (path === '/iam/auth/memberships') {
      expect(route.request().method()).toBe('POST')
      expect(route.request().headers().authorization).toBeUndefined()
      expect(route.request().postDataJSON().refreshToken).toMatch(/^refresh-/)
      return route.fulfill(ok([a, b]))
    }
    if (path.endsWith('/unread-count')) return route.fulfill(ok({ count: 0 }))
    if (path === '/iam/auth/switch') return route.fulfill(ok(session(b)))
    if (path === '/iam/auth/logout') return route.fulfill(ok(null))
    return route.fulfill(ok([]))
  })
  await page.route('**/demo/context', (route) =>
    route.fulfill(ok({ identityId: 'i', tenantId: 'tb', membershipId: 'mb', version: 1 })),
  )
}
for (const mobile of [false, true]) {
  test(`安全设置显示当前IP、保存和逐条校验 ${mobile ? 'mobile' : 'desktop'}`, async ({ page }) => {
    if (mobile) await page.setViewportSize({ width: 390, height: 844 })
    await seed(page)
    await common(page)
    const writes: unknown[] = []
    await page.route('**/iam/security-settings', (route) =>
      route.fulfill(ok({ mfaRequired: false })),
    )
    await page.route('**/iam/security-settings/ip-allowlist', (route) => {
      if (route.request().method() === 'PUT') {
        const body = route.request().postDataJSON()
        writes.push(body)
        if (body.rules[1] === 'invalid')
          return route.fulfill({
            status: 400,
            json: {
              code: 'validation.failed',
              httpStatus: 400,
              userTip: '请检查规则',
              traceId: 'bad-rule',
              errors: [{ field: 'rules[1]', code: 'invalid', userTip: '请输入 IP 地址或 CIDR' }],
            },
          })
        return route.fulfill(ok({ rules: ['192.0.2.1', '2001:db8::/64'], currentIp: '192.0.2.1' }))
      }
      return route.fulfill(ok({ rules: ['192.0.2.1'], currentIp: '192.0.2.1' }))
    })
    await page.goto('/security-settings')
    await expect(page.getByRole('heading', { name: '租户 IP 允许列表' })).toBeVisible()
    await expect(page.getByText('保存后立即影响', { exact: false })).toContainText('每次请求')
    await page.getByRole('button', { name: '添加条目' }).click()
    await page.getByLabel('条目 2', { exact: true }).fill('invalid')
    await page.getByTestId('ip-save').click()
    await expect(page.getByTestId('ip-rule-1')).toContainText('请输入 IP 地址或 CIDR')
    await expect(page.getByLabel('条目 2', { exact: true })).toBeFocused()
    await page.getByLabel('条目 2', { exact: true }).fill('2001:db8::1/64')
    await page.getByTestId('ip-save').click()
    await expect(page.getByRole('status')).toContainText('租户 IP 允许列表已保存')
    await expect(page.getByLabel('条目 2', { exact: true })).toHaveValue('2001:db8::/64')
    await expect(page.getByTestId('ip-rule-1')).not.toHaveClass(/is-error/)
    expect(writes[1]).toEqual({ rules: ['192.0.2.1', '2001:db8::1/64'] })
    await expect(page).toHaveURL(/\/security-settings$/)
    await page.screenshot({
      path: `/private/tmp/tenant-ip-allowlist-settings-${mobile ? 'mobile' : 'desktop'}.png`,
      fullPage: true,
    })
  })
}

test('首屏权限被拒、整页刷新仍显示专用页面，停止轮询并可切换 B', async ({ page }) => {
  await seed(page)
  await common(page)
  let refreshes = 0
  let unread = 0
  await page.route('**/iam/auth/access', (route) =>
    route.fulfill(
      route.request().headers().authorization === 'Bearer access-ma'
        ? denied()
        : ok({ permissions: [], menus: [], subApps: [] }),
    ),
  )
  await page.route('**/iam/auth/refresh', (route) => {
    refreshes++
    return route.fulfill(denied())
  })
  await page.route(/\/iam\/(notifications|announcements)\/unread-count$/, (route) => {
    unread++
    return route.fulfill(ok({ count: 0 }))
  })
  await page.goto('/security-settings')
  await expect(page).toHaveURL(/\/ip-not-allowed$/)
  await expect(page.getByRole('heading', { name: '当前 IP 无法访问该租户' })).toBeVisible()
  expect(
    await page.evaluate(
      () => JSON.parse(localStorage.getItem('forge.tenant.session')!).refreshToken,
    ),
  ).toBe('refresh-ma')
  await page.reload()
  await expect(page.getByRole('button', { name: '租户 B', exact: true })).toBeVisible()
  await page.clock.install()
  await page.clock.fastForward(125_000)
  expect(refreshes).toBe(0)
  expect(unread).toBe(0)
  await page.screenshot({ path: '/private/tmp/tenant-ip-allowlist-denied-desktop.png' })
  await page.getByRole('button', { name: '租户 B', exact: true }).click()
  await expect(page.getByText('当前租户：租户 B')).toBeVisible()
  await expect(page).toHaveURL('http://127.0.0.1:5174/')
})

for (const source of ['api', 'refresh'] as const) {
  test(`${source} IP 拒绝无刷新循环且能退出`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await seed(page)
    await common(page)
    let refreshes = 0
    await page.route('**/demo/context', (route) =>
      route.fulfill(
        source === 'api'
          ? denied()
          : { status: 401, json: { code: 'auth.unauthenticated', httpStatus: 401 } },
      ),
    )
    await page.route('**/iam/auth/refresh', (route) => {
      refreshes++
      return route.fulfill(denied())
    })
    await page.goto('/')
    await expect(page).toHaveURL(/\/ip-not-allowed$/)
    await expect(page.getByRole('button', { name: '租户 B', exact: true })).toBeVisible()
    await page.clock.install()
    await page.clock.fastForward(125_000)
    expect(refreshes).toBe(source === 'api' ? 0 : 1)
    await page.screenshot({ path: `/private/tmp/tenant-ip-allowlist-denied-${source}-mobile.png` })
    await page.getByTestId('ip-denied-logout').click()
    await expect(page).toHaveURL(/\/login$/)
    expect(await page.evaluate(() => localStorage.getItem('forge.tenant.session'))).toBeNull()
  })
}

for (const multi of [false, true]) {
  test(`${multi ? '多' : '单'}Tenant exchange IP 拒绝进入专用页`, async ({ page }) => {
    await common(page)
    await page.route('**/iam/auth/login', (route) =>
      route.fulfill(
        ok({
          clientId: 'tenant-app',
          redirectUri: 'http://127.0.0.1:5174/',
          memberships: multi ? [a, b] : [a],
        }),
      ),
    )
    await page.route('**/iam/oauth2/authorize?*', (route) => {
      const state = new URL(route.request().url()).searchParams.get('state')
      return route.fulfill({ status: 302, headers: { Location: '/?code=c&state=' + state } })
    })
    await page.route('**/iam/auth/exchange', (route) =>
      route.fulfill(
        route.request().postDataJSON().membershipId === 'ma' ? denied() : ok(session(b)),
      ),
    )
    await page.goto('/login')
    await page.getByLabel('账号或邮箱').fill('test')
    await page.getByLabel('密码', { exact: true }).fill('test-password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    if (multi) await page.getByRole('button', { name: '租户 A', exact: true }).click()
    await expect(page).toHaveURL(/\/ip-not-allowed$/)
    await expect(page.getByRole('heading', { name: '当前 IP 无法访问该租户' })).toBeVisible()
    if (multi) {
      await page.getByRole('button', { name: '租户 B', exact: true }).click()
      await expect(page.getByText('当前租户：租户 B')).toBeVisible()
    } else {
      await page.getByTestId('ip-denied-logout').click()
      await expect(page).toHaveURL(/\/login$/)
    }
  })
}

test('切换目标被拒可返回有效的当前租户', async ({ page }) => {
  await seed(page)
  await common(page)
  await page.route('**/iam/auth/switch', (route) => route.fulfill(denied()))
  await page.goto('/')
  await page.getByTestId('tenant-switcher').click()
  await page.getByRole('option', { name: '租户 B' }).click()
  await expect(page).toHaveURL(/\/ip-not-allowed$/)
  await expect(page.getByRole('button', { name: '返回当前租户' })).toBeVisible()
  await page.getByRole('button', { name: '返回当前租户' }).click()
  await expect(page.getByText('当前租户：租户 A')).toBeVisible()
})

for (const manage of [true, false]) {
  test(`平台紧急清除 ${manage ? '必填原因、取消和确认' : '只读无入口'}`, async ({ page }) => {
    if (manage) await page.setViewportSize({ width: 390, height: 844 })
    await page.addInitScript(() =>
      localStorage.setItem(
        'forge.platform.session',
        JSON.stringify({
          accessToken: 'operator',
          refreshToken: 'operator-refresh',
          operatorName: '值班运营',
        }),
      ),
    )
    await page.route('**/iam/platform/access', (route) =>
      route.fulfill(
        ok({
          roles: [],
          permissions: ['platform:tenant:read', ...(manage ? ['platform:tenant:manage'] : [])],
        }),
      ),
    )
    const tenant = {
      id: 'ta',
      name: '租户 A',
      status: 'active',
      adminContact: 'a@example.com',
      region: null,
      planId: null,
      expiresAt: null,
      purgeAt: null,
      purgedAt: null,
      createdAt: '2026-11-01T00:00:00Z',
    }
    await page.route('**/platform/plans', (route) => route.fulfill(ok([])))
    await page.route('**/platform/tenants?*', (route) =>
      route.fulfill({
        json: {
          ...ok([tenant]).json,
          meta: { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } },
        },
      }),
    )
    await page.route('**/platform/tenants/ta', (route) =>
      route.fulfill(ok({ tenant, initializations: [], allowedTransitions: [], statusChanges: [] })),
    )
    const writes: unknown[] = []
    await page.route('**/iam/platform/tenants/ta/ip-allowlist/clear', (route: Route) => {
      writes.push(route.request().postDataJSON())
      return route.fulfill(ok(null))
    })
    await page.goto('http://127.0.0.1:5173/tenants')
    await page.getByRole('button', { name: '详情', exact: true }).click()
    if (!manage) {
      await expect(page.getByTestId('ip-clear-open')).toHaveCount(0)
      expect(writes).toHaveLength(0)
      return
    }
    await page.getByTestId('ip-clear-open').click()
    const dialog = page.getByRole('dialog', { name: '确认紧急清除 IP 允许列表' })
    await expect(dialog).toBeVisible()
    await expect(page.getByTestId('ip-clear-confirm')).toBeDisabled()
    await page.getByLabel('清除原因（必填）').fill('   ')
    await expect(page.getByTestId('ip-clear-confirm')).toBeDisabled()
    await dialog.getByRole('button', { name: '取消', exact: true }).click()
    expect(writes).toHaveLength(0)
    await page.getByTestId('ip-clear-open').click()
    await page.getByLabel('清除原因（必填）').fill('管理员误配置，请求恢复')
    await page.screenshot({ path: '/private/tmp/tenant-ip-allowlist-clear-mobile.png' })
    await page.getByTestId('ip-clear-confirm').click()
    await expect(page.getByText('租户 IP 允许列表已清除。', { exact: true })).toBeVisible()
    expect(writes).toEqual([{ reason: '管理员误配置，请求恢复' }])
    await expect(page).toHaveURL('http://127.0.0.1:5173/tenants')
  })
}
