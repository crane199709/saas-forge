import { expect, test, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'

test.use({ baseURL: 'http://127.0.0.1:5173' })

// 只替换 HTTP 边界，登录、PlatformClient、路由与页面渲染均真实运行。
const browserProblems = new WeakMap<Page, string[]>()
test.beforeEach(async ({ page }) => {
  const problems: string[] = []
  browserProblems.set(page, problems)
  page.on('pageerror', (error) => problems.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) problems.push(message.text())
  })
})
test.afterEach(async ({ page }) => {
  await expect(page.locator('vite-error-overlay, #webpack-dev-server-client-overlay')).toHaveCount(
    0,
  )
  expect(browserProblems.get(page)).toEqual([])
})

function ok(data: unknown, status = 200) {
  return { status, json: { code: 'ok', httpStatus: status, data, traceId: 'models-e2e' } }
}

test('Operator 登录后新增模型，在列表确认并取消、确认禁用', async ({ page }) => {
  await page.route('**/iam/platform/auth/login', (route) => {
    expect(route.request().postDataJSON()).toMatchObject({ account: 'operator' })
    return route.fulfill(
      ok({
        nextStep: 'authorize',
        clientId: 'platform-console',
        redirectUri: 'http://127.0.0.1:5173/',
      }),
    )
  })
  await page.route('**/iam/oauth2/authorize?*', (route) => {
    const url = new URL(route.request().url())
    expect(url.searchParams.get('scope')).toBe('platform')
    return route.fulfill({
      status: 302,
      headers: {
        Location: '/?code=model-authorization-code&state=' + url.searchParams.get('state'),
      },
    })
  })
  await page.route('**/iam/platform/auth/exchange', (route) => {
    expect(route.request().postDataJSON()).toMatchObject({ code: 'model-authorization-code' })
    return route.fulfill(
      ok({
        accessToken: 'operator-access',
        refreshToken: 'operator-refresh',
        tokenType: 'Bearer',
        expiresIn: 900,
        operatorName: '模型运营',
      }),
    )
  })
  await page.route('**/iam/platform/access', (route) =>
    route.fulfill(ok({ roles: [], permissions: ['platform:ops:manage'], layout: null })),
  )
  const content = {
    name: 'DeepSeek Chat E2E',
    provider: 'DEEPSEEK',
    modelId: 'deepseek-chat-e2e',
    baseUrl: 'https://api.deepseek.com',
    capabilities: ['CHAT', 'TOOLS'],
    contextWindow: 65536,
    status: 'ENABLED',
  }
  const models: Record<string, unknown>[] = []
  const updates: unknown[] = []
  await page.route('**/ai-platform/models**', (route) => {
    const request = route.request()
    expect(request.headers().authorization).toBe('Bearer operator-access')
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET') {
      expect(path).toBe('/api/ai-platform/models')
      return route.fulfill(ok(models))
    }
    const body = request.postDataJSON()
    if (request.method() === 'POST') {
      expect(path).toBe('/api/ai-platform/models')
      expect(body).toEqual({ ...content, apiKey: 'mock-e2e-api-key' })
      models.push({ ...content, id: 'e2e-model', credentialKind: 'API_KEY' })
      return route.fulfill(ok(models[0], 201))
    }
    expect(request.method()).toBe('PUT')
    expect(path).toBe('/api/ai-platform/models/e2e-model')
    expect(body).toEqual({ ...content, status: 'DISABLED' })
    updates.push(body)
    Object.assign(models[0]!, body)
    return route.fulfill(ok(models[0]))
  })

  await page.goto('/login?redirect=/ai-models')
  await page.getByLabel('运营账号').fill('operator')
  await page.getByLabel('密码', { exact: true }).fill('Operator-password-123!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('http://127.0.0.1:5173/ai-models')
  await expect(page).toHaveTitle('SaaS Forge 平台控制台')
  await expect(page.getByRole('heading', { name: 'AI 模型', exact: true })).toBeVisible()
  await expect(page.getByText('还没有模型。新增后可让租户选择并调用。')).toBeVisible()
  await page.getByRole('button', { name: '新增模型', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '新增模型', exact: true })
  await drawer.getByLabel('名称', { exact: true }).fill('DeepSeek Chat E2E')
  await drawer.getByLabel('模型标识', { exact: true }).fill('deepseek-chat-e2e')
  await drawer.getByLabel('接入地址', { exact: true }).fill('https://api.deepseek.com')
  await drawer.getByText('工具调用', { exact: true }).click()
  await expect(drawer.getByRole('checkbox', { name: '工具调用', exact: true })).toBeChecked()
  await drawer.getByLabel('上下文窗口', { exact: true }).fill('65536')
  await drawer.getByLabel('API Key', { exact: true }).fill('mock-e2e-api-key')
  await drawer.getByRole('button', { name: '保存', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  const row = page.getByRole('row').filter({ hasText: 'DeepSeek Chat E2E' })
  await expect(row).toContainText('直接密钥')
  await expect(row).toContainText('deepseek-chat-e2e')
  const status = row.getByRole('switch')
  await expect(status).toBeChecked()
  await row.locator('.el-switch').click()
  const confirm = page.getByRole('dialog', { name: '禁用模型：DeepSeek Chat E2E', exact: true })
  await expect(confirm).toContainText(
    '禁用后所有租户的后续模型调用都会失效，已经打开的输出流可能继续。',
  )
  await confirm.getByRole('button', { name: '取消', exact: true }).click()
  await expect(confirm).not.toBeVisible()
  await expect(status).toBeChecked()
  expect(updates).toEqual([])
  await row.locator('.el-switch').click()
  await confirm.getByRole('button', { name: '确认禁用', exact: true }).click()
  await expect(confirm).not.toBeVisible()
  await expect(status).not.toBeChecked()
  expect(updates).toEqual([{ ...content, status: 'DISABLED' }])
  await page.locator('.el-table__body-wrapper .el-scrollbar__wrap').evaluate((element) => {
    element.scrollLeft = element.scrollWidth - element.clientWidth
  })
  await page.mouse.move(0, 0)
  await mkdir('/tmp/forge-model-management/visual', { recursive: true })
  await page.screenshot({ path: '/tmp/forge-model-management/visual/e2e-model-disabled.png' })
})
