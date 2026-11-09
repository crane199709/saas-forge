import { expect, test, type Page } from '@playwright/test'

// Browser plugin not available；HTTP mock 仅验证真实 UI 的错误文案传播，存储校验由后端集成测试覆盖。
const remoteEntry = process.env.FORGE_STORAGE_REMOTE_ENTRY ?? 'http://127.0.0.1:5175/remoteEntry.js'
const problems = new WeakMap<Page, string[]>()
const pagination = { number: 1, size: 20, totalElements: 1, totalPages: 1 }
const ok = (data: unknown, page?: unknown) => ({
  json: { code: 'ok', httpStatus: 200, data, meta: page ? { page } : undefined },
})
const rejection = (code: string, userTip: string) => ({
  status: 400,
  json: { code, httpStatus: 400, errorMessage: 'internal validation detail', userTip },
})

test.beforeEach(async ({ page }) => {
  const errors: string[] = []
  problems.set(page, errors)
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (
      ['error', 'warning'].includes(message.type()) &&
      !/Failed to load resource: the server responded with a status of 400/.test(message.text())
    )
      errors.push(message.text())
  })
  await page.addInitScript(() => {
    localStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({
        accessToken: 'access',
        refreshToken: 'refresh',
        membershipId: 'm',
        tenantId: 't',
        tenantName: '存储用途测试租户',
        identityName: '管理员',
      }),
    )
  })
  await page.route('**/iam/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/iam/auth/access')
      return route.fulfill(
        ok({
          permissions: ['order:read', 'order:update', 'order:import'],
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
                {
                  code: 'demo.attachments',
                  name: '附件',
                  app: 'demo',
                  path: 'attachments',
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
        }),
      )
    if (path === '/iam/auth/memberships')
      return route.fulfill(
        ok([{ membershipId: 'm', tenantId: 't', tenantName: '存储用途测试租户' }]),
      )
    if (path.endsWith('/unread-count')) return route.fulfill(ok({ count: 0 }))
    return route.fulfill(ok([]))
  })
  await page.route('**/demo/context', (route) =>
    route.fulfill(ok({ identityId: 'identity', tenantId: 't', membershipId: 'm', version: 1 })),
  )
  await page.route('**/demo/io/tasks?*', (route) =>
    route.fulfill(ok([], { ...pagination, totalElements: 0, totalPages: 0 })),
  )
  await page.route('**/demo/orders?*', (route) =>
    route.fulfill(
      ok([{ id: 'order', title: '测试订单', amount: 1, status: 'unpaid' }], pagination),
    ),
  )
})

test.afterEach(async ({ page }) => {
  await expect(page).toHaveTitle('SaaS Forge 租户端')
  await expect(page.getByTestId('remote-theme')).toBeVisible()
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  expect(problems.get(page)).toEqual([])
})

for (const [code, tip, filename] of [
  ['storage.extensionNotAllowed', '该用途不支持此文件扩展名', 'script.exe'],
  ['storage.fileTooLarge', '文件大小超过该用途的上限', 'large.pdf'],
] as const) {
  test(`附件上传被 ${code} 拒绝时显示 userTip 并停止直传和登记`, async ({ page }) => {
    let puts = 0
    let confirmations = 0
    await page.route('**/*', (route) => {
      if (route.request().method() === 'PUT') {
        puts++
        return route.fulfill({ status: 200 })
      }
      return route.fallback()
    })
    await page.route('**/demo/orders/order/attachments', (route) => {
      if (route.request().method() === 'POST') confirmations++
      return route.fulfill(ok([]))
    })
    await page.route('**/demo/orders/order/attachments/uploads', (route) =>
      route.fulfill(rejection(code!, tip!)),
    )
    await page.goto('/apps/demo/attachments')
    await expect(page).toHaveURL(/\/apps\/demo\/attachments$/)
    await expect(page.getByRole('heading', { name: '订单附件' })).toBeVisible()
    await page.getByTestId('attachment-file').setInputFiles({
      name: filename!,
      mimeType: 'application/octet-stream',
      buffer: Buffer.from('file'),
    })
    await page.getByTestId('attachment-upload').click()
    await expect(page.getByRole('alert')).toHaveText(tip!)
    await expect(page.getByRole('alert')).not.toContainText('internal validation')
    await expect(page.getByTestId('attachment-upload')).toBeEnabled()
    expect(puts).toBe(0)
    expect(confirmations).toBe(0)
    await page.screenshot({
      path:
        code === 'storage.extensionNotAllowed'
          ? '/private/tmp/storage-purpose-attachment-rejected.png'
          : '/private/tmp/storage-purpose-attachment-size-rejected.png',
      fullPage: false,
    })
  })
}

for (const [filename, tip, serverRejects] of [
  ['large.xlsx', '文件大小超过该用途的上限', true],
  ['source.csv', '仅支持 .xlsx 文件', false],
] as const) {
  test(`导入 ${filename} 被拒时保留提示且不继续直传和提交任务`, async ({ page }) => {
    let uploads = 0
    let puts = 0
    let submissions = 0
    await page.route('**/*', (route) => {
      if (route.request().method() === 'PUT') {
        puts++
        return route.fulfill({ status: 200 })
      }
      return route.fallback()
    })
    await page.route('**/demo/orders/import/upload', (route) => {
      uploads++
      return route.fulfill(rejection('storage.fileTooLarge', '文件大小超过该用途的上限'))
    })
    await page.route('**/demo/orders/import', (route) => {
      submissions++
      return route.fulfill(ok(null))
    })
    await page.goto('/apps/demo/overview')
    await expect(page).toHaveURL(/\/apps\/demo\/overview$/)
    await page.getByRole('button', { name: '导入订单' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.locator('input[type=file]').setInputFiles({
      name: filename,
      mimeType: 'application/octet-stream',
      buffer: Buffer.from('file'),
    })
    await page.getByTestId('import-submit').click()
    await expect(dialog.getByRole('alert')).toHaveText(tip)
    await expect(page.getByTestId('import-submit')).toBeEnabled()
    expect(uploads).toBe(serverRejects ? 1 : 0)
    expect(puts).toBe(0)
    expect(submissions).toBe(0)
    await page.screenshot({ path: `/tmp/storage-purpose-import-${filename}.png`, fullPage: false })
  })
}
