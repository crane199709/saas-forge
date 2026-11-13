import { mkdir } from 'node:fs/promises'
import { expect, test, type Page } from '@playwright/test'

const password = 'Demo-password-123!'
const memberA = '019a2525-0000-7000-8000-000000000031'

async function login(page: Page, account = 'e2e-member') {
  await page.goto('/')
  await page.getByLabel('账号或邮箱').fill(account)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  if (account === 'e2e-member') {
    await expect(page.getByRole('heading', { name: '选择租户' })).toBeVisible()
    await page.getByRole('button', { name: 'E2E 租户 A', exact: true }).click()
  }
  await page.getByTestId('menu-group-demo').locator('.el-sub-menu__title').first().click()
  await expect(page.getByTestId('menu-demo.overview')).toBeVisible()
}

async function token(page: Page): Promise<string> {
  return page.evaluate(() => JSON.parse(localStorage.getItem('forge.tenant.session')!).accessToken)
}

// 工作台读取在本次临时数据库播种的 A/B 数据；全程真实登录、网关与远程子应用。
test('工作台登录、三卡、待办跳转与切租户刷新', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await login(page)
  await expect(page).toHaveURL('http://127.0.0.1:15174/')
  await expect(page).toHaveTitle('SaaS Forge 租户端')
  await expect(page.getByRole('heading', { name: '工作台', exact: true })).toBeVisible()
  await expect(page.getByTestId('workbench-greeting')).toHaveText('e2e-member · E2E 租户 A')
  for (const [kind, title] of [
    ['todos', 'A 待处理订单'],
    ['notifications', 'A 订单审核提醒'],
    ['announcements', 'A 租户业务公告'],
  ]) {
    const card = page.getByTestId(`workbench-${kind}`)
    await expect(card).toBeVisible()
    await expect(card.getByRole('link', { name: title, exact: true })).toBeVisible()
    await expect(card).not.toContainText('B ')
  }
  await expect(page.getByTestId('workbench-notifications').getByLabel('1 条未读通知')).toBeVisible()
  await mkdir('/tmp/workbench-qa', { recursive: true })
  await page.screenshot({ path: '/tmp/workbench-qa/real-desktop.png', animations: 'disabled' })
  await page.getByRole('link', { name: 'A 待处理订单', exact: true }).click()
  await expect(page).toHaveURL('http://127.0.0.1:15174/apps/demo/overview')
  await expect(page.getByRole('heading', { name: '示例子应用 v1' })).toBeVisible()
  await expect(page.getByTestId('order-title')).toHaveText(['A 本人订单'])

  await page.getByTestId('tenant-switcher').click()
  await page.getByRole('option', { name: 'E2E 租户 B', exact: true }).click()
  await expect(page.getByTestId('workbench-greeting')).toHaveText('e2e-member · E2E 租户 B')
  await expect(page.getByRole('link', { name: 'B 待处理订单', exact: true })).toBeVisible()
  await expect(page.getByTestId('workbench-notifications')).toContainText('B 订单审核提醒')
  await expect(page.getByTestId('workbench-announcements')).toContainText('B 租户业务公告')
  await expect(page.getByRole('link', { name: /^A / })).toHaveCount(0)
  // 将当前通知标为已读，手动刷新必须读取新状态，且其他卡片保持当前租户数据。
  const markRead = await page.request.post(
    '/api/iam/notifications/019a2525-0000-7000-8000-000000000082/read',
    {
      headers: { Authorization: `Bearer ${await token(page)}` },
    },
  )
  expect(markRead.status()).toBe(200)
  await page.getByTestId('workbench-refresh').click()
  await expect(page.getByTestId('workbench-notifications')).toContainText('暂无未读通知')
  await expect(page.getByTestId('workbench-notifications').getByLabel('0 条未读通知')).toBeVisible()
  await expect(page.getByRole('link', { name: 'B 待处理订单', exact: true })).toBeVisible()
  await expect(page.getByTestId('workbench-announcements')).toContainText('B 租户业务公告')
  await expect(page.locator('vite-error-overlay, webpack-dev-server-client-overlay')).toHaveCount(0)
  expect(errors).toEqual([])
})

