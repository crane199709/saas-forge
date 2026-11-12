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
  await expect(page.getByTestId('menu-demo.overview')).toBeVisible()
}

async function token(page: Page): Promise<string> {
  return page.evaluate(() => JSON.parse(localStorage.getItem('forge.tenant.session')!).accessToken)
}

// 不拦截网络、不注入会话：浏览器经历真实的密码登录、授权码 + PKCE、网关和动态远程加载。
test('双 Membership 登录、数据范围与租户切换', async ({ page }) => {
  const remote = page.waitForResponse('http://127.0.0.1:15175/remoteEntry.js')
  await login(page)
  await expect(page.getByTestId('menu-audit')).toBeVisible()
  await page.getByTestId('menu-demo.overview').click()
  expect((await remote).status()).toBe(200)
  await expect(page.getByRole('heading', { name: '示例子应用 v1' })).toBeVisible()
  await expect(page.getByTestId('create-order')).toBeVisible()
  await expect(page.getByTestId('order-title')).toHaveText(['A 本人订单'])

  await page.getByTestId('tenant-switcher').click()
  await page.getByRole('option', { name: 'E2E 租户 B', exact: true }).click()
  await expect(page.getByTestId('home-tenant')).toHaveText('E2E 租户 B')
  await expect(page.getByTestId('menu-audit')).toBeHidden()
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
