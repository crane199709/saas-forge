import { expect, test, type Page, type Route } from '@playwright/test'

test.use({ baseURL: 'http://127.0.0.1:5173' })

// 浏览器只替换公开 HTTP 边界，渲染、路由、权限与 PlatformClient 均真实运行。
const browserProblems = new WeakMap<Page, string[]>()
test.beforeEach(async ({ page }) => {
  const problems: string[] = []
  browserProblems.set(page, problems)
  page.on('pageerror', (error) => problems.push(error.message))
  page.on('console', (message) => {
    if (!['error', 'warning'].includes(message.type())) return
    // 用例主动制造的业务拒绝/服务故障，Chrome 会记录资源加载错误。
    if (
      /Failed to load resource: the server responded with a status of (409|503)/.test(
        message.text(),
      )
    )
      return
    problems.push(message.text())
  })
})
test.afterEach(async ({ page }) => {
  await expect(page.locator('vite-error-overlay, #webpack-dev-server-client-overlay')).toHaveCount(
    0,
  )
  expect(browserProblems.get(page)).toEqual([])
})

const failedTenant = '018d0000-0000-7000-8000-000000000010'
const run = {
  id: '018d0000-0000-7000-8000-000000000001',
  name: '租户 清理',
  type: 'TENANT',
  trigger: 'SCHEDULED',
  operatorId: null,
  targetTenantId: null,
  status: 'PARTIALLY_FAILED',
  startedAt: '2026-11-07T03:00:00Z',
  finishedAt: '2026-11-07T03:02:00Z',
  successCount: 12,
  failureCount: 103,
  failedTenants: Array.from({ length: 100 }, (_, i) => ({
    tenantId:
      i === 0 ? failedTenant : `018d0000-0000-7000-8000-${String(i + 20).padStart(12, '0')}`,
    errorSummary: 'ForgeException: tenant.cleanupFailed',
  })),
  errorSummary: null,
}
function ok(data: unknown) {
  return { json: { code: 'ok', httpStatus: 200, data, traceId: 'task-ok' } }
}
async function fail(route: Route, status: number, code: string, userTip?: string) {
  await route.fulfill({
    status,
    json: { code, httpStatus: status, userTip, traceId: `task-${status}` },
  })
}
async function signIn(page: Page, permissions = ['platform:ops:read', 'platform:ops:manage']) {
  await page.addInitScript(() => {
    localStorage.setItem(
      'forge.platform.session',
      JSON.stringify({
        accessToken: 'operator-access',
        refreshToken: 'operator-refresh',
        operatorName: '值班运营',
      }),
    )
  })
  await page.route('**/iam/platform/access', (route) =>
    route.fulfill(ok({ roles: [], permissions })),
  )
}
async function choose(page: Page, label: string, option: string) {
  await page
    .locator('.el-select')
    .filter({ has: page.getByRole('combobox', { name: label, exact: true }) })
    .click()
  await page.getByRole('option', { name: option, exact: true }).click()
}
async function services(page: Page) {
  await page.route('**/platform/ops/services', (route) =>
    route.fulfill(
      ok([
        { service: 'orders', available: true },
        { service: 'billing', available: false },
        { service: 'shipping', available: true },
      ]),
    ),
  )
}
const definitions = [
  {
    name: '租户 清理',
    type: 'TENANT',
    cron: '0 0 3 * * *',
    zone: 'Asia/Shanghai',
    fixedDelay: null,
    latestRun: run,
  },
  {
    name: 'health-check',
    type: 'GLOBAL',
    cron: null,
    zone: null,
    fixedDelay: 'PT1H',
    latestRun: null,
  },
]