// 不拦截网络、不注入会话：浏览器经历真实的密码登录、授权码 + PKCE、网关和动态远程加载。
test('双 Membership 登录、数据范围与租户切换', async ({ page }) => {
  const remote = page.waitForResponse('http://127.0.0.1:15175/remoteEntry.js')
  await login(page)
  await expect(page.getByTestId('menu-audit')).toBeAttached()
  await page.getByTestId('menu-demo.overview').click()
  expect((await remote).status()).toBe(200)
  await expect(page.getByRole('heading', { name: '示例子应用 v1' })).toBeVisible()
  await expect(page.getByTestId('create-order')).toBeVisible()
  await expect(page.getByTestId('order-title')).toHaveText(['A 本人订单'])

  await page.getByTestId('tenant-switcher').click()
  await page.getByRole('option', { name: 'E2E 租户 B', exact: true }).click()
  await expect(page.getByTestId('workbench-greeting')).toHaveText('e2e-member · E2E 租户 B')
  await expect(page.getByTestId('menu-audit')).toHaveCount(0)
  await page.getByTestId('menu-group-demo').locator('.el-sub-menu__title').first().click()
  await page.getByTestId('menu-demo.overview').click()
  await expect(page.getByTestId('remote-tenant')).toHaveText('当前租户：E2E 租户 B')
  await expect(page.getByTestId('create-order')).toBeHidden()
  await expect(page.getByTestId('order-title')).toHaveText(['B 本人订单', 'B 他人订单'])
  // 按钮隐藏之外，真实服务也必须拒绝未授权写入。
  const denied = await page.request.post('/api/demo/orders', {
    headers: { Authorization: `Bearer ${await token(page)}` },
    data: { title: '不应创建', amount: 1 },
  })
  expect(denied.status()).toBe(403)
})

test('Tenant Realm 无法访问 platform-console 接口', async ({ page }) => {
  await login(page)
  const authorization = `Bearer ${await token(page)}`
  const allowed = await page.request.get('/api/demo/orders', { headers: { authorization } })
  expect(allowed.status()).toBe(200)
  for (const path of ['/platform/operators/current', '/iam/platform/roles']) {
    const denied = await page.request.get(`http://127.0.0.1:18000${path}`, {
      headers: { authorization },
    })
    expect(denied.status()).toBe(401)
    expect((await denied.json()).code).toBe('auth.unauthenticated')
  }
})

test('管理员停用成员后下一次操作立即登出', async ({ page, browser }) => {
  const adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:15174' })
  const admin = await adminContext.newPage()
  try {
    await login(admin, 'e2e-admin')
    const headers = { Authorization: `Bearer ${await token(admin)}` }
    await login(page)
    await page.getByTestId('menu-demo.overview').click()
    await expect(page.getByTestId('order-title')).toHaveText(['A 本人订单'])
    const accessToken = await token(page)
    const suspended = await admin.request.post(`/api/iam/members/${memberA}/suspend`, { headers })
    expect(suspended.status()).toBe(200)
    try {
      const rejected = page.waitForResponse(
        (r) => r.url().includes('/demo/orders') && r.status() === 401,
      )
      await page.getByRole('button', { name: '刷新订单', exact: true }).click()
      await rejected
      await expect(page.getByLabel('账号或邮箱')).toBeVisible()
      await expect(page.getByTestId('order-title')).toHaveCount(0)
      expect(await page.evaluate(() => localStorage.getItem('forge.tenant.session'))).toBeNull()
      const replay = await page.request.get('/api/demo/orders', {
        headers: { Authorization: `Bearer ${accessToken}` },
      })
      expect(replay.status()).toBe(401)
    } finally {
      const resumed = await admin.request.post(`/api/iam/members/${memberA}/resume`, { headers })
      expect(resumed.status()).toBe(200)
    }
  } finally {
    await adminContext.close()
  }
})

