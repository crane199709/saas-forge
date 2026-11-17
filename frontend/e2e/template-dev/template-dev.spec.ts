import { expect, test } from '@playwright/test'

test('仓库外生成工程的独立开发宿主可登录并读取真实订单', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto('/')
  await expect(page).toHaveTitle('smoke Sub-App 开发宿主')
  await expect(page.getByText('Sub-App 开发宿主 · smoke')).toBeVisible()
  await page.getByLabel('账号', { exact: true }).fill('e2e-member')
  await page.getByLabel('密码', { exact: true }).fill('Demo-password-123!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText('e2e-member @ E2E 租户 A')).toBeVisible()
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  // 页面初始化时尚未登录；改变排序通过公开控件再次读取，响应来自同一隔离服务。
  await page.getByRole('region', { name: '订单' }).getByText('最新创建', { exact: true }).click()
  await Promise.all([
    page.waitForResponse(
      (result) => result.url().includes('/api/smoke/orders?') && result.status() === 200,
    ),
    page.getByRole('option', { name: '金额从高到低', exact: true }).click(),
  ])
  // 订单响应先于 Element Plus 的遮罩/下拉关闭过渡结束，终态截图须等两者退出。
  await expect(page.locator('.el-loading-mask')).toBeHidden()
  await expect(page.locator('.el-select-dropdown')).toBeHidden()
  await expect(page.getByTestId('order-title')).toHaveText(['A 本人订单', '模板订单'])
  await expect(page).toHaveURL('/apps/smoke/home')
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  expect(errors).toEqual([])
  if (process.env.FORGE_TEMPLATE_EVIDENCE_DIR)
    await page.screenshot({
      path: `${process.env.FORGE_TEMPLATE_EVIDENCE_DIR}/dev-host.png`,
      animations: 'disabled',
    })
})