test('按服务查看规则、最近任务运行与问题运行，读取失败租户上限和未列出数', async ({ page }) => {
  await signIn(page)
  await services(page)
  await page.route('**/platform/ops/services/orders/tasks**', async (route) => {
    const request = route.request()
    expect(request.headers().authorization).toBe('Bearer operator-access')
    const url = new URL(request.url())
    if (url.pathname.endsWith('/tasks')) return route.fulfill(ok(definitions))
    expect(decodeURIComponent(url.pathname)).toBe(
      '/platform/ops/services/orders/tasks/租户 清理/runs',
    )
    return route.fulfill(
      ok(
        url.searchParams.get('view') === 'problems'
          ? [run]
          : [
              {
                ...run,
                id: 'succeeded-run',
                status: 'SUCCEEDED',
                failureCount: 0,
                failedTenants: [],
              },
              run,
            ],
      ),
    )
  })
  await page.goto('/task-runs')
  await expect(page).toHaveURL('http://127.0.0.1:5173/task-runs')
  await expect(page).toHaveTitle('SaaS Forge 平台控制台')
  await expect(page.getByRole('heading', { name: '任务运行', exact: true })).toBeVisible()
  await expect(page.getByText('这里只保留最近的运行记录，不用于长期留存或统计。')).toBeVisible()
  const task = page.getByRole('row').filter({ hasText: '租户 清理' })
  await expect(task).toContainText('0 0 3 * * *')
  await expect(task).toContainText('Asia/Shanghai')
  await expect(task).toContainText('部分失败')
  await expect(page.getByRole('row').filter({ hasText: 'health-check' })).toContainText('PT1H')
  await expect(page.getByRole('row').filter({ hasText: 'health-check' })).toContainText('尚未运行')
  await page.screenshot({ animations: 'disabled', path: '/private/tmp/task-run-ui-desktop.png' })
  await task.getByRole('button', { name: '运行历史' }).click()
  const history = page.getByRole('dialog', { name: '运行历史：租户 清理' })
  await expect(history.getByRole('row').filter({ hasText: 'succeeded-run' })).toContainText('成功')
  await choose(page, '运行视图', '最近非成功运行')
  await expect(history.getByText('succeeded-run')).toHaveCount(0)
  const problem = history.getByRole('row').filter({ hasText: run.id })
  await expect(problem).toContainText('103')
  await problem.getByRole('button', { name: '详情' }).click()
  const detail = page.getByRole('dialog', { name: '任务运行详情' })
  await expect(detail).toContainText(
    '已列出 100 个失败租户，另有 3 个未列出，请查看服务日志或重跑整个任务。',
  )
  await expect(detail.getByRole('row').filter({ hasText: failedTenant })).toContainText(
    'ForgeException: tenant.cleanupFailed',
  )
  await page.screenshot({
    animations: 'disabled',
    path: '/private/tmp/task-run-ui-failed-tenants.png',
  })
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
})

test('手动触发和失败租户重跑须确认，展示已在运行拒绝及异步受理结果', async ({ page }) => {
  await signIn(page)
  await services(page)
  let triggerAttempts = 0
  const commands: unknown[] = []
  await page.route('**/platform/ops/services/orders/tasks**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    expect(request.headers().authorization).toBe('Bearer operator-access')
    if (url.pathname.endsWith('/trigger')) {
      expect(decodeURIComponent(url.pathname)).toBe(
        '/platform/ops/services/orders/tasks/租户 清理/trigger',
      )
      expect(request.method()).toBe('POST')
      const body = request.postDataJSON()
      commands.push(body)
      if (triggerAttempts++ === 0) return fail(route, 409, 'task.alreadyRunning')
      return route.fulfill(ok({ runId: 'accepted-run' }))
    }
    return route.fulfill(
      ok(
        url.pathname.endsWith('/runs')
          ? [run]
          : definitions.map((item) =>
              item.name === run.name && triggerAttempts > 1
                ? {
                    ...item,
                    latestRun: { ...run, id: 'accepted-run', status: 'RUNNING', finishedAt: null },
                  }
                : item,
            ),
      ),
    )
  })
  await page.goto('/task-runs')
  const task = page.getByRole('row').filter({ hasText: '租户 清理' })
  await task.getByRole('button', { name: '手动触发', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '确认手动触发', exact: true })
  await expect(confirmation).toContainText('orders · 租户 清理')
  expect(commands).toEqual([])
  await confirmation.getByRole('button', { name: '取消', exact: true }).click()
  expect(commands).toEqual([])
  await task.getByRole('button', { name: '手动触发', exact: true }).click()
  await confirmation.getByRole('button', { name: '确认触发', exact: true }).click()
  await expect(confirmation.getByRole('alert')).toContainText(
    '任务已在运行，请等待当前运行结束后重试',
  )
  await expect(confirmation.getByRole('alert')).toContainText('task-409')
  await page.screenshot({
    animations: 'disabled',
    path: '/private/tmp/task-run-ui-already-running.png',
  })
  await confirmation.getByRole('button', { name: '确认触发', exact: true }).click()
  await expect(confirmation).not.toBeVisible()
  await expect(page.getByRole('alert').filter({ hasText: '已受理' })).toContainText('accepted-run')
  await expect(task).toContainText('运行中')
  await task.getByRole('button', { name: '运行历史' }).click()
  const history = page.getByRole('dialog', { name: '运行历史：租户 清理' })
  await history.getByRole('button', { name: '详情', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '任务运行详情' })
  await page.setViewportSize({ width: 390, height: 844 })
  const failed = detail.getByRole('row').filter({ hasText: failedTenant })
  await failed.getByRole('button', { name: '单独重跑', exact: true }).click()
  const retry = page.getByRole('dialog', { name: '确认单租户重跑', exact: true })
  await expect(retry).toContainText(failedTenant)
  await page.screenshot({
    animations: 'disabled',
    path: '/private/tmp/task-run-ui-mobile-confirm.png',
  })
  await retry.getByRole('button', { name: '确认触发', exact: true }).click()
  await expect(retry).not.toBeVisible()
  const accepted = detail.getByRole('alert').filter({ hasText: '已受理' })
  await accepted.scrollIntoViewIfNeeded()
  await expect(accepted).toBeVisible()
  await expect(accepted).toContainText('accepted-run')
  await page.screenshot({
    animations: 'disabled',
    path: '/private/tmp/task-run-ui-mobile-detail.png',
  })
  await detail.getByRole('button', { name: '关闭', exact: true }).click()
  await history.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(history).not.toBeVisible()
  await expect(detail).not.toBeVisible()
  await expect(page.getByRole('heading', { name: '任务运行', exact: true })).toBeVisible()
  await page.screenshot({
    animations: 'disabled',
    path: '/private/tmp/task-run-ui-mobile-list.png',
  })
  await choose(page, '服务', 'billing（不可用）')
  await expect(page.getByRole('alert').filter({ hasText: '服务 billing 当前不可用' })).toBeVisible()
  expect(commands).toEqual([{}, {}, { targetTenantId: failedTenant }])
})