test('字典有效修改产生历史，任意版本可比较且无变化保存不增版', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') errors.push(message.text())
  })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await login(page, 'e2e-admin')
  await page.goto('/dictionaries')
  await expect(page).toHaveURL(/\/dictionaries$/)
  await expect(page).toHaveTitle('SaaS Forge 租户端')
  await expect(page.getByRole('heading', { name: '字典', exact: true })).toBeVisible()
  await page.getByTestId('dictionary-demo-service/customer-source').click()
  await page.getByRole('tab', { name: '变更历史' }).click()
  await expect(page.getByText('暂无变更历史')).toBeVisible()

  const code = `history-${Date.now()}`
  const custom = `custom.${code}`
  async function items() {
    await page.getByRole('tab', { name: '字典项', exact: true }).click()
  }
  async function save() {
    await page.getByTestId('item-save').click()
    await expect(page.getByRole('dialog')).toBeHidden()
  }
  async function history(versions: number) {
    await page.getByRole('tab', { name: '变更历史' }).click()
    await expect(page.getByTestId('history-list').locator('tbody tr')).toHaveCount(versions)
    await expect(page.getByTestId(`history-detail-${versions}`)).toBeVisible()
  }
  async function selectVersion(field: 'from' | 'to', version: number) {
    const select = page.getByTestId(`history-${field}`)
    await select.click()
    const listbox = await select.getByRole('combobox').getAttribute('aria-controls')
    await page
      .locator(`[id="${listbox}"]`)
      .getByRole('option', { name: `v${version}`, exact: true })
      .click()
  }

  await items()
  await page.getByTestId('item-edit-online').click()
  await page.getByTestId('item-name').fill('线上验收渠道')
  await save()
  await history(1)
  await expect(page.getByTestId('history-list')).toContainText('e2e-admin')
  await expect(page.getByTestId('history-list')).not.toContainText(
    '019a2525-0000-7000-8000-000000000012',
  )
  await expect(
    page
      .getByTestId('history-list')
      .locator('[title="身份 ID：019a2525-0000-7000-8000-000000000012"]'),
  ).toHaveText('e2e-admin')
  await page.getByTestId('history-detail-1').click()
  await expect(page.getByTestId('history-snapshot')).toContainText('线上验收渠道')
  await expect(page.getByTestId('history-manifest')).toContainText('线上')

  await items()
  await page.getByTestId('new-item-create').click()
  await page.getByTestId('item-code').fill(code)
  await page.getByTestId('item-name').fill('验收自定义渠道')
  await save()
  await expect(page.getByTestId(`item-${custom}`)).toBeVisible()
  await page.getByTestId('item-edit-online').click()
  await page.getByTestId('item-name').fill('线上二次改名')
  await page.getByTestId('item-sort').locator('input').fill('9')
  await save()
  await history(3)

  await selectVersion('from', 1)
  await selectVersion('to', 3)
  await page.getByTestId('history-compare').click()
  await expect(page.getByTestId('history-added')).toContainText(custom)
  await expect(page.getByTestId('history-changed')).toContainText('线上验收渠道')
  await expect(page.getByTestId('history-changed')).toContainText('线上二次改名')
  await expect(page.getByTestId('history-changed')).toContainText('排序：9')
  await page.getByTestId('history-detail-1').click()
  await expect(page.getByTestId('history-snapshot')).toContainText('线上验收渠道')
  await expect(page.getByTestId('history-snapshot')).not.toContainText(custom)
  await expect(page.getByTestId('history-current')).toContainText('线上二次改名')
  const evidence = '/tmp/dictionary-history-qa'
  await mkdir(evidence, { recursive: true })
  await page.screenshot({
    path: `${evidence}/desktop-forward-detail.png`,
    fullPage: true,
    animations: 'disabled',
  })

  await selectVersion('from', 3)
  await selectVersion('to', 1)
  await page.getByTestId('history-compare').click()
  await expect(page.getByTestId('history-removed')).toContainText(custom)
  await expect(page.getByTestId('history-added').locator('tbody tr')).toHaveCount(0)
  await expect(page.getByTestId('history-changed')).toContainText('线上验收渠道')
  await page.setViewportSize({ width: 1024, height: 900 })
  await page.screenshot({
    path: `${evidence}/laptop-reverse-detail.png`,
    fullPage: true,
    animations: 'disabled',
  })

  await items()
  await page.getByTestId('item-edit-online').click()
  await expect(page.getByTestId('item-name')).toHaveValue('线上二次改名')
  await save()
  await history(3)
  await expect(page.getByTestId('history-detail-4')).toHaveCount(0)
  await expect(page.locator('vite-error-overlay, webpack-dev-server-client-overlay')).toHaveCount(0)
  expect(errors).toEqual([])
})
