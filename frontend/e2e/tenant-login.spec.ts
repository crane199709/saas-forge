import { expect, test } from '@playwright/test'

// 进入租户后 Shell 读取权限与菜单；这些用例不涉及菜单，返回空权限。
test.beforeEach(async ({ page }) => {
  await page.route('**/iam/auth/access', (route) =>
    route.fulfill({
      json: { code: 'ok', httpStatus: 200, data: { permissions: [], menus: [] }, traceId: 'trace' },
    }),
  )
})

function ok(data?: unknown) {
  return { json: { code: 'ok', httpStatus: 200, data, traceId: 'trace-ok' } }
}

function fail(code: string, status: number, userTip: string) {
  return {
    status,
    headers: { 'X-Trace-Id': 'trace-' + status },
    json: { code, httpStatus: status, errorMessage: code, userTip, traceId: 'trace-' + status },
  }
}

test('登录页显示统一失败提示，成功后显示上下文并在刷新页面后恢复', async ({ page }) => {
  await page.route('**/iam/auth/login', async (route) => {
    const body = route.request().postDataJSON()
    if (body.password !== 'correct-password') {
      await route.fulfill(fail('auth.failed', 401, '账号或密码错误，或登录会话已失效'))
      return
    }
    await route.fulfill(
      ok({
        clientId: 'tenant-app',
        redirectUri: 'http://127.0.0.1:5174/',
        memberships: [
          { membershipId: 'membership-1', tenantId: 'tenant-1', tenantName: '演示租户' },
        ],
      }),
    )
  })
  await page.route('**/iam/oauth2/authorize?*', async (route) => {
    const state = new URL(route.request().url()).searchParams.get('state')
    await route.fulfill({
      status: 302,
      headers: { Location: '/?code=browser-code&state=' + state },
    })
  })
  await page.route('**/iam/auth/exchange', async (route) => {
    const body = route.request().postDataJSON()
    expect(body.code).toBe('browser-code')
    expect(body.codeVerifier).toHaveLength(43)
    await route.fulfill(
      ok({
        accessToken: 'browser-access',
        refreshToken: 'browser-refresh',
        tokenType: 'Bearer',
        expiresIn: 900,
        identityName: '演示身份',
        tenantId: 'tenant-1',
        tenantName: '演示租户',
        membershipId: 'membership-1',
      }),
    )
  })
  await page.route('**/iam/auth/memberships', async (route) => {
    await route.fulfill(
      ok([{ membershipId: 'membership-1', tenantId: 'tenant-1', tenantName: '演示租户' }]),
    )
  })
  await page.route('**/demo/context', async (route) => {
    expect(route.request().headers().authorization).toBe('Bearer browser-access')
    await route.fulfill(
      ok({
        identityId: 'identity-1',
        tenantId: 'tenant-1',
        membershipId: 'membership-1',
        version: 1,
      }),
    )
  })
  let logoutCount = 0
  await page.route('**/iam/auth/logout', async (route) => {
    expect(route.request().method()).toBe('POST')
    logoutCount += 1
    await route.fulfill(logoutCount === 1 ? fail('system.error', 500, '系统繁忙') : ok())
  })
  await page.goto('/')
  await page.getByLabel('账号或邮箱').fill('demo')
  await page.getByLabel('密码', { exact: true }).fill('incorrect')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('账号或密码错误')
  await expect(page.getByRole('alert')).toContainText('trace-401')
  await page.getByLabel('密码', { exact: true }).fill('correct-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText('当前身份：演示身份')).toBeVisible()
  await expect(page.getByText('当前租户：演示租户')).toBeVisible()
  await expect(page.getByText('membership-1', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByText('membership-1', { exact: true })).toBeVisible()
  await page.screenshot({ path: '/tmp/forge-03-login.png' })
  await page.getByRole('button', { name: '重新登录' }).click()
  await expect(page.getByLabel('账号或邮箱')).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('浏览器仍可能保持登录')
  await page.getByRole('button', { name: '重试退出' }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  expect(logoutCount).toBe(2)
  expect(await page.evaluate(() => sessionStorage.getItem('forge.tenant.session'))).toBeNull()
})

test('多租户身份先选择租户，切换后重新加载并只看到新租户的上下文', async ({ page }) => {
  const memberships = [
    { membershipId: 'membership-a', tenantId: 'tenant-a', tenantName: '租户 A' },
    { membershipId: 'membership-b', tenantId: 'tenant-b', tenantName: '租户 B' },
  ]
  const sessions: Record<string, object> = {
    'membership-a': { accessToken: 'access-a', refreshToken: 'refresh-a', ...memberships[0] },
    'membership-b': { accessToken: 'access-b', refreshToken: 'refresh-b', ...memberships[1] },
  }
  await page.route('**/iam/auth/login', (route) =>
    route.fulfill(
      ok({ clientId: 'tenant-app', redirectUri: 'http://127.0.0.1:5174/', memberships }),
    ),
  )
  await page.route('**/iam/oauth2/authorize?*', async (route) => {
    const state = new URL(route.request().url()).searchParams.get('state')
    await route.fulfill({ status: 302, headers: { Location: '/?code=c&state=' + state } })
  })
  await page.route('**/iam/auth/exchange', (route) =>
    route.fulfill(
      ok({
        tokenType: 'Bearer',
        expiresIn: 900,
        identityName: '多成员身份',
        ...sessions[route.request().postDataJSON().membershipId],
      }),
    ),
  )
  let switchBody: { refreshToken?: string; membershipId?: string } = {}
  await page.route('**/iam/auth/switch', (route) => {
    switchBody = route.request().postDataJSON()
    return route.fulfill(
      ok({
        tokenType: 'Bearer',
        expiresIn: 900,
        identityName: '多成员身份',
        ...sessions['membership-b'],
      }),
    )
  })
  await page.route('**/iam/auth/memberships', (route) => route.fulfill(ok(memberships)))
  await page.route('**/demo/context', (route) => {
    const tenant = route.request().headers().authorization === 'Bearer access-b' ? 'b' : 'a'
    return route.fulfill(
      ok({
        identityId: 'identity-1',
        tenantId: 'tenant-' + tenant,
        membershipId: 'membership-' + tenant,
        version: 1,
      }),
    )
  })
  await page.goto('/')
  await page.getByLabel('账号或邮箱').fill('multi')
  await page.getByLabel('密码', { exact: true }).fill('correct-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '选择租户' })).toBeVisible()
  await page.getByRole('button', { name: '租户 A' }).click()
  await expect(page.getByText('当前租户：租户 A')).toBeVisible()
  await expect(page.getByText('membership-a', { exact: true })).toBeVisible()

  await page.getByTestId('tenant-switcher').click()
  await page.getByRole('option', { name: '租户 B' }).click()
  await expect(page.getByText('当前租户：租户 B')).toBeVisible()
  await expect(page.getByText('membership-b', { exact: true })).toBeVisible()
  await expect(page.getByText('membership-a', { exact: true })).toHaveCount(0)
  expect(switchBody).toEqual({ refreshToken: 'refresh-a', membershipId: 'membership-b' })
})
