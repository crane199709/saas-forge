import { expect, test } from '@playwright/test'

test('仓库外模板的 Manifest、网关路由与成员权限', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('账号或邮箱').fill('e2e-member')
  await page.getByLabel('密码', { exact: true }).fill('Demo-password-123!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: 'E2E 租户 A', exact: true }).click()
  // 菜单由模板的 Manifest 上报产生，同时验证 IAM 已接受清单。
  await expect(page.getByTestId('menu-smoke.overview')).toBeVisible()
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
  await page.getByRole('option', { name: 'E2E 租户 B', exact: true }).click()
  await expect(page.getByText('当前租户：E2E 租户 B', { exact: true }).first()).toBeVisible()
  expect(
    (
      await page.request.post('http://127.0.0.1:18000/smoke/orders', {
        headers: await headers(),
        data: { title: '拒绝订单', amount: 1 },
      })
    ).status(),
  ).toBe(403)
})
