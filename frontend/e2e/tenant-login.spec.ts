import { expect, test } from '@playwright/test'

test('登录页显示统一失败提示，成功后显示上下文并在刷新页面后恢复', async ({ page }) => {
  await page.route('**/iam/auth/login', async (route) => {
    const body = route.request().postDataJSON()
    if (body.password !== 'correct-password') {
      await route.fulfill({ status: 401, json: { code: 'auth.failed' } })
      return
    }
    await route.fulfill({ json: { clientId: 'tenant-app', redirectUri: 'http://127.0.0.1:5174/' } })
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
    await route.fulfill({
      json: {
        accessToken: 'browser-access',
        refreshToken: 'browser-refresh',
        tokenType: 'Bearer',
        expiresIn: 900,
        identityName: '演示身份',
        tenantName: '演示租户',
      },
    })
  })
  await page.route('**/demo/context', async (route) => {
    expect(route.request().headers().authorization).toBe('Bearer browser-access')
    await route.fulfill({
      json: {
        identityId: 'identity-1',
        tenantId: 'tenant-1',
        membershipId: 'membership-1',
        version: 1,
      },
    })
  })
  let logoutCount = 0
  await page.route('**/iam/auth/logout', async (route) => {
    expect(route.request().method()).toBe('POST')
    logoutCount += 1
    await route.fulfill({ status: logoutCount === 1 ? 500 : 204 })
  })
  await page.goto('/')
  await page.getByLabel('账号或邮箱').fill('demo')
  await page.getByLabel('密码', { exact: true }).fill('incorrect')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('账号或密码错误')
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
