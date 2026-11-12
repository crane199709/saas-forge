import { expect, test } from '@playwright/test'
import { ok, onePage, openAsAdmin, viewports } from './support'

const pending = {
  type: 'IMPORT',
  status: 'AWAITING_CONFIRM',
  rowCount: 999999,
  membershipId: 'm',
  heartbeatAt: '2026-11-10T01:00:00Z',
}
const states = {
  pending: [
    { ...pending, id: 'owned' },
    { ...pending, id: 'other', membershipId: 'other', rowCount: 18 },
  ],
  expired: [
    { ...pending, id: 'late', rowCount: 24, heartbeatAt: '2026-11-08T01:00:00Z' },
    {
      ...pending,
      id: 'expired',
      status: 'FAILED',
      rowCount: 0,
      errorCode: 'io.confirmationExpired',
      resultAvailable: false,
    },
  ],
  paging: [
    ...Array.from({ length: 10 }, (_, index) => ({
      id: `export-${index}`,
      type: 'EXPORT',
      status: 'SUCCEEDED',
      rowCount: 1,
    })),
    { ...pending, id: 'older-import' },
  ],
  revalidation: [
    {
      ...pending,
      id: 'failed',
      status: 'FAILED',
      rowCount: 12500,
      finishedAt: '2026-11-10T01:05:00Z',
      resultAvailable: true,
      failureReason:
        '第 12500 行：客户来源「华东地区合作伙伴线下展会及渠道转介绍」已停用，请检查最新字典选项，修正文件后重新上传。',
    },
    { ...pending, id: 'no-reason', status: 'FAILED', rowCount: 0, resultAvailable: false },
  ],
}
const access = {
  permissions: ['order:read', 'order:import', 'order:export'],
  menus: [
    {
      code: 'demo',
      name: '示例应用',
      children: [
        {
          code: 'demo.overview',
          name: '订单',
          app: 'demo',
          path: 'overview',
          permission: 'order:read',
        },
      ],
    },
  ],
  subApps: [
    {
      code: 'demo',
      name: '示例',
      version: '1.0.0',
      remoteEntry: 'http://127.0.0.1:5185/remoteEntry.js',
      sharedRequirements: { vue: '3.5.0' },
    },
  ],
}
for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    for (const [state, tasks] of Object.entries(states)) {
      test(`导入确认-${state}`, async ({ page }) => {
        await page.clock.setFixedTime(new Date('2026-11-10T02:00:00Z'))
        await page.route(/:\d+\/api\/demo\//, (route) => {
          const path = new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/, '')
          if (path === '/demo/io/tasks') {
            if (state !== 'paging') return route.fulfill(onePage(tasks))
            const number = Number(new URL(route.request().url()).searchParams.get('page') ?? 1)
            return route.fulfill(
              ok(tasks.slice((number - 1) * 10, number * 10), {
                number,
                size: 10,
                totalElements: 11,
                totalPages: 2,
              }),
            )
          }
          if (path === '/demo/context')
            return route.fulfill(
              ok({ identityId: 'i', tenantId: 't', membershipId: 'm', version: 1 }),
            )
          return route.fulfill(onePage([]))
        })
        await openAsAdmin(page, '/apps/demo/overview', () => undefined, access)
        await page.getByRole('button', { name: '导入订单' }).click()
        const dialog = page.getByRole('dialog')
        await expect(dialog.getByRole('heading', { name: '最近任务' })).toBeVisible()
        if (state === 'paging') {
          await expect(dialog.getByText('暂无任务')).toBeVisible()
          await expect(page).toHaveScreenshot(`import-confirmation-paging-first-${label}.png`)
          await dialog.locator('.btn-next').click()
          await expect(dialog.getByRole('button', { name: '确认写入' })).toBeVisible()
        } else if (state === 'pending')
          await expect(dialog.getByRole('button', { name: '确认写入' })).toHaveCount(1)
        else if (state === 'expired') {
          await expect(dialog.getByText('确认已过期，请重新上传', { exact: false })).toHaveCount(2)
          await expect(dialog.getByRole('button', { name: '确认写入' })).toHaveCount(0)
        } else await expect(dialog.getByRole('button', { name: '下载错误报告' })).toBeVisible()
        await expect(page).toHaveScreenshot(`import-confirmation-${state}-${label}.png`)
      })
    }
  })
}
