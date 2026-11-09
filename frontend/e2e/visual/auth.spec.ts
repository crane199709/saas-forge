import { expect, test } from '@playwright/test'
import { ok, viewports } from './support'

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    test('租户登录', async ({ page }) => {
      await page.goto('/login')
      await expect(page.getByTestId('forgot-password')).toBeVisible()
      await expect(page).toHaveScreenshot(`login-tenant-${label}.png`)
    })

    test('注册', async ({ page }) => {
      await page.goto('/login')
      await page.getByTestId('mode-register').click()
      await expect(page.locator('input[name="register-account"]')).toBeVisible()
      await expect(page).toHaveScreenshot(`register-${label}.png`)
    })

    test('忘记密码', async ({ page }) => {
      await page.goto('/forgot-password')
      await expect(page.locator('input[name="reset-target"]')).toBeVisible()
      await expect(page).toHaveScreenshot(`forgot-password-${label}.png`)
    })

    test('接受邀请', async ({ page }) => {
      await page.route('**/iam/auth/invitations/preview', (route) =>
        route.fulfill(ok({ tenantName: '示例科技有限公司', requiresCredentials: true })),
      )
      await page.goto('/invitations/accept?invitation=invite-token')
      await expect(page.locator('input[name="invitation-account"]')).toBeVisible()
      await expect(page).toHaveScreenshot(`invitation-${label}.png`)
    })

    test('平台登录', async ({ page }) => {
      await page.goto('http://127.0.0.1:5183/login')
      await expect(page.locator('input[name="account"]')).toBeVisible()
      await expect(page).toHaveScreenshot(`login-platform-${label}.png`)
    })
  })
}
