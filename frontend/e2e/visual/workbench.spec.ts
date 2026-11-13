import { mkdir } from 'node:fs/promises'
import { expect, test, type Page } from '@playwright/test'
import { ok, onePage, openAsAdmin, viewports } from './support'

// Browser plugin not available；真实登录和 Sub-App 跳转另由 main-flow 验证。
// 这里在公开 HTTP 接缝稳定提供四种页面状态，并检查首屏和响应式布局。
const states = ['populated', 'empty', 'error', 'hidden'] as const
const screens = [...viewports, ['narrow', 390, 844] as const]
const todos = [
  '华东大区第四季度办公设备集中采购订单待审核',
  '核对供应商发票与本月结算金额',
  '确认新客户资料与合同附件',
  '补充订单收货地址',
  '线下材料待归档',
  '第六条待办不应出现在工作台',
].map((title, index) => ({
  id: `todo-${index + 1}`,
  title,
  body: index === 4 ? '' : '请核对订单资料，完成后在业务页面提交处理结果。',
  occurredAt: '2026-11-13T01:30:00Z',
  link: index === 4 ? null : { appCode: 'demo', route: '/overview' },
}))
const notifications = [
  { id: 'read', title: '已经读过的通知', body: '不会出现在未读卡片。', read: true },
  ...[
    '采购订单已经通过财务审核',
    '供应商已上传最新合同附件，请及时查看',
    '本月结算单已生成',
    '客户资料已更新',
    '订单已成功归档',
    '第六条未读通知不应出现在工作台',
  ].map((title, index) => ({
    id: `notice-${index + 1}`,
    title,
    body: '处理进展已更新，可在通知中心查看完整内容。',
    occurredAt: '2026-11-13T01:20:00Z',
    read: false,
  })),
]
const announcements = [
  '关于年末业务结算与开票时间的安排',
  '本周六晚间系统维护通知',
  '新版采购制度已正式发布',
  '第四条公告不应出现在工作台',
].map((title, index) => ({
  id: `announcement-${index + 1}`,
  title,
  body: '请各部门按计划完成相关工作，详情见公告正文。',
  publishedAt: '2026-11-12T08:00:00Z',
}))

async function healthy(page: Page, problems: string[]) {
  await expect(page).toHaveURL('http://127.0.0.1:5184/')
  await expect(page).toHaveTitle('SaaS Forge 租户端')
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()
  await expect(page.locator('vite-error-overlay, webpack-dev-server-client-overlay')).toHaveCount(0)
  expect(problems).toEqual([])
}

for (const [label, width, height] of screens) {
  test.describe(`工作台 ${label}`, () => {
    test.use({ viewport: { width, height } })
    for (const state of states) {
      test(state, async ({ page }) => {
        const problems: string[] = []
        page.on('pageerror', (error) => problems.push(error.message))
        page.on('console', (message) => {
          if (!['error', 'warning'].includes(message.type())) return
          if (
            state === 'error' &&
            /Failed to load resource: the server responded with a status of 503/.test(
              message.text(),
            )
          )
            return
          problems.push(message.text())
        })
        let recovered = false
        const dataRequests: string[] = []
        await openAsAdmin(
          page,
          '/',
          (path, route) => {
            dataRequests.push(path)
            if (path === '/iam/todos') return route.fulfill(onePage(state === 'empty' ? [] : todos))
            if (path === '/iam/announcements/unread-count')
              return route.fulfill(ok({ count: state === 'empty' ? 0 : 3 }))
            if (path === '/iam/notifications/unread-count')
              return route.fulfill(ok({ count: state === 'empty' ? 0 : 6 }))
            if (path === '/iam/notifications') {
              if (state === 'error' && !recovered)
                return route.fulfill({
                  status: 503,
                  json: {
                    code: 'system.error',
                    httpStatus: 503,
                    userTip: '通知暂时无法加载，请稍后刷新',
                    traceId: 'workbench-503',
                  },
                })
              return route.fulfill(onePage(state === 'empty' ? [] : notifications))
            }
            if (path === '/iam/announcements')
              return route.fulfill(ok(state === 'empty' ? [] : announcements))
            return undefined
          },
          { readPermissions: state === 'hidden' ? [] : undefined },
        )
        await expect(page.getByTestId('workbench-greeting')).toHaveText(
          '测试成员 · 示例科技有限公司',
        )
        if (state === 'hidden') {
          await expect(page.locator('.workbench-card')).toHaveCount(0)
          await expect(page.getByTestId('workbench-refresh')).toHaveCount(0)
          await expect(page.getByTestId('notification-bell')).toHaveCount(0)
          expect(
            dataRequests.filter((path) => /^\/iam\/(todos|notifications|announcements)/.test(path)),
          ).toEqual([])
        } else {
          await expect(page.getByTestId('workbench-refresh')).toBeEnabled()
          await expect(page.locator('.workbench-card')).toHaveCount(3)
          if (state === 'empty') {
            await expect(page.getByTestId('workbench-todos')).toContainText('暂无待办')
            await expect(page.getByTestId('workbench-notifications')).toContainText('暂无未读通知')
            await expect(page.getByTestId('workbench-announcements')).toContainText('暂无公告')
          } else {
            await expect(page.getByTestId('workbench-todos').locator('li')).toHaveCount(5)
            await expect(page.getByTestId('workbench-announcements').locator('li')).toHaveCount(3)
            await expect(page.getByTestId('workbench-todos')).not.toContainText('第六条')
            await expect(page.getByTestId('workbench-announcements')).not.toContainText('第四条')
            if (state === 'error')
              await expect(
                page.getByTestId('workbench-notifications').getByRole('alert'),
              ).toContainText('通知暂时无法加载')
            else {
              await expect(page.getByTestId('workbench-notifications').locator('li')).toHaveCount(5)
              await expect(page.getByTestId('workbench-notifications')).not.toContainText(
                '已经读过',
              )
              await expect(page.getByTestId('workbench-notifications')).not.toContainText('第六条')
            }
          }
        }
        await healthy(page, problems)
        if (state !== 'hidden') {
          await expect(page.getByTestId('workbench-refresh')).toBeInViewport()
          if (width >= 1024) {
            for (const title of ['我的待办', '未读通知', '最新公告'])
              await expect(page.getByRole('heading', { name: title, exact: true })).toBeInViewport()
          }
        }
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
          true,
        )
        await expect(page).toHaveScreenshot(`workbench-${state}-${label}.png`)
        if (state === 'populated') {
          await mkdir('/tmp/workbench-qa', { recursive: true })
          await page.screenshot({ path: `/tmp/workbench-qa/${label}.png`, animations: 'disabled' })
        }
        if (state === 'error') {
          if (label === 'narrow')
            await expect(page.getByTestId('workbench-notifications')).toHaveScreenshot(
              'workbench-error-notifications-narrow.png',
            )
          recovered = true
          await page.getByTestId('workbench-refresh').click()
          await expect(page.getByTestId('workbench-notifications').getByRole('alert')).toHaveCount(
            0,
          )
          await expect(page.getByTestId('workbench-notifications').locator('li')).toHaveCount(5)
          await healthy(page, problems)
        }
      })
    }
  })
}
