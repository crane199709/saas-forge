import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'

const evidence = '/private/tmp/service-onboarding-qa'
const errors: string[] = []

test.beforeAll(() => mkdir(evidence, { recursive: true }).then(() => {}))
test.beforeEach(async ({ page }) => {
  errors.length = 0
  await page.setViewportSize({ width: 1440, height: 1000 })
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
})
test.afterEach(() => expect(errors).toEqual([]))

// 本文件只由 deploy/e2e/run.sh 的隔离接入验收模式启动；不拦截请求、不注入会话。
test('真实控制台登记凭证、下一请求停用与一次性明文', async ({ page }) => {
  await page.goto('/service-credentials')
  await page.locator('input[name="account"]').fill('e2e-onboarding')
  await page.getByLabel('密码', { exact: true }).fill('Demo-password-123!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '服务凭证', exact: true })).toBeVisible()
  await expect(page).toHaveTitle('SaaS Forge 平台控制台')

  await page.getByRole('button', { name: '登记凭证', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '登记凭证', exact: true })
  await dialog.getByLabel('服务名', { exact: true }).fill('e2e-credential-service')
  await dialog.getByRole('button', { name: '登记', exact: true }).click()
  const fragment = page.getByTestId('service-credential-token')
  await expect(fragment).toBeVisible()
  const snippet = (await fragment.textContent())!.trim()
  const credential = snippet.replace(/^FORGE_MANIFEST_TOKEN=/, '')
  expect(credential.length).toBeGreaterThanOrEqual(43)
  const headers = { Authorization: `Bearer ${credential}` }
  expect(
    (await page.request.get('http://127.0.0.1:18101/internal/services', { headers })).status(),
  ).toBe(200)

  // 刷新后既不能重新查询明文，也不能在页面状态中找回它。
  await page.reload()
  await expect(fragment).toHaveCount(0)
  const row = page.getByRole('row').filter({ hasText: 'e2e-credential-service' })
  await expect(row).toContainText('启用')
  await row.getByRole('button', { name: '停用', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认停用', exact: true }).click()
  await expect(row).toContainText('已停用')
  expect(
    (await page.request.get('http://127.0.0.1:18101/internal/services', { headers })).status(),
  ).toBe(401)
  await expect(page.locator('body')).not.toContainText(credential)
  await page.screenshot({ path: `${evidence}/real-credential-disabled.png` })
  expect(
    (await page.request.get('http://127.0.0.1:18000/iam/internal/onboarding-status')).status(),
  ).toBe(403)
})

test('真实 Shell 与远程子应用业务请求返回 JSON', async ({ page }) => {
  await page.goto('http://127.0.0.1:15174/')
  await page.getByLabel('账号或邮箱').fill('e2e-member')
  await page.getByLabel('密码', { exact: true }).fill('Demo-password-123!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: 'E2E 租户 A', exact: true }).click()
  await expect(page.getByTestId('menu-demo.overview')).toBeVisible()
  const response = page.waitForResponse((r) => r.url().includes('/api/demo/orders'))
  await page.getByTestId('menu-demo.overview').click()
  const orders = await response
  expect(orders.status()).toBe(200)
  expect(orders.headers()['content-type']).toContain('application/json')
  expect((await orders.json()).data).toBeInstanceOf(Array)
  await expect(page.getByTestId('order-title')).toHaveText(['A 本人订单'])
  await page.reload()
  await expect(page.getByTestId('order-title')).toHaveText(['A 本人订单'])
  await expect(page.locator('vite-error-overlay, #webpack-dev-server-client-overlay')).toHaveCount(
    0,
  )
  await page.screenshot({ path: `${evidence}/real-shell-demo.png` })
})