test('切换服务后旧服务慢响应不会覆盖当前任务列表', async ({ page }) => {
  await signIn(page)
  await services(page)
  let release = () => {}
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let refreshing = false
  let ordersRequests = 0
  await page.route('**/platform/ops/services/*/tasks', async (route) => {
    if (route.request().url().includes('/shipping/'))
      return route.fulfill(ok([{ ...definitions[1], name: 'shipping-cleanup' }]))
    if (ordersRequests++ > 0) {
      refreshing = true
      await gate
    }
    return route.fulfill(ok(definitions))
  })
  await page.goto('/task-runs')
  await expect(page.getByRole('row').filter({ hasText: 'health-check' })).toBeVisible()
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect.poll(() => refreshing).toBe(true)
  await choose(page, '服务', 'shipping')
  await expect(page.getByRole('row').filter({ hasText: 'shipping-cleanup' })).toBeVisible()
  const oldResponse = page.waitForResponse((response) => response.url().endsWith('/orders/tasks'))
  release()
  await oldResponse
  await expect(page.getByRole('row').filter({ hasText: 'shipping-cleanup' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: 'health-check' })).toHaveCount(0)
})

test('关闭历史后切换任务和运行视图，旧请求不能覆盖当前运行', async ({ page }) => {
  await signIn(page)
  await services(page)
  let releaseTask = () => {}
  let releaseView = () => {}
  const taskGate = new Promise<void>((resolve) => {
    releaseTask = resolve
  })
  const viewGate = new Promise<void>((resolve) => {
    releaseView = resolve
  })
  let taskPending = false
  let viewPending = false
  await page.route('**/platform/ops/services/orders/tasks**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/tasks')) return route.fulfill(ok(definitions))
    if (decodeURIComponent(url.pathname).includes('/租户 清理/')) {
      taskPending = true
      await taskGate
      return route.fulfill(ok([run]))
    }
    if (url.searchParams.get('view') === 'problems') {
      viewPending = true
      await viewGate
      return route.fulfill(ok([{ ...run, id: 'stale-problem' }]))
    }
    return route.fulfill(
      ok([
        {
          ...run,
          id: 'fresh-global-run',
          name: 'health-check',
          type: 'GLOBAL',
          status: 'FAILED',
          errorSummary: 'IllegalStateException',
          failedTenants: [],
          failureCount: 0,
        },
      ]),
    )
  })
  await page.goto('/task-runs')
  await page
    .getByRole('row')
    .filter({ hasText: '租户 清理' })
    .getByRole('button', { name: '运行历史' })
    .click()
  await expect.poll(() => taskPending).toBe(true)
  await page
    .getByRole('dialog', { name: '运行历史：租户 清理' })
    .getByRole('button', { name: '关闭', exact: true })
    .click()
  await page
    .getByRole('row')
    .filter({ hasText: 'health-check' })
    .getByRole('button', { name: '运行历史' })
    .click()
  const history = page.getByRole('dialog', { name: '运行历史：health-check' })
  await expect(history.getByText('fresh-global-run')).toBeVisible()
  const taskResponse = page.waitForResponse((response) =>
    decodeURIComponent(response.url()).includes('/租户 清理/runs'),
  )
  releaseTask()
  await taskResponse
  await expect(history.getByText('fresh-global-run')).toBeVisible()
  await expect(history.getByText(run.id)).toHaveCount(0)
  await choose(page, '运行视图', '最近非成功运行')
  await expect.poll(() => viewPending).toBe(true)
  await choose(page, '运行视图', '最近运行')
  await expect(history.getByText('fresh-global-run')).toBeVisible()
  const viewResponse = page.waitForResponse((response) => response.url().includes('view=problems'))
  releaseView()
  await viewResponse
  await expect(history.getByText('fresh-global-run')).toBeVisible()
  await expect(history.getByText('stale-problem')).toHaveCount(0)
})

