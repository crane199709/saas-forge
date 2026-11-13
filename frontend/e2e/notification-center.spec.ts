import { expect, test, type Page, type Route } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const evidence = join(tmpdir(), 'notification-center-qa')
function ok(data: unknown, page?: unknown) {
  return {
    json: {
      code: 'ok',
      httpStatus: 200,
      data,
      meta: page ? { page } : undefined,
      traceId: 'inbox-ok',
    },
  }
}
function paged(items: unknown[]) {
  return ok(items, { number: 1, size: 20, totalElements: items.length, totalPages: 1 })
}
function fail(route: Route) {
  return route.fulfill({
    status: 503,
    json: {
      code: 'system.error',
      httpStatus: 503,
      userTip: '暂时无法保存，请重试',
      traceId: 'inbox-503',
    },
  })
}
const problems = new WeakMap<Page, string[]>()
test.beforeEach(async ({ page }) => {
  await mkdir(evidence, { recursive: true })
  const errors: string[] = []
  problems.set(page, errors)
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (
      ['error', 'warning'].includes(message.type()) &&
      !/Failed to load resource: the server responded with a status of 503/.test(message.text())
    )
      errors.push(message.text())
  })
})
test.afterEach(async ({ page }) => {
  await expect(page.locator('vite-error-overlay, #webpack-dev-server-client-overlay')).toHaveCount(
    0,
  )
  expect(problems.get(page)).toEqual([])
})
async function tenant(page: Page, permissions: string[] = []) {
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({
        accessToken: 'tenant-access',
        refreshToken: 'tenant-refresh',
        membershipId: 'm',
        tenantId: 't',
        tenantName: '测试租户',
        identityName: '测试成员',
      }),
    ),
  )
  await page.route('**/iam/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/iam/auth/access')
      return route.fulfill(
        ok({
          permissions: [...permissions, 'todo:read', 'notification:read', 'announcement:read'],
          menus: [{ code: 'demo.overview', name: '订单概览', app: 'demo', path: 'overview' }],
          subApps: [
            {
              code: 'demo',
              name: '示例',
              version: '1',
              remoteEntry: 'http://127.0.0.1:5175/remoteEntry.js',
              sharedRequirements: {},
            },
          ],
        }),
      )
    if (path === '/iam/auth/memberships')
      return route.fulfill(ok([{ membershipId: 'm', tenantId: 't', tenantName: '测试租户' }]))
    if (path.endsWith('/unread-count')) return route.fulfill(ok({ count: 0 }))
    return route.fulfill(ok([]))
  })
}
const announcementRow = (page: Page, id: string) =>
  page.getByRole('row').filter({ has: page.getByTestId(`announcement-${id}`) })

