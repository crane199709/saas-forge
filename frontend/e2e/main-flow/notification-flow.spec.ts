import { expect, test, type Browser, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
const evidence = '/private/tmp/notification-center-qa'
const password = 'Demo-password-123!'
const tenantA = '019a2525-0000-7000-8000-000000000001'
const problems = new WeakMap<Page, string[]>()
function observe(page: Page) {
  const errors: string[] = []
  problems.set(page, errors)
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('console', (m) => {
    if (['error', 'warning'].includes(m.type())) errors.push(m.text())
  })
}
async function healthy(page: Page) {
  await expect(page.locator('vite-error-overlay, #webpack-dev-server-client-overlay')).toHaveCount(
    0,
  )
  expect(problems.get(page)).toEqual([])
}
async function login(page: Page, account = 'e2e-member') {
  observe(page)
  await page.goto('http://127.0.0.1:15174/')
  await page.getByLabel('账号或邮箱').fill(account)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  if (account === 'e2e-member')
    await page.getByRole('button', { name: 'E2E 租户 A', exact: true }).click()
  await expect(page.getByTestId('menu-demo.overview')).toBeVisible()
  await expect(page).toHaveTitle('SaaS Forge 租户端')
}
async function headers(page: Page) {
  const token = await page.evaluate(
    () => JSON.parse(localStorage.getItem('forge.tenant.session')!).accessToken,
  )
  return { Authorization: `Bearer ${token}` }
}
async function read(page: Page, path: string) {
  const response = await page.request.get(path, { headers: await headers(page) })
  expect(response.status()).toBe(200)
  return (await response.json()).data as Array<{
    id: string
    title: string
    body: string
    read?: boolean
    businessKey?: string
  }>
}
async function center(page: Page, tab = 'notifications') {
  await page.getByTestId('notification-bell').click()
  await expect(page.getByRole('heading', { name: '通知中心', exact: true })).toBeVisible()
  await page.getByTestId(`tab-${tab}`).click()
}
async function switchTenant(page: Page, name: string) {
  await page.getByTestId('tenant-switcher').click()
  await page.getByRole('option', { name, exact: true }).click()
  await expect(page.getByText(`当前租户：${name}`, { exact: true }).first()).toBeVisible()
}
async function publish(page: Page, title: string, target?: string) {
  await page.getByTestId('announcement-create').click()
  const dialog = page.getByRole('dialog', { name: '发布公告', exact: true })
  await dialog.getByLabel('标题', { exact: true }).fill(title)
  await dialog.getByLabel('正文（纯文本）').fill(`${title}正文`)
  if (target) {
    await dialog.locator('.el-radio').filter({ hasText: '指定租户' }).click()
    await dialog.getByLabel('租户标识（UUID）', { exact: true }).fill(target)
  }
  await dialog.getByRole('button', { name: '确认发布' }).click()
  await expect(dialog).toBeHidden()
  await expect(
    page.locator('article').filter({ has: page.getByRole('heading', { name: new RegExp(title) }) }),
  ).toContainText('发布中')
}
async function revoke(page: Page, title: string) {
  await page
    .getByRole('row')
    .filter({ hasText: title })
    .getByRole('button', { name: '撤回公告', exact: true })
    .click()
  await page.getByTestId('announcement-confirm-revoke').click()
  await expect(page.getByRole('row').filter({ hasText: title })).toContainText('已撤回')
}
async function isolated(browser: Browser, url: string) {
  const context = await browser.newContext({
    baseURL: url,
    viewport: { width: 1440, height: 1000 },
  })
  return { context, page: await context.newPage() }
}
test.beforeAll(() => mkdir(evidence, { recursive: true }).then(() => {}))
// 无网络拦截、无会话注入、无 IAM inbox/Todo 种子：业务事务真实进入 SDK、Outbox、RocketMQ，再由 IAM 消费。
test('真实MQ：订单通知、已读、处理待办、租户切换隔离', async ({ page }) => {
  await login(page)
  const title = `MQ闭环订单-${Date.now()}`
  const response = await page.request.post('/demo/orders', {
    headers: await headers(page),
    data: { title, amount: 25 },
  })
  expect(response.status()).toBe(201)
  const order = (await response.json()).data as { id: string; number: string }
  let noticeId = ''
  await expect
    .poll(async () => {
      const found = (await read(page, '/iam/notifications')).find((n) =>
        n.body.includes(order.number),
      )
      noticeId = found?.id ?? ''
      return Boolean(found)
    })
    .toBe(true)
  let todoId = ''
  await expect
    .poll(async () => {
      const found = (await read(page, '/iam/todos')).find(
        (t) => t.businessKey === `order:${order.id}`,
      )
      todoId = found?.id ?? ''
      return Boolean(found)
    })
    .toBe(true)
  await center(page)
  await expect(page.getByTestId(`inbox-${noticeId}`)).toContainText('订单已创建')
  await expect(page.getByTestId('notification-bell')).toHaveAccessibleName('通知中心，1 条未读')
  await page.screenshot({ path: `${evidence}/real-mq-notification.png` })
  await page.getByTestId(`mark-read-${noticeId}`).click()
  await expect(page.getByTestId(`inbox-${noticeId}`)).toContainText('已读')
  await expect(page.getByTestId('notification-bell')).toHaveAccessibleName('通知中心，0 条未读')
  await page.getByTestId('tab-todos').click()
  await expect(page.getByTestId(`inbox-${todoId}`)).toContainText(title)
  await page.screenshot({ path: `${evidence}/real-mq-todo.png` })
  await page.getByTestId(`open-${todoId}`).click()
  await expect(page).toHaveURL('http://127.0.0.1:15174/apps/demo/overview')
  await expect(page.getByRole('heading', { name: '示例子应用 v1' })).toBeVisible()
  await page.getByTestId(`order-close-${order.id}`).click()
  await expect(page.getByRole('row').filter({ hasText: title })).toContainText('已关闭')
  await page.screenshot({ path: `${evidence}/real-mq-order-closed.png` })
  await expect
    .poll(async () => (await read(page, '/iam/todos')).some((t) => t.id === todoId))
    .toBe(false)
  await center(page, 'todos')
  await expect(page.getByTestId(`inbox-${todoId}`)).toHaveCount(0)
  await switchTenant(page, 'E2E 租户 B')
  await center(page)
  await expect(page.getByTestId(`inbox-${noticeId}`)).toHaveCount(0)
  await page.getByTestId('tab-todos').click()
  await expect(page.getByTestId(`inbox-${todoId}`)).toHaveCount(0)
  await switchTenant(page, 'E2E 租户 A')
  await center(page)
  await expect(page.getByTestId(`inbox-${noticeId}`)).toContainText('已读')
  await healthy(page)
})

test('真实HTTP：租户与平台公告发布、目标隔离、已读和撤回', async ({ page, browser }) => {
  const admin = await isolated(browser, 'http://127.0.0.1:15174')
  const operator = await isolated(browser, 'http://127.0.0.1:15173')
  try {
    await login(page)
    await login(admin.page, 'e2e-admin')
    await admin.page.getByTestId('menu-announcements').click()
    await expect(admin.page.getByRole('heading', { name: '公告管理', exact: true })).toBeVisible()
    await publish(admin.page, '租户A维护公告')
    await center(page, 'announcements')
    await expect(page.locator('article').filter({ hasText: '租户A维护公告' })).toBeVisible()
    await expect(page.getByTestId('notification-bell')).toHaveAccessibleName('通知中心，1 条未读')
    await page
      .locator('article')
      .filter({ hasText: '租户A维护公告' })
      .getByRole('button', { name: '标为已读' })
      .click()
    await expect(page.locator('article').filter({ hasText: '租户A维护公告' })).toContainText('已读')
    await switchTenant(page, 'E2E 租户 B')
    await center(page, 'announcements')
    await expect(page.getByRole('heading', { name: /^租户A维护公告/ })).toHaveCount(0)
    await revoke(admin.page, '租户A维护公告')
    await switchTenant(page, 'E2E 租户 A')
    await center(page, 'announcements')
    await expect(page.getByRole('heading', { name: /^租户A维护公告/ })).toHaveCount(0)
    observe(operator.page)
    await operator.page.goto('http://127.0.0.1:15173/')
    await operator.page.locator('input[name="account"]').fill('e2e-announcement')
    await operator.page.getByLabel('密码', { exact: true }).fill(password)
    await operator.page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(operator.page).toHaveTitle('SaaS Forge 平台控制台')
    await expect(
      operator.page.getByRole('heading', { name: '公告管理', exact: true }),
    ).toBeVisible()
    await publish(operator.page, '全部租户公告')
    await page.getByRole('button', { name: '刷新列表', exact: true }).click()
    await expect(page.getByRole('heading', { name: /^全部租户公告/ })).toBeVisible()
    await switchTenant(page, 'E2E 租户 B')
    await center(page, 'announcements')
    await expect(page.getByRole('heading', { name: /^全部租户公告/ })).toBeVisible()
    await revoke(operator.page, '全部租户公告')
    await page.getByRole('button', { name: '刷新列表', exact: true }).click()
    await expect(page.getByRole('heading', { name: /^全部租户公告/ })).toHaveCount(0)
    await publish(operator.page, '指定租户A公告', tenantA)
    await page.getByRole('button', { name: '刷新列表', exact: true }).click()
    await expect(page.getByRole('heading', { name: /^指定租户A公告/ })).toHaveCount(0)
    await switchTenant(page, 'E2E 租户 A')
    await center(page, 'announcements')
    await expect(page.getByRole('heading', { name: /^指定租户A公告/ })).toBeVisible()
    await page.screenshot({ path: `${evidence}/real-announcement-tenant.png` })
    await operator.page.screenshot({ path: `${evidence}/real-announcement-platform.png` })
    await revoke(operator.page, '指定租户A公告')
    await page.getByRole('button', { name: '刷新列表', exact: true }).click()
    await expect(page.getByRole('heading', { name: /^指定租户A公告/ })).toHaveCount(0)
    await healthy(page)
    await healthy(admin.page)
    await healthy(operator.page)
  } finally {
    await admin.context.close()
    await operator.context.close()
  }
})
