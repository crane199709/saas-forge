import { expect, test } from '@playwright/test'
import { checkUpgradedSubApp } from '../sub-app-upgrade'

test('仓库外模板的 Manifest、网关路由与成员权限', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  await page.goto('/')
  await page.getByLabel('账号或邮箱').fill('e2e-member')
  await page.getByLabel('密码', { exact: true }).fill('Demo-password-123!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: 'E2E 租户 A', exact: true }).click()
  // 菜单由模板的 Manifest 上报产生，同时验证 IAM 已接受清单。
  await page.getByTestId('menu-group-smoke').locator('.el-sub-menu__title').first().click()
  await expect(page.getByTestId('menu-smoke.overview')).toBeVisible()
  await page.getByTestId('menu-smoke.overview').click()
  await expect(page).toHaveURL('/apps/smoke/home')
  await expect(page.getByTestId('order-title')).toHaveText(['A 本人订单'])
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '订单', exact: true })).toBeVisible()
  if (process.env.FORGE_SUB_APP_UPGRADE === '1')
    await checkUpgradedSubApp(page, 'shell', ['A 本人订单'])
  if (process.env.FORGE_TEMPLATE_EVIDENCE_DIR)
    await page.screenshot({ path: `${process.env.FORGE_TEMPLATE_EVIDENCE_DIR}/shell.png` })
  expect((await page.request.get('http://127.0.0.1:18000/smoke/orders')).status()).toBe(401)
  const headers = async () => ({
    Authorization: `Bearer ${await page.evaluate(() => JSON.parse(localStorage.getItem('forge.tenant.session')!).accessToken)}`,
  })
  expect(
    (
      await page.request.get('http://127.0.0.1:18000/smoke/orders', { headers: await headers() })
    ).status(),
  ).toBe(200)
  expect(
    (
      await page.request.post('http://127.0.0.1:18000/smoke/orders', {
        headers: await headers(),
        data: { title: '模板订单', amount: 1 },
      })
    ).status(),
  ).toBe(201)
  expect(
    (
      await page.request.get('http://127.0.0.1:18000/smoke/internal/probe', {
        headers: await headers(),
      })
    ).status(),
  ).toBe(403)
  await page.getByTestId('tenant-switcher').click()
  // 租户名称可能先在旧文档更新；等 Shell 整页重载完成后再读取新会话。
  await Promise.all([
    page.waitForEvent('load'),
    page.getByRole('option', { name: 'E2E 租户 B', exact: true }).click(),
  ])
  await expect(page.getByTestId('workbench-greeting')).toHaveText('e2e-member · E2E 租户 B')
  await page.getByTestId('menu-group-smoke').locator('.el-sub-menu__title').first().click()
  await page.getByTestId('menu-smoke.overview').click()
  // 旧业务页保留创建时间升序；新建模板默认降序，两者都必须只显示租户 B。
  await expect(page.getByTestId('order-title')).toHaveText(
    process.env.FORGE_SUB_APP_UPGRADE === '1'
      ? ['B 本人订单', 'B 他人订单']
      : ['B 他人订单', 'B 本人订单'],
  )
  expect(
    (
      await page.request.post('http://127.0.0.1:18000/smoke/orders', {
        headers: await headers(),
        data: { title: '拒绝订单', amount: 1 },
      })
    ).status(),
  ).toBe(403)
  expect(errors).toEqual([])
})
