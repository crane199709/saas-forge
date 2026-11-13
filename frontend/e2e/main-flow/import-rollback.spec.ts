import { expect, test, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const evidence = join(tmpdir(), 'forge-import-rollback-qa')
async function login(page: Page, account: string) {
  await page.goto('/')
  await page.getByLabel('账号或邮箱').fill(account)
  await page.getByLabel('密码', { exact: true }).fill('Demo-password-123!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByTestId('menu-demo.overview')).toBeVisible()
}
async function headers(page: Page) {
  return {
    Authorization: `Bearer ${await page.evaluate(() => JSON.parse(localStorage.getItem('forge.tenant.session')!).accessToken)}`,
  }
}

// 不注入假会话、不拦截 HTTP；有效 xlsx 经浏览器 PUT 到本轮隔离 S3。
test('覆盖导入后默认阻断冲突，明确跳过后回滚旧字段并保持幂等与独立权限', async ({
  page,
  browser,
}) => {
  await mkdir(evidence, { recursive: true })
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text())
  })
  await login(page, 'e2e-admin')
  const auth = await headers(page)
  const suffix = Date.now()
  const create = async (title: string, amount: number, customerSource: string) => {
    const response = await page.request.post('/api/demo/orders', {
      headers: auth,
      data: { title, amount, customerSource },
    })
    expect(response.status()).toBe(201)
    return (await response.json()).data
  }
  const a = await create(`回滚A-${suffix}`, 11.25, 'online')
  const b = await create(`回滚B-${suffix}`, 22.5, 'referral')
  const cTitle = `新增C-${suffix}`
  const fixture = `${evidence}/overwrite-${suffix}.xlsx`
  execFileSync(
    'pnpm',
    [
      '--filter',
      '@forge/tenant-app',
      'exec',
      'node',
      '--input-type=module',
      '-e',
      `
    import writeXlsxFile from 'write-excel-file/node';
    const [file,a,b,c]=process.argv.slice(1);
    await writeXlsxFile([
      ['标题','金额','客户来源','订单ID'].map(value=>({value})),
      [{value:'覆盖A'},{value:101.25},{value:'转介绍'},{value:a}],
      [{value:'覆盖B'},{value:202.50},{value:'线上'},{value:b}],
      [{value:c},{value:303},{value:'线上'},{value:''}],
    ]).toFile(file);
  `,
      fixture,
      a.id,
      b.id,
      cTitle,
    ],
    { stdio: 'pipe' },
  )
  await page.getByTestId('menu-demo.overview').click()
  await expect(page.getByTestId('remote-tenant')).toHaveText('当前租户：E2E 租户 A')
  await page.getByRole('button', { name: '覆盖导入', exact: true }).click()
  const importDialog = page.getByRole('dialog', { name: '导入', exact: true })
  await importDialog.getByLabel('选择 Excel 文件').setInputFiles(fixture)
  const submitted = page.waitForResponse(
    (r) => r.url().endsWith('/api/demo/orders/import/overwrite') && r.request().method() === 'POST',
  )
  await importDialog.getByTestId('import-submit').click()
  const submission = await submitted
  expect(submission.status()).toBe(201)
  const task = (await submission.json()).data
  await expect(importDialog.getByText('将写入 3 行', { exact: false })).toBeVisible()
  await importDialog.getByRole('button', { name: '确认写入', exact: true }).click()
  await expect(importDialog.getByText('导入 · 成功 · 3 行')).toBeVisible()
  const get = async (id: string) => {
    const response = await page.request.get(`/api/demo/orders/${id}`, { headers: auth })
    expect(response.status()).toBe(200)
    return (await response.json()).data
  }
  expect(await get(a.id)).toMatchObject({
    title: '覆盖A',
    amount: 101.25,
    customerSource: 'referral',
  })
  expect(await get(b.id)).toMatchObject({ title: '覆盖B', amount: 202.5, customerSource: 'online' })
  const list = await page.request.get('/api/demo/orders?size=100', { headers: auth })
  expect(list.status()).toBe(200)
  const c = (await list.json()).data.find((order: { title: string }) => order.title === cTitle)
  expect(c).toBeTruthy()
  const modified = await page.request.put(`/api/demo/orders/${a.id}`, {
    headers: auth,
    data: { title: '人工修改A', amount: 999.99, customerSource: 'online' },
  })
  expect(modified.status()).toBe(200)
  await importDialog.locator('.el-dialog__headerbtn').click()
  await page.getByRole('button', { name: '导入历史', exact: true }).click()
  const history = page.getByRole('dialog', { name: '导入历史', exact: true })
  await history.getByRole('button', { name: '回滚导入', exact: true }).click()
  const rollback = page.getByRole('dialog', { name: '回滚导入', exact: true })
  await expect(rollback.getByTestId('rollback-summary')).toHaveText(
    '覆盖 2 条，可恢复 1 条，冲突 1 条',
  )
  await expect(rollback.getByText('导入后已被修改', { exact: true })).toBeVisible()
  await expect(rollback.getByRole('button', { name: '确认回滚', exact: true })).toBeDisabled()
  await page.screenshot({ path: `${evidence}/real-conflicts-1440.png` })
  const blocked = await page.request.post(`/api/demo/orders/import/${task.id}/rollback`, {
    headers: auth,
    data: { skipConflicts: false },
  })
  expect(blocked.status()).toBe(409)
  expect((await blocked.json()).code).toBe('io.rollbackConflicts')
  expect(await get(b.id)).toMatchObject({ title: '覆盖B', amount: 202.5, customerSource: 'online' })
  expect(await get(a.id)).toMatchObject({
    title: '人工修改A',
    amount: 999.99,
    customerSource: 'online',
  })
  await rollback.getByText('我确认跳过冲突记录，仅恢复其余记录', { exact: true }).click()
  await expect(rollback.getByRole('checkbox')).toBeChecked()
  await page.screenshot({ path: `${evidence}/real-skip-confirm-1440.png` })
  await rollback.getByRole('button', { name: '确认回滚', exact: true }).click()
  await expect(rollback.getByTestId('rollback-result')).toContainText(
    '已恢复 1 条，跳过 1 条，失败 0 条',
  )
  expect(await get(a.id)).toMatchObject({
    title: '人工修改A',
    amount: 999.99,
    customerSource: 'online',
  })
  expect(await get(b.id)).toMatchObject({
    title: b.title,
    amount: 22.5,
    customerSource: 'referral',
    number: b.number,
    status: b.status,
    ownerId: b.ownerId,
  })
  expect(await get(c.id)).toMatchObject({ title: cTitle, amount: 303, customerSource: 'online' })
  const repeat = await page.request.post(`/api/demo/orders/import/${task.id}/rollback`, {
    headers: auth,
    data: { skipConflicts: true },
  })
  expect(repeat.status()).toBe(200)
  expect((await repeat.json()).data).toMatchObject({
    status: 'PARTIAL',
    restoredCount: 1,
    skippedCount: 1,
    failedCount: 0,
  })
  expect(await get(b.id)).toMatchObject({
    title: b.title,
    amount: 22.5,
    customerSource: 'referral',
  })
  const finalTask = await page.request.get(`/api/demo/io/tasks/${task.id}`, { headers: auth })
  expect((await finalTask.json()).data).toMatchObject({
    status: 'SUCCEEDED',
    rollbackStatus: 'PARTIAL',
    restoredCount: 1,
    skippedCount: 1,
    failedCount: 0,
  })
  await page.screenshot({ path: `${evidence}/real-result-1440.png` })
  await page.setViewportSize({ width: 1024, height: 768 })
  await page.screenshot({ path: `${evidence}/real-result-1024.png` })
  const importerContext = await browser.newContext({ baseURL: 'http://127.0.0.1:15174' })
  try {
    const importer = await importerContext.newPage()
    await login(importer, 'e2e-importer')
    const importerAuth = await headers(importer)
    const accessResponse = await importer.request.get('/api/iam/auth/access', {
      headers: importerAuth,
    })
    const permissions = (await accessResponse.json()).data.permissions
    expect(permissions).toContain('order:import')
    expect(permissions).not.toContain('order:importRollback')
    const template = await importer.request.get('/api/demo/orders/import/overwrite/template', {
      headers: importerAuth,
    })
    expect(template.status()).toBe(200)
    for (const action of ['rollback-preview', 'rollback']) {
      const response =
        action === 'rollback-preview'
          ? await importer.request.get(`/api/demo/orders/import/${task.id}/${action}`, {
              headers: importerAuth,
            })
          : await importer.request.post(`/api/demo/orders/import/${task.id}/${action}`, {
              headers: importerAuth,
              data: { skipConflicts: true },
            })
      expect(response.status()).toBe(403)
    }
    await importer.getByTestId('menu-demo.overview').click()
    await expect(importer.getByRole('button', { name: '覆盖导入', exact: true })).toBeVisible()
    await importer.getByRole('button', { name: '导入历史', exact: true }).click()
    await expect(importer.getByRole('button', { name: '回滚导入', exact: true })).toHaveCount(0)
  } finally {
    await importerContext.close()
  }
  await expect(page).toHaveURL(/\/apps\/demo\/overview$/)
  await expect(page).toHaveTitle('SaaS Forge 租户端')
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  expect(errors).toEqual([])
})
