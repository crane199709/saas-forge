import { expect, test, type Page } from '@playwright/test'
import { ok, onePage, openAsAdmin } from './visual/support'

// Browser plugin not available；HTTP mock 验证真实 UI，实际写入由 ObjectStorageTest 覆盖。
const remoteEntry =
  process.env.FORGE_IMPORT_CONFIRMATION_REMOTE_ENTRY ?? 'http://127.0.0.1:5175/remoteEntry.js'
const problems = new WeakMap<Page, string[]>()
const pending = () => ({
  id: 'import-1',
  type: 'IMPORT',
  status: 'AWAITING_CONFIRM',
  rowCount: 2,
  membershipId: 'm',
  heartbeatAt: new Date().toISOString(),
})
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
      remoteEntry,
      sharedRequirements: { vue: '3.5.0' },
    },
  ],
}

test.beforeEach(async ({ page }) => {
  const errors: string[] = []
  problems.set(page, errors)
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (
      ['error', 'warning'].includes(message.type()) &&
      !/Failed to load resource: the server responded with a status of (400|429)/.test(
        message.text(),
      )
    )
      errors.push(message.text())
  })
})
test.afterEach(async ({ page }) => {
  await expect(page).toHaveURL(/\/apps\/demo\/overview$/)
  await expect(page).toHaveTitle('SaaS Forge 租户端')
  await expect(page.getByTestId('remote-tenant')).toBeVisible()
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  expect(problems.get(page)).toEqual([])
})

async function open(
  page: Page,
  outcome: 'success' | 'failed' | 'retry' = 'success',
  recent?: Record<string, unknown>[],
) {
  let task: Record<string, unknown> | undefined
  let confirmed = false
  const confirmations: string[] = []
  let downloads = 0
  let written = false
  await page.route(/:\d+\/demo\//, (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/demo/context')
      return route.fulfill(ok({ identityId: 'i', tenantId: 't', membershipId: 'm', version: 1 }))
    if (path === '/demo/orders')
      return route.fulfill(
        onePage(
          written
            ? [{ id: 'written', title: '确认后写入的订单', amount: 12, status: 'unpaid' }]
            : [],
        ),
      )
    if (path === '/demo/io/tasks') {
      const number = Number(new URL(route.request().url()).searchParams.get('page') ?? 1)
      const tasks = recent ?? (task ? [task] : [])
      return route.fulfill(
        ok(tasks.slice((number - 1) * 10, number * 10), {
          number,
          size: 10,
          totalElements: tasks.length,
          totalPages: Math.ceil(tasks.length / 10),
        }),
      )
    }
    if (path === '/demo/orders/import/upload')
      return route.fulfill(
        ok({
          objectId: 'source',
          url: new URL('/mock-source', route.request().url()).href,
          headers: { 'Content-Type': 'application/octet-stream' },
        }),
      )
    if (path === '/demo/orders/import') {
      task = { ...pending(), status: 'WAITING', rowCount: 0 }
      return route.fulfill(ok(task))
    }
    if (path === '/demo/orders/import/import-1/confirm') {
      confirmations.push(path)
      if (outcome === 'retry' && confirmations.length === 1)
        return route.fulfill({
          status: 429,
          json: { code: 'io.busy', httpStatus: 429, userTip: '任务较多，请稍后再试' },
        })
      confirmed = true
      task = { ...pending(), status: 'WAITING' }
      return route.fulfill(ok(task))
    }
    if (path === '/demo/io/tasks/import-1') {
      task = !confirmed
        ? pending()
        : {
            ...pending(),
            status: outcome === 'failed' ? 'FAILED' : 'SUCCEEDED',
            finishedAt: new Date().toISOString(),
            resultAvailable: outcome === 'failed',
            failureReason: outcome === 'failed' ? '客户来源已停用，请修正后重新上传' : undefined,
          }
      written = confirmed && outcome !== 'failed'
      return route.fulfill(ok(task))
    }
    if (path === '/demo/io/tasks/import-1/download') {
      downloads++
      return route.fulfill(
        ok({ url: new URL('/new-confirmation-report.xlsx', route.request().url()).href }),
      )
    }
    return route.fulfill(onePage([]))
  })
  await page.route('**/mock-source', (route) => {
    expect(route.request().headers().authorization).toBeUndefined()
    return route.fulfill({ status: 200 })
  })
  await openAsAdmin(page, '/apps/demo/overview', () => undefined, access)
  await page.getByRole('button', { name: '导入订单' }).click()
  return { confirmations, downloads: () => downloads }
}
async function upload(page: Page) {
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('选择 Excel 文件').setInputFiles({
    name: 'orders.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: Buffer.from('workbook'),
  })
  await dialog.getByTestId('import-submit').click()
  await expect(dialog.getByText('将写入 2 行', { exact: false })).toBeVisible()
  await expect(dialog.getByRole('button', { name: '确认写入' })).toBeEnabled()
  return dialog
}

