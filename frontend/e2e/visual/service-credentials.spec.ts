import { expect, test, type Page } from '@playwright/test'
import { ok, viewports } from './support'

test.use({ baseURL: 'http://127.0.0.1:5183' })
const token = 'visual-credential-DoNotUseInAnyEnvironment-123456'
const row = {
  id: '019a0110-0000-7000-8000-000000000001',
  service: 'orders-service',
  status: 'active',
  fingerprint: 'a1b2c3d4',
  createdAt: '2026-11-12T03:00:00Z',
  lastUsedAt: null,
}
async function open(page: Page, state = 'list') {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('console', (msg) => {
    if (msg.type() === 'error' || msg.type() === 'warning') errors.push(msg.text())
  })
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.platform.session',
      JSON.stringify({
        accessToken: 'operator-access',
        refreshToken: 'operator-refresh',
        operatorName: '值班运营',
      }),
    ),
  )
  let registered = false
  let disabled = false
  await page.route('**/platform/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api/, '')
    if (path === '/iam/platform/access')
      return route.fulfill(
        ok({
          roles: ['super-admin'],
          permissions: ['platform:service-credential:manage'],
          layout: null,
        }),
      )
    if (path.endsWith('/disable')) {
      disabled = true
      return route.fulfill(ok(null))
    }
    if (path === '/platform/service-credentials') {
      if (route.request().method() === 'POST') {
        registered = true
        return route.fulfill(ok({ credential: row, token, manifestReported: false }))
      }
      return route.fulfill(
        ok({
          service: 'forge-iam',
          available: state !== 'unavailable',
          credentials:
            state === 'empty' && !registered
              ? []
              : [
                  { ...row, status: disabled ? 'disabled' : 'active' },
                  {
                    ...row,
                    id: 'c2',
                    service: 'regional-supply-chain-order-fulfillment-service-' + 'a'.repeat(54),
                    status: 'disabled',
                    fingerprint: 'f8e7d6c5',
                    lastUsedAt: '2026-11-11T23:59:00Z',
                  },
                ],
        }),
      )
    }
    return route.fulfill(ok([]))
  })
  await page.goto('/service-credentials')
  await expect(page.getByRole('heading', { name: '服务凭证', exact: true })).toBeVisible()
  await expect(page).toHaveTitle('SaaS Forge 平台控制台')
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  return errors
}
for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    for (const state of [
      'list',
      'empty',
      'unavailable',
      'register',
      'issued',
      'disable',
      'rotate',
    ]) {
      test(state, async ({ page, context }) => {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
        const errors = await open(page, state)
        if (state === 'unavailable') {
          await expect(page.getByText('IAM 服务当前不可用，请稍后刷新重试')).toBeVisible()
          await expect(page.getByRole('button', { name: '登记凭证', exact: true })).toBeDisabled()
        } else if (state === 'empty') await expect(page.getByText('尚未登记服务凭证')).toBeVisible()
        else await expect(page.getByRole('row').filter({ hasText: 'orders-service' })).toBeVisible()
        if (state === 'register' || state === 'issued') {
          await page.getByRole('button', { name: '登记凭证', exact: true }).click()
          await page.getByLabel('服务名', { exact: true }).fill('orders-service')
          if (state === 'issued') {
            await page.getByRole('button', { name: '登记', exact: true }).click()
            await expect(page.getByTestId('service-credential-token')).toHaveText(
              'FORGE_MANIFEST_TOKEN=' + token,
            )
            await page.getByRole('button', { name: '复制片段', exact: true }).click()
            await expect(page.getByRole('button', { name: '已复制', exact: true })).toBeVisible()
            expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
              'FORGE_MANIFEST_TOKEN=' + token,
            )
          }
        }
        if (state === 'disable')
          await page
            .getByRole('row')
            .filter({ hasText: 'orders-service' })
            .getByRole('button', { name: '停用', exact: true })
            .click()
        if (state === 'rotate') {
          await page
            .getByRole('row')
            .filter({ hasText: 'orders-service' })
            .getByRole('button', { name: '轮换', exact: true })
            .click()
          await expect(
            page.getByText('先登记新凭证并部署到服务，再停用旧凭证。当前旧凭证会继续有效。'),
          ).toBeVisible()
        }
        await expect(page).toHaveScreenshot(`service-credentials-${state}-${label}.png`)
        if (state === 'issued') {
          await page.getByRole('button', { name: '已保存，关闭', exact: true }).click()
          await expect(page.getByTestId('service-credential-token')).toHaveCount(0)
          await page
            .getByRole('row')
            .filter({ hasText: 'orders-service' })
            .getByRole('button', { name: '停用', exact: true })
            .click()
          await page.getByRole('button', { name: '确认停用', exact: true }).click()
          await expect(page.getByRole('row').filter({ hasText: 'orders-service' })).toContainText(
            '已停用',
          )
          await page.reload()
          await expect(page.getByTestId('service-credential-token')).toHaveCount(0)
          await expect(page.locator('body')).not.toContainText(token)
        }
        expect(errors).toEqual([])
      })
    }
  })
}
