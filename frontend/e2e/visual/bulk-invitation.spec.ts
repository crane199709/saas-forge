import { createRequire } from 'node:module'
import { expect, test, type Page } from '@playwright/test'
import { ok, onePage, openAsAdmin, viewports } from './support'

const tenantApp = createRequire(new URL('../../apps/tenant-app/package.json', import.meta.url))
const { default: writeXlsxFile } = tenantApp('write-excel-file/universal') as {
  default: (data: unknown[][]) => { toBlob(): Promise<Blob> }
}

const roles = [
  { id: 'r-owner', code: 'owner', name: '所有者', owner: true },
  { id: 'r-sales', code: 'sales', name: '华东大区销售经理', owner: false },
]
const departments = [
  { id: 'hq', parentId: null, name: '总部', path: 'hq' },
  { id: 'rd', parentId: 'hq', name: '研发中心', path: 'hq.rd' },
  { id: 'fe', parentId: 'rd', name: '前端平台组', path: 'hq.rd.fe' },
]
// 联系方式和路径取接近真实的最长值，覆盖空值与各类错误。
const rows = [
  ['联系方式', '角色', '主部门'],
  [
    'lina.operations.manager@example-enterprise.com',
    '华东大区销售经理',
    '总部/研发中心/前端平台组',
  ],
  ['zhangwei@example.com', null, null],
  ['13800000000', null, '总部/研发中心'],
  ['member@example.com', null, null],
  ['quota@example.com', null, null],
  [null, '华东大区销售经理', null],
  ['not-a-contact', null, null],
  ['chenxiaohua@example.com', '所有者', null],
  ['wangqiang@example.com', null, '总部/研发中心/不存在的后端平台基础架构组'],
  ['ZhangWei@example.com', null, null],
]

async function open(page: Page, hold = false) {
  await openAsAdmin(page, '/members', (path, route) => {
    if (path === '/iam/members') return route.fulfill(onePage([]))
    if (path === '/iam/roles') return route.fulfill(ok(roles))
    if (path === '/iam/org/departments') return route.fulfill(ok(departments))
    if (path === '/iam/members/invitations') {
      const { contact } = route.request().postDataJSON() as { contact: string }
      if (contact === 'member@example.com')
        return route.fulfill({
          status: 409,
          json: { code: 'member.alreadyMember', httpStatus: 409, userTip: '该用户已是成员' },
        })
      // 发送中的截图让这一行一直等待，停在发送中。
      if (contact === 'quota@example.com' && hold) return new Promise<void>(() => {})
      if (contact === 'quota@example.com')
        return route.fulfill({
          status: 409,
          json: {
            code: 'member.quotaExceeded',
            httpStatus: 409,
            userTip: '成员数已达套餐上限 50 人，请升级套餐后再邀请',
          },
        })
      return route.fulfill({
        status: 201,
        json: {
          code: 'ok',
          httpStatus: 201,
          data: { membershipId: contact, status: 'invited', resent: contact === '13800000000' },
        },
      })
    }
    return undefined
  })
  await page.getByTestId('member-bulk-invite').click()
  return page.getByRole('dialog', { name: '批量邀请成员' })
}

async function upload(page: Page) {
  await page.getByTestId('member-bulk-file').setInputFiles({
    name: 'members.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: Buffer.from(await (await writeXlsxFile(rows).toBlob()).arrayBuffer()),
  })
  await expect(page.getByTestId('member-bulk-preview')).toBeVisible()
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    test('批量邀请-选择文件', async ({ page }) => {
      const dialog = await open(page)
      await expect(dialog.getByTestId('member-bulk-choose')).toBeEnabled()
      await expect(page).toHaveScreenshot(`bulk-invitation-select-${label}.png`)
    })

    test('批量邀请-预览', async ({ page }) => {
      await open(page)
      await upload(page)
      await expect(page).toHaveScreenshot(`bulk-invitation-preview-${label}.png`)
    })

    test('批量邀请-发送中', async ({ page }) => {
      const dialog = await open(page, true)
      await upload(page)
      await dialog.getByTestId('member-bulk-send').click()
      await expect(dialog.getByTestId('member-bulk-progress')).toContainText('已处理 4 / 5')
      await page.mouse.move(0, 0)
      await expect(page).toHaveScreenshot(`bulk-invitation-sending-${label}.png`)
    })

    test('批量邀请-结果', async ({ page }) => {
      const dialog = await open(page)
      await upload(page)
      await dialog.getByTestId('member-bulk-send').click()
      await expect(dialog.getByTestId('member-bulk-result')).toBeVisible()
      // 「完成」按钮出现在刚点击的位置，移开鼠标并等悬停色过渡结束再截图。
      await page.mouse.move(0, 0)
      await expect(dialog.getByTestId('member-bulk-done')).toHaveCSS(
        'background-color',
        'rgb(64, 158, 255)',
      )
      await expect(page).toHaveScreenshot(`bulk-invitation-result-${label}.png`)
    })
  })
}