test('通知、公告已读、错误重试与待办进入业务后关单', async ({ page }) => {
  await tenant(page, ['order:read', 'order:update'])
  let read1 = false,
    read2 = false,
    announcementRead = false,
    retry = true,
    closed = false
  const notification = (id: string, read: boolean) => ({
    id,
    title: `订单通知 ${id}`,
    body: '<b>纯文本通知</b>',
    read,
    occurredAt: '2026-11-07T01:00:00Z',
    link: { appCode: 'demo', route: '/overview' },
  })
  await page.route('**/iam/notifications**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/, '')
    if (path.endsWith('/unread-count'))
      return route.fulfill(ok({ count: Number(!read1) + Number(!read2) }))
    if (path.endsWith('/n1/read')) {
      if (retry) {
        retry = false
        return fail(route)
      }
      read1 = true
      return route.fulfill(ok(null))
    }
    if (path.endsWith('/read-all')) {
      read1 = read2 = true
      return route.fulfill(ok(null))
    }
    return route.fulfill(paged([notification('n1', read1), notification('n2', read2)]))
  })
  await page.route('**/iam/announcements**', (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/, '')
    if (path.endsWith('/unread-count'))
      return route.fulfill(ok({ count: Number(!announcementRead) }))
    if (path.endsWith('/read')) {
      announcementRead = true
      return route.fulfill(ok(null))
    }
    return route.fulfill(
      ok([
        {
          id: 'a1',
          title: '服务公告',
          body: '<em>纯文本公告</em>',
          read: announcementRead,
          subApp: 'demo',
          route: '/overview',
        },
      ]),
    )
  })
  await page.route('**/iam/todos?*', (route) =>
    route.fulfill(
      paged(
        closed
          ? []
          : [
              {
                id: 't1',
                title: '处理订单',
                body: '待处理订单',
                link: { appCode: 'demo', route: '/overview' },
              },
            ],
      ),
    ),
  )
  await page.route('**/demo/orders**', (route) => {
    if (route.request().method() === 'POST') {
      expect(new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/, '')).toBe(
        '/demo/orders/o1/close',
      )
      closed = true
      return route.fulfill(ok({ id: 'o1', status: 'closed' }))
    }
    return route.fulfill(
      paged([{ id: 'o1', title: '待处理订单', amount: 10, status: closed ? 'closed' : 'unpaid' }]),
    )
  })
  await page.goto('/notifications')
  await expect(page).toHaveTitle('SaaS Forge 租户端')
  await expect(page.getByRole('heading', { name: '通知中心', exact: true })).toBeVisible()
  await expect(page.getByTestId('notification-bell')).toHaveAccessibleName('通知中心，3 条未读')
  await expect(page.getByText('<b>纯文本通知</b>').first()).toBeVisible()
  await expect(page.locator('.inbox-item b')).toHaveCount(0)
  await page.getByTestId('mark-read-n1').click()
  await expect(page.getByRole('alert')).toContainText('暂时无法保存，请重试')
  await expect(page.getByRole('alert')).toContainText('inbox-503')
  await page.screenshot({ path: `${evidence}/notification-error.png` })
  await page.getByTestId('mark-read-n1').click()
  await expect(page.getByTestId('inbox-n1')).toContainText('已读')
  await expect(page.getByTestId('notification-bell')).toHaveAccessibleName('通知中心，2 条未读')
  await page.getByRole('button', { name: '全部通知标为已读' }).click()
  await expect(page.getByTestId('inbox-n2')).toContainText('已读')
  await page.getByTestId('open-n1').click()
  await expect(page).toHaveURL('http://127.0.0.1:5174/apps/demo/overview')
  await page.getByTestId('notification-bell').click()
  await page.getByTestId('tab-announcements').click()
  await expect(page.getByText('<em>纯文本公告</em>')).toBeVisible()
  await page.getByTestId('mark-read-a1').click()
  await expect(page.getByTestId('notification-bell')).toHaveAccessibleName('通知中心，0 条未读')
  await page.getByTestId('open-a1').click()
  await expect(page).toHaveURL('http://127.0.0.1:5174/apps/demo/overview')
  await page.getByTestId('notification-bell').click()
  await page.getByTestId('tab-todos').click()
  await expect(page.getByTestId('inbox-t1')).toContainText('处理订单')
  await page.screenshot({ path: `${evidence}/notification-desktop.png` })
  await page.getByTestId('open-t1').click()
  await expect(page).toHaveURL('http://127.0.0.1:5174/apps/demo/overview')
  await expect(page.getByRole('heading', { name: '示例子应用 v1' })).toBeVisible()
  await page.getByTestId('order-close-o1').click()
  await expect(page.getByRole('row').filter({ hasText: '待处理订单' })).toContainText('已关闭')
  await page.getByTestId('notification-bell').click()
  await page.getByTestId('tab-todos').click()
  await expect(page.getByText('暂无待办', { exact: true })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByTestId('tab-announcements').click()
  await expect(page.getByRole('heading', { name: /^服务公告/ })).toBeVisible()
  const overflowingControls = await page.locator('.shell-header').evaluate((header) =>
    Array.from(header.querySelectorAll('a, button, [role="combobox"]'))
      .filter((control) => {
        const box = control.getBoundingClientRect()
        return box.width > 0 && box.height > 0 && (box.left < 0 || box.right > innerWidth + 1)
      })
      .map((control) => control.getAttribute('aria-label') ?? control.textContent),
  )
  expect(overflowingControls).toEqual([])
  await page.screenshot({ path: `${evidence}/notification-mobile-final.png` })
})

