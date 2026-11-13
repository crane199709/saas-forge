import { expect, test } from '@playwright/test'
import { ok, openAsAdmin, viewports } from './support'

const dictionary = {
  service: 'crm-service',
  code: 'customer-source',
  name: '客户来源',
  extensible: true,
  items: [
    {
      code: 'web',
      name: '官方网站与线上推广渠道',
      sort: 1,
      custom: false,
      status: 'enabled',
      nameConflict: false,
    },
    {
      code: 'custom.expo',
      name: '华东医疗行业年度展会',
      sort: 5,
      custom: true,
      status: 'disabled',
      nameConflict: false,
    },
    { code: 'fax', name: '传真', sort: 3, custom: false, status: 'retired', nameConflict: false },
  ],
}
const versions = [
  {
    version: 3,
    summary: '停用 custom.expo',
    changedBy: '019a0110-0000-7000-8000-0000000002c2',
    changedAt: '2026-11-13T02:30:00Z',
  },
  {
    version: 2,
    summary: '修改 custom.expo 显示名与排序',
    changedBy: '019a0110-0000-7000-8000-0000000002c2',
    changedAt: '2026-11-12T08:15:00Z',
  },
  { version: 1, summary: '新增 custom.expo', changedBy: null, changedAt: '2026-11-11T01:00:00Z' },
]
const original = { code: 'custom.expo', custom: true, name: '行业展会', sort: 2, enabled: true }
const latest = { ...original, name: '华东医疗行业年度展会', sort: 5, enabled: false }
const inherited = { code: 'fax', custom: false, name: null, sort: null, enabled: false }
const base = '/iam/reference-data/dictionaries/crm-service/customer-source/history'

for (const [viewport, width, height] of viewports) {
  test.describe(`dictionary history ${viewport}`, () => {
    test.use({ viewport: { width, height } })
    for (const state of ['list', 'empty', 'detail', 'diff'] as const) {
      test(state, async ({ page }) => {
        const errors: string[] = []
        page.on('pageerror', (error) => errors.push(error.message))
        page.on('console', (message) => {
          if (message.type() === 'error') errors.push(message.text())
        })
        await openAsAdmin(
          page,
          '/dictionaries',
          (path, route) => {
            if (path === '/iam/reference-data/dictionaries') return route.fulfill(ok([dictionary]))
            if (path === base) return route.fulfill(ok(state === 'empty' ? [] : versions))
            if (path === `${base}/1`)
              return route.fulfill(
                ok({
                  ...versions[2],
                  snapshot: [original, inherited],
                  current: dictionary,
                  manifest: {
                    code: dictionary.code,
                    name: dictionary.name,
                    items: [{ code: 'web', name: '官网' }],
                  },
                }),
              )
            if (path === `${base}/diff`)
              return route.fulfill(
                ok({
                  from: 2,
                  to: 3,
                  added: [
                    {
                      code: 'custom.direct',
                      custom: true,
                      name: '客户直接联系',
                      sort: 6,
                      enabled: true,
                    },
                  ],
                  removed: [inherited],
                  changed: [{ before: original, after: latest }],
                }),
              )
          },
          { permissions: ['iam:dictionary-history:read'] },
        )
        await page.getByRole('tab', { name: '变更历史' }).click()
        if (state === 'empty') await expect(page.getByText('暂无变更历史')).toBeVisible()
        else await expect(page.getByTestId('history-detail-1')).toBeVisible()
        if (state === 'detail') {
          await page.getByTestId('history-detail-1').click()
          await expect(page.getByTestId('history-snapshot')).toContainText('行业展会')
          await expect(page.getByTestId('history-current')).toContainText('官方网站与线上推广渠道')
          await expect(page.getByTestId('history-manifest')).toContainText('官网')
        }
        if (state === 'diff') {
          await page.getByTestId('history-compare').click()
          await expect(page.getByTestId('history-changed')).toContainText('华东医疗行业年度展会')
        }
        await expect(page).toHaveURL(/\/dictionaries$/)
        await expect(page.getByRole('heading', { name: '字典', exact: true })).toContainText('字典')
        await expect(page.locator('vite-error-overlay')).toHaveCount(0)
        expect(errors).toEqual([])
        await expect(page).toHaveScreenshot(`dictionary-history-${state}-${viewport}.png`, {
          fullPage: true,
        })
      })
    }
  })
}
