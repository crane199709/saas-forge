import { expect, test } from '@playwright/test'
import { ok, viewports } from './support'
test.use({ baseURL: 'http://127.0.0.1:5183' })
const at = '2026-11-12T03:00:00Z'
const healthy = {
  service: 'demo-service',
  configuration: {
    status: 'CONFIGURED',
    source: 'NACOS_CLIENT',
    checkedAt: at,
    prefix: '/demo',
    missing: [],
  },
  runtime: {
    status: 'LOADED',
    checkedAt: at,
    reporting: {
      enabled: true,
      reportedVersion: '2026.11.12-release-1234567890',
      lastSuccessAt: at,
      lastFailure: null,
      lastFailureAt: null,
      attempts: 1,
      phase: 'REPORTED',
    },
  },
  request: { status: 'VERIFIED', checkedAt: at },
}
const degraded = {
  service: 'east-region-supply-chain-collaboration-service',
  configuration: {
    status: 'UNKNOWN',
    source: 'NACOS_CLIENT',
    checkedAt: at,
    missing: ['NACOS_UNAVAILABLE'],
  },
  runtime: {
    status: 'LOADED',
    checkedAt: at,
    reporting: {
      enabled: true,
      reportedVersion: null,
      lastSuccessAt: null,
      lastFailure: 'VERSION_CONFLICT',
      lastFailureAt: at,
      attempts: 4,
      phase: 'BACKGROUND',
    },
  },
  request: { status: 'NOT_CHECKED', detail: 'ROUTE_PREFIX_MISSING' },
}
for (const [viewport, width, height] of viewports) {
  test.describe(viewport, () => {
    test.use({ viewport: { width, height } })
    for (const state of ['mixed', 'empty', 'error'] as const) {
      test(`onboarding ${state}`, async ({ page }) => {
        const errors: string[] = []
        page.on('pageerror', (error) => errors.push(error.message))
        page.on('console', (message) => {
          if (message.type() === 'error' || message.type() === 'warning')
            errors.push(message.text())
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
        let reads = 0
        await page.route('**/platform/**', (route) => {
          const path = new URL(route.request().url()).pathname.replace(/^\/api/, '')
          if (path === '/iam/platform/access')
            return route.fulfill(ok({ roles: [], permissions: ['platform:onboarding:read'] }))
          if (path === '/platform/onboarding') {
            expect(route.request().method()).toBe('GET')
            reads++
            if (state === 'error')
              return route.fulfill({
                status: 503,
                json: {
                  code: 'system.unavailable',
                  httpStatus: 503,
                  userTip: '诊断服务暂不可用，请稍后刷新',
                  traceId: 'onboarding-qa',
                },
              })
            return route.fulfill(
              ok({
                directoryAvailable: state === 'empty',
                services: state === 'empty' ? [] : [healthy, degraded],
              }),
            )
          }
          return route.fulfill(ok([]))
        })
        await page.goto('/onboarding')
        await expect(page).toHaveURL(/\/onboarding$/)
        await expect(page.getByRole('heading', { name: '服务接入诊断' })).toBeVisible()
        expect(await page.title()).not.toBe('')
        if (state === 'mixed') {
          await expect(page.getByText('请求已验证', { exact: true })).toBeVisible()
          await expect(page.getByText('配置未知', { exact: true })).toBeVisible()
          await page.getByRole('button', { name: '刷新', exact: true }).click()
          await expect.poll(() => reads).toBe(2)
          await page.locator('.el-table__expand-icon').first().click()
          await expect(
            page.getByText('2026.11.12-release-1234567890', { exact: true }),
          ).toBeVisible()
        } else if (state === 'empty') await expect(page.getByText('暂无可诊断的服务')).toBeVisible()
        else await expect(page.getByText('诊断服务暂不可用，请稍后刷新')).toBeVisible()
        await expect(page).toHaveScreenshot(`onboarding-${state}-${viewport}.png`)
        expect(errors).toEqual(
          state === 'error'
            ? errors.filter((message) => message.includes('503 (Service Unavailable)'))
            : [],
        )
      })
    }
  })
}