test('已知不可用和请求中故障的服务均有提示，仍可切回正常服务', async ({ page }) => {
  await signIn(page)
  await services(page)
  const queried: string[] = []
  await page.route('**/platform/ops/services/*/tasks', async (route) => {
    const selected = new URL(route.request().url()).pathname.split('/')[4]!
    queried.push(selected)
    if (selected === 'shipping')
      return fail(route, 503, 'ops.serviceUnavailable', '服务暂不可用，请稍后重试')
    return route.fulfill(ok(selected === 'billing' ? [] : definitions))
  })
  await page.goto('/task-runs')
  await expect(page.getByRole('row').filter({ hasText: 'health-check' })).toBeVisible()
  await choose(page, '服务', 'billing（不可用）')
  await expect(page.getByRole('alert').filter({ hasText: '服务 billing 当前不可用' })).toBeVisible()
  await choose(page, '服务', 'shipping')
  await expect(
    page.getByRole('alert').filter({ hasText: '服务暂不可用，请稍后重试' }),
  ).toBeVisible()
  await choose(page, '服务', 'orders')
  await expect(page.getByRole('row').filter({ hasText: 'health-check' })).toBeVisible()
  expect(queried).toEqual(['orders', 'shipping', 'orders'])
})

test('read 控制入口和直接路由，read-only 可查看失败租户但不能手动触发或重跑', async ({ page }) => {
  await signIn(page, ['platform:ops:read'])
  await services(page)
  await page.route('**/platform/ops/services/orders/tasks**', (route) =>
    route.fulfill(
      ok(new URL(route.request().url()).pathname.endsWith('/runs') ? [run] : definitions),
    ),
  )
  await page.goto('/task-runs')
  await expect(
    page.locator('.shell-menu').getByRole('link', { name: '任务运行', exact: true }),
  ).toBeVisible()
  await expect(page.getByRole('button', { name: '手动触发', exact: true })).toHaveCount(0)
  await page
    .getByRole('row')
    .filter({ hasText: '租户 清理' })
    .getByRole('button', { name: '运行历史' })
    .click()
  await page
    .getByRole('dialog', { name: '运行历史：租户 清理' })
    .getByRole('button', { name: '详情', exact: true })
    .click()
  const detail = page.getByRole('dialog', { name: '任务运行详情' })
  await expect(detail).toContainText(failedTenant)
  await expect(detail.getByRole('button', { name: '单独重跑', exact: true })).toHaveCount(0)
  await page.route('**/iam/platform/access', (route) =>
    route.fulfill(ok({ roles: [], permissions: ['platform:ops:manage'] })),
  )
  await page.goto('/account/security')
  await expect(
    page.locator('.shell-menu').getByRole('link', { name: '任务运行', exact: true }),
  ).toHaveCount(0)
  await page.goto('/task-runs')
  await expect(page).toHaveURL('http://127.0.0.1:5173/403')
  await expect(page.getByTestId('status-403')).toBeVisible()
})
