import { expect, test } from '@playwright/test'
import { ok, onePage, openAsAdmin, viewports } from './support'

const model = 'io.saasforge.demo.order.model.OrderOverwriteRow'
const task = {
  id: '019a2525-0000-7000-8000-000000000091',
  type: 'IMPORT',
  importModel: model,
  status: 'SUCCEEDED',
  membershipId: 'm',
  rowCount: 12500,
  snapshotCount: 12000,
  snapshotAvailable: true,
  snapshotExpiresAt: '2027-02-11T10:30:00Z',
  rollbackStatus: 'NONE',
}
const report = {
  taskId: task.id,
  available: true,
  blocked: true,
  totalCount: 12000,
  rollbackableCount: 11997,
  conflictCount: 3,
  snapshotExpiresAt: task.snapshotExpiresAt,
  conflicts: [
    { recordId: '019a2525-0000-7000-8000-000000000101', reason: 'RECORD_MODIFIED' },
    { recordId: '019a2525-0000-7000-8000-000000000102', reason: 'LATER_IMPORT' },
    { recordId: '019a2525-0000-7000-8000-000000000103', reason: 'RECORD_UNAVAILABLE' },
  ],
}
const access = {
  permissions: ['order:read', 'order:import', 'order:importRollback', 'order:export'],
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
    for (const state of ['history', 'conflict', 'skip', 'result'] as const) {
      test(`导入回滚-${state}`, async ({ page }) => {
        await page.clock.setFixedTime(new Date('2026-11-13T12:00:00Z'))
        let done = false
        await page.route(/:\d+\/api\/demo\//, (route) => {
          const path = new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/, '')
          if (path === '/demo/context')
            return route.fulfill(
              ok({ identityId: 'i', tenantId: 't', membershipId: 'm', version: 1 }),
            )
          if (path === '/demo/io/tasks')
            return route.fulfill(
              onePage(
                state === 'history'
                  ? [
                      task,
                      {
                        ...task,
                        id: '019a2525-0000-7000-8000-000000000092',
                        importModel: 'io.saasforge.demo.order.model.OrderImportRow',
                        snapshotCount: 0,
                        snapshotAvailable: false,
                        snapshotExpiresAt: null,
                      },
                      {
                        ...task,
                        id: '019a2525-0000-7000-8000-000000000093',
                        snapshotAvailable: false,
                        snapshotExpiresAt: '2026-11-12T08:15:00Z',
                        rollbackStatus: 'PARTIAL',
                        rollbackAttempts: 2,
                        restoredCount: 11997,
                        skippedCount: 3,
                        failedCount: 0,
                      },
                      {
                        ...task,
                        id: '019a2525-0000-7000-8000-000000000094',
                        snapshotAvailable: false,
                        rollbackStatus: 'SUCCEEDED',
                        rollbackAttempts: 1,
                        restoredCount: 12000,
                        skippedCount: 0,
                        failedCount: 0,
                      },
                    ]
                  : [
                      {
                        ...task,
                        rollbackStatus: done ? 'PARTIAL' : 'NONE',
                        rollbackAttempts: done ? 1 : 0,
                        restoredCount: done ? 11997 : 0,
                        skippedCount: done ? 3 : 0,
                        failedCount: 0,
                      },
                    ],
              ),
            )
          if (path.endsWith('/rollback-preview'))
            return route.fulfill(
              ok(done ? { ...report, totalCount: 12000, rollbackableCount: 0 } : report),
            )
          if (path.endsWith('/rollback')) {
            expect(route.request().postDataJSON()).toEqual({ skipConflicts: true })
            done = true
            return route.fulfill(
              ok({
                taskId: task.id,
                status: 'PARTIAL',
                restoredCount: 11997,
                skippedCount: 3,
                failedCount: 0,
                conflicts: report.conflicts,
              }),
            )
          }
          return route.fulfill(onePage([]))
        })
        await openAsAdmin(page, '/apps/demo/overview', () => undefined, access)
        await page.getByRole('button', { name: '导入历史', exact: true }).click()
        const history = page.getByRole('dialog', { name: '导入历史', exact: true })
        if (state === 'history') {
          await expect(history.getByText('不可回滚：快照已过期', { exact: true })).toBeVisible()
          await expect(
            history.getByRole('cell', {
              name: '已回滚 已恢复 12000 条，跳过 0 条，失败 0 条',
              exact: true,
            }),
          ).toBeVisible()
        } else {
          await history.getByRole('button', { name: '回滚导入', exact: true }).click()
          const rollback = page.getByRole('dialog', { name: '回滚导入', exact: true })
          await expect(
            rollback.getByText('仅恢复本次导入覆盖的字段；新增记录和导入后的修改会保留。', {
              exact: true,
            }),
          ).toBeVisible()
          await expect(rollback.getByText('导入后已被修改', { exact: true })).toBeVisible()
          await expect(
            rollback.getByRole('button', { name: '确认回滚', exact: true }),
          ).toBeDisabled()
          if (state === 'skip' || state === 'result') {
            await rollback.getByText('我确认跳过冲突记录，仅恢复其余记录', { exact: true }).click()
            await expect(rollback.getByRole('checkbox')).toBeChecked()
          }
          if (state === 'result') {
            await rollback.getByRole('button', { name: '确认回滚', exact: true }).click()
            await expect(rollback.getByTestId('rollback-result')).toContainText(
              '已恢复 11997 条，跳过 3 条，失败 0 条',
            )
            await expect(rollback.getByTestId('rollback-summary')).toHaveText(
              '覆盖 12000 条，可恢复 0 条，冲突 3 条',
            )
          }
        }
        await expect(page.locator('vite-error-overlay')).toHaveCount(0)
        await expect(page).toHaveScreenshot(`import-rollback-${state}-${label}.png`)
      })
    }
  })
}
