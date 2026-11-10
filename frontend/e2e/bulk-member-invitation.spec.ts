import { createRequire } from 'node:module'
import { readFile } from 'node:fs/promises'
import { expect, test, type Page } from '@playwright/test'
import { ok, onePage, openAsAdmin } from './visual/support'

// Browser plugin not available：按项目 Playwright 工作流，以公开 HTTP 边界模拟后端；
// 邀请接口的真实行为由 forge-iam 的 MemberManagementTest 覆盖。
// 用 tenant-app 依赖的同一个 Excel 库生成上传文件、读取下载的模板。
const tenantApp = createRequire(new URL('../apps/tenant-app/package.json', import.meta.url))
const { default: writeXlsxFile } = tenantApp('write-excel-file/universal') as {
  default: (data: unknown[][]) => { toBlob(): Promise<Blob> }
}
const { readSheet } = tenantApp('read-excel-file/universal') as {
  readSheet(input: Blob | ArrayBuffer, sheet?: number): Promise<unknown[][]>
}

const problems = new WeakMap<Page, string[]>()
test.beforeEach(({ page }) => {
  const errors: string[] = []
  problems.set(page, errors)
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (!['error', 'warning'].includes(message.type())) return
    if (
      /Failed to load resource: the server responded with a status of (409|503)/.test(
        message.text(),
      )
    )
      return
    errors.push(message.text())
  })
})
test.afterEach(async ({ page }) => {
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  expect(problems.get(page)).toEqual([])
})

const roles = [
  { id: 'r-owner', code: 'owner', name: '所有者', owner: true },
  { id: 'r-sales', code: 'sales', name: '销售', owner: false },
]
const departments = [
  { id: 'hq', parentId: null, name: '总部', path: 'hq' },
  { id: 'rd', parentId: 'hq', name: '研发部', path: 'hq.rd' },
]

async function xlsx(rows: unknown[][]) {
  return Buffer.from(await (await writeXlsxFile(rows).toBlob()).arrayBuffer())
}

test('下载模板、上传预览、确认发送、显示结果并下载失败清单', async ({ page }) => {
  const invited: unknown[] = []
  let listed = 0
  await openAsAdmin(page, '/members', (path, route) => {
    if (path === '/iam/members') {
      listed++
      return route.fulfill(onePage([]))
    }
    if (path === '/iam/roles') return route.fulfill(ok(roles))
    if (path === '/iam/org/departments') return route.fulfill(ok(departments))
    if (path === '/iam/members/invitations') {
      const body = route.request().postDataJSON() as { contact: string }
      invited.push(body)
      if (body.contact === 'member@example.com')
        return route.fulfill({
          status: 409,
          json: { code: 'member.alreadyMember', httpStatus: 409, userTip: '该用户已是成员' },
        })
      if (body.contact === 'down@example.com')
        return route.fulfill({
          status: 503,
          json: { code: 'service.unavailable', httpStatus: 503, userTip: '服务暂不可用' },
        })
      return route.fulfill({
        status: 201,
        json: {
          code: 'ok',
          httpStatus: 201,
          data: {
            membershipId: body.contact,
            status: 'invited',
            resent: body.contact === 'again@example.com',
          },
        },
      })
    }
    return undefined
  })
  await expect(page.getByTestId('member-bulk-invite')).toBeVisible()
  const listedBefore = listed

  await page.getByTestId('member-bulk-invite').click()
  const dialog = page.getByRole('dialog', { name: '批量邀请成员' })

  const download = page.waitForEvent('download')
  await dialog.getByTestId('member-bulk-template').click()
  const template = await download
  expect(template.suggestedFilename()).toBe('成员批量邀请模板.xlsx')
  const content = await readFile(await template.path())
  const bytes = content.buffer.slice(content.byteOffset, content.byteOffset + content.byteLength)
  expect(await readSheet(bytes as ArrayBuffer)).toEqual([['联系方式', '角色', '主部门']])

  await dialog.getByTestId('member-bulk-file').setInputFiles({
    name: 'members.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: await xlsx([
      ['联系方式', '角色', '主部门'],
      ['new@example.com', '销售', '总部/研发部'],
      ['again@example.com', null, null],
      ['member@example.com', null, null],
      ['down@example.com', null, null],
      ['new@example.com', null, null],
    ]),
  })
  const preview = dialog.getByTestId('member-bulk-preview')
  await expect(preview).toContainText('将邀请 4 人')
  await expect(preview).toContainText('1 行有错误')
  await expect(preview).toContainText('与第 2 行重复')

  await dialog.getByTestId('member-bulk-send').click()
  const result = dialog.getByTestId('member-bulk-result')
  await expect(result).toBeVisible()
  await expect(dialog.getByTestId('member-bulk-invited')).toContainText('1')
  await expect(dialog.getByTestId('member-bulk-resent')).toContainText('1')
  await expect(dialog.getByTestId('member-bulk-alreadyMember')).toContainText('1')
  await expect(dialog.getByTestId('member-bulk-failed')).toContainText('2')
  await expect(result).toContainText('服务暂不可用')
  expect(invited).toHaveLength(4)
  expect(invited[0]).toEqual({
    contact: 'new@example.com',
    roleIds: ['r-sales'],
    primaryDepartmentId: 'rd',
  })
  expect(listed).toBe(listedBefore + 1)

  const failures = page.waitForEvent('download')
  await dialog.getByTestId('member-bulk-download').click()
  const failureList = await failures
  expect(failureList.suggestedFilename()).toBe('成员批量邀请失败清单.xlsx')
  const failureContent = await readFile(await failureList.path())
  expect(
    await readSheet(
      failureContent.buffer.slice(
        failureContent.byteOffset,
        failureContent.byteOffset + failureContent.byteLength,
      ) as ArrayBuffer,
    ),
  ).toEqual([
    ['联系方式', '角色', '主部门', '失败原因'],
    ['down@example.com', null, null, '服务暂不可用'],
    ['new@example.com', null, null, '与第 2 行重复'],
  ])

  await dialog.getByTestId('member-bulk-done').click()
  await expect(dialog).toBeHidden()
})

test('非 .xlsx 文件整个拒收', async ({ page }) => {
  await openAsAdmin(page, '/members', (path, route) =>
    path === '/iam/members' ? route.fulfill(onePage([])) : undefined,
  )
  await page.getByTestId('member-bulk-invite').click()
  await page.getByTestId('member-bulk-file').setInputFiles({
    name: 'members.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from('联系方式,角色,主部门\n'),
  })
  await expect(page.getByTestId('member-bulk-rejected')).toContainText('只支持 .xlsx 文件')
  await expect(page.getByTestId('member-bulk-send')).toBeDisabled()
})
