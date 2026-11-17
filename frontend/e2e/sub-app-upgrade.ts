import { expect, type Page } from '@playwright/test'

// 两种宿主均操作真实保留页面，不 mock IAM/业务接口，不在运行时改写 Sub-App 路由。
export async function checkUpgradedSubApp(page: Page, host: string, orders: string[]) {
  await expect(page).toHaveURL('/apps/smoke/home')
  await expect(page.getByTestId('order-title')).toHaveText(orders)
  await page.getByRole('link', { name: '采购复核', exact: true }).click()
  await expect(page).toHaveURL('/apps/smoke/purchasing')
  await expect(page.getByRole('heading', { name: '采购复核', exact: true })).toBeVisible()
  await Promise.all([
    page.waitForResponse(
      (response) => response.url().includes('/api/smoke/orders?') && response.status() === 200,
    ),
    page.getByRole('button', { name: '刷新订单', exact: true }).click(),
  ])
  await expect(page.getByTestId('order-title')).toHaveText(orders)
  await expect(page.locator('.el-loading-mask')).toHaveCount(0)
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  // 宿主提供统一主题；Element Plus 按钮实际读取该语义色，业务页不重建主题。
  const primary = await page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue('--el-color-primary').trim(),
  )
  expect(primary.toLowerCase()).toBe('#4f46e5')
  const refresh = page.getByRole('button', { name: '刷新订单', exact: true })
  await refresh.hover()
  await expect(refresh).toHaveCSS('color', 'rgb(79, 70, 229)')
  for (const [width, height] of [
    [1440, 900],
    [1024, 768],
  ]) {
    await page.setViewportSize({ width, height })
    await expect(page.getByRole('button', { name: '刷新订单', exact: true })).toBeInViewport()
    await expect(page.getByRole('heading', { name: '采购复核', exact: true })).toBeInViewport()
    if (process.env.FORGE_TEMPLATE_EVIDENCE_DIR)
      await page.screenshot({
        path: `${process.env.FORGE_TEMPLATE_EVIDENCE_DIR}/${host}-purchasing-${width}.png`,
        animations: 'disabled',
      })
  }
  await page.getByRole('link', { name: '返回订单', exact: true }).click()
  await expect(page).toHaveURL('/apps/smoke/home')
  await expect(page.getByTestId('order-title')).toHaveText(orders)
}