test('租户公告管理按权限显示，发布错误保留输入并可重试、撤回', async ({ page }) => {
  await tenant(page)
  await page.goto('/notifications')
  await expect(page.getByTestId('menu-announcements')).toHaveCount(0)
  await page.goto('/announcements')
  await expect(page).toHaveURL(/\/403$/)
  await page.route('**/iam/auth/access', (route) =>
    route.fulfill(ok({ permissions: ['announcement:manage'], menus: [], subApps: [] })),
  )
  let item: Record<string, unknown> | null = null,
    retry = true
  await page.route('**/iam/announcements/manage**', (route) => {
    if (route.request().url().endsWith('/revoke')) {
      item!.revoked = true
      return route.fulfill(ok(null))
    }
    if (route.request().method() === 'POST') {
      if (retry) {
        retry = false
        return fail(route)
      }
      const body = route.request().postDataJSON()
      expect(body.tenantIds).toBeUndefined()
      expect(body.expiresAt).toMatch(/^2030-01-01T/)
      item = { ...body, id: 'a1', publishedAt: '2026-11-07T00:00:00Z', revoked: false }
      return route.fulfill(ok(item))
    }
    return route.fulfill(ok(item ? [item] : []))
  })
  await page.goto('/announcements')
  await expect(page.getByTestId('menu-announcements')).toBeVisible()
  await page.getByTestId('announcement-create').click()
  const dialog = page.getByRole('dialog', { name: '发布公告', exact: true })
  await dialog.getByLabel('标题', { exact: true }).fill('租户维护')
  await dialog.getByLabel('正文（纯文本）').fill('请提前保存工作')
  await dialog.getByLabel('到期时间（可选）').fill('2030-01-01T12:00')
  await dialog.getByLabel('Sub-App 编码（可选）').fill('demo')
  await dialog.getByLabel('业务路由（可选）').fill('/overview')
  await dialog.getByRole('button', { name: '确认发布' }).click()
  await expect(dialog.getByRole('alert')).toContainText('暂时无法保存，请重试')
  await expect(dialog.getByLabel('标题', { exact: true })).toHaveValue('租户维护')
  await dialog.getByRole('button', { name: '确认发布' }).click()
  await expect(dialog).toBeHidden()
  await expect(announcementRow(page, 'a1')).toContainText('发布中')
  await page.screenshot({ path: `${evidence}/tenant-announcement.png` })
  await page.getByTestId('announcement-revoke-a1').click()
  await page.getByTestId('announcement-confirm-revoke').click()
  await expect(announcementRow(page, 'a1')).toContainText('已撤回')
})

test('平台公告管理具有独立权限，指定租户表单验证并发布撤回', async ({ page }) => {
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.platform.session',
      JSON.stringify({ accessToken: 'platform', refreshToken: 'r', operatorName: '公告运营' }),
    ),
  )
  await page.route('**/iam/platform/access', (route) =>
    route.fulfill(ok({ roles: [], permissions: [] })),
  )
  await page.goto('http://127.0.0.1:5173/announcements')
  await expect(page).toHaveURL('http://127.0.0.1:5173/403')
  await page.route('**/iam/platform/access', (route) =>
    route.fulfill(ok({ roles: [], permissions: ['platform:announcement:manage'] })),
  )
  let item: Record<string, unknown> | null = null
  await page.route('**/iam/platform/announcements**', (route) => {
    expect(route.request().headers().authorization).toBe('Bearer platform')
    if (route.request().url().endsWith('/revoke')) {
      item!.revoked = true
      return route.fulfill(ok(null))
    }
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON()
      expect(body.allTenants).toBe(false)
      expect(body.tenantIds).toEqual(['019a2525-0000-7000-8000-000000000001'])
      item = { ...body, id: 'p1', publishedAt: '2026-11-07T00:00:00Z', revoked: false }
      return route.fulfill(ok(item))
    }
    return route.fulfill(ok(item ? [item] : []))
  })
  await page.goto('http://127.0.0.1:5173/announcements')
  await expect(page).toHaveTitle('SaaS Forge 平台控制台')
  await expect(page.getByRole('heading', { name: '公告管理', exact: true })).toBeVisible()
  await page.getByTestId('announcement-create').click()
  const dialog = page.getByRole('dialog', { name: '发布公告', exact: true })
  await dialog.getByLabel('标题', { exact: true }).fill('指定租户公告')
  await dialog.getByLabel('正文（纯文本）').fill('<strong>保留纯文本</strong>')
  await dialog.locator('.el-radio').filter({ hasText: '指定租户' }).click()
  await expect(dialog.getByRole('button', { name: '确认发布' })).toBeDisabled()
  await dialog.getByLabel('租户标识（UUID）', { exact: true }).fill('错误标识')
  await expect(dialog.getByRole('button', { name: '确认发布' })).toBeDisabled()
  await dialog
    .getByLabel('租户标识（UUID）', { exact: true })
    .fill('019a2525-0000-7000-8000-000000000001')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: `${evidence}/platform-announcement-mobile-form.png` })
  await dialog.getByRole('button', { name: '确认发布' }).click()
  await expect(dialog).toBeHidden()
  await expect(announcementRow(page, 'p1')).toContainText('<strong>保留纯文本</strong>')
  await expect(page.locator('tbody strong')).toHaveCount(0)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: `${evidence}/platform-announcement.png` })
  await page.getByTestId('announcement-revoke-p1').click()
  await page.getByTestId('announcement-confirm-revoke').click()
  await expect(announcementRow(page, 'p1')).toContainText('已撤回')
})