test('提交后展示将写入行数，提交人确认后才刷新出写入订单', async ({ page }) => {
  const state = await open(page)
  const dialog = await upload(page)
  expect(state.confirmations).toEqual([])
  await expect(page.getByTestId('order-title')).toHaveCount(0)
  await dialog.getByRole('button', { name: '确认写入' }).click()
  await expect(dialog.getByText('导入 · 成功 · 2 行')).toBeVisible()
  expect(state.confirmations).toEqual(['/demo/orders/import/import-1/confirm'])
  await expect(page.getByTestId('order-title')).toHaveText('确认后写入的订单')
  await page.screenshot({ path: test.info().outputPath('import-confirmation-success.png') })
})

test('确认时重新校验失败，显示新的错误原因并现取错误报告', async ({ page, context }) => {
  await context.route('**/new-confirmation-report.xlsx', (route) =>
    route.fulfill({ contentType: 'text/plain', body: 'new error report' }),
  )
  const state = await open(page, 'failed')
  const dialog = await upload(page)
  await dialog.getByRole('button', { name: '确认写入' }).click()
  await expect(dialog.getByText('客户来源已停用，请修正后重新上传', { exact: false })).toBeVisible()
  await expect(page.getByTestId('order-title')).toHaveCount(0)
  const popup = page.waitForEvent('popup')
  await dialog.getByRole('button', { name: '下载错误报告' }).click()
  const report = await popup
  await expect(report).toHaveURL(/new-confirmation-report\.xlsx$/)
  expect(state.downloads()).toBe(1)
  await page.screenshot({ path: test.info().outputPath('import-confirmation-revalidation.png') })
})

test('非提交人无确认入口，超时和已过期任务明确提示', async ({ page }) => {
  await open(page, 'success', [
    { ...pending(), id: 'other', membershipId: 'other' },
    {
      ...pending(),
      id: 'late',
      heartbeatAt: new Date(Date.now() - 25 * 60 * 60 * 1000).toISOString(),
    },
    {
      ...pending(),
      id: 'expired',
      status: 'FAILED',
      errorCode: 'io.confirmationExpired',
      finishedAt: new Date().toISOString(),
      resultAvailable: false,
    },
  ])
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('button', { name: '确认写入' })).toHaveCount(0)
  await expect(dialog.getByText('确认已过期，请重新上传', { exact: false })).toHaveCount(2)
  await page.screenshot({ path: test.info().outputPath('import-confirmation-expired.png') })
})

test('确认队列繁忙后保留待确认任务并可重试', async ({ page }) => {
  const state = await open(page, 'retry')
  const dialog = await upload(page)
  await dialog.getByRole('button', { name: '确认写入' }).click()
  await expect(dialog.getByRole('alert')).toHaveText('任务较多，请稍后再试')
  await expect(dialog.getByRole('button', { name: '确认写入' })).toBeEnabled()
  await dialog.getByRole('button', { name: '确认写入' }).click()
  await expect(page.getByTestId('order-title')).toHaveText('确认后写入的订单')
  expect(state.confirmations).toHaveLength(2)
})

test('较早待确认任务在十个更新导出之后，重开与整页刷新仍可翻页确认', async ({ page }) => {
  const state = await open(page, 'success', [
    ...Array.from({ length: 10 }, (_, index) => ({
      id: `export-${index}`,
      type: 'EXPORT',
      status: 'SUCCEEDED',
      rowCount: 1,
    })),
    pending(),
  ])
  let dialog = page.getByRole('dialog')
  await expect(dialog.getByText('暂无任务')).toBeVisible()
  await dialog.locator('.btn-next').click()
  await expect(dialog.getByText('将写入 2 行', { exact: false })).toBeVisible()
  await dialog.locator('.el-dialog__headerbtn').click()
  await page.getByRole('button', { name: '导入订单' }).click()
  await expect(dialog.getByRole('button', { name: '确认写入' })).toBeEnabled()
  await page.reload()
  await page.getByRole('button', { name: '导入订单' }).click()
  dialog = page.getByRole('dialog')
  await expect(dialog.getByText('暂无任务')).toBeVisible()
  await dialog.locator('.btn-next').click()
  await expect(dialog.getByRole('button', { name: '确认写入' })).toBeEnabled()
  await page.screenshot({ path: test.info().outputPath('import-confirmation-review-paging.png') })
  await dialog.getByRole('button', { name: '确认写入' }).click()
  await expect(page.getByTestId('order-title')).toHaveText('确认后写入的订单')
  expect(state.confirmations).toEqual(['/demo/orders/import/import-1/confirm'])
})
