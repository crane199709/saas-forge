import { expect, test, type Page, type Route } from '@playwright/test'

test.use({ baseURL: 'http://127.0.0.1:5173' })

const baseEvent = {
  consumerGroup: 'orders-group',
  tenantId: '018d0000-0000-7000-8000-000000000010',
  topic: 'order-created',
  tag: 'created',
  deliveryAttempt: 16,
  lastError: '库存服务超时，消费事务已回滚',
  status: 'PENDING',
  createdAt: '2026-11-07T03:00:00Z',
  resolvedAt: null as string | null,
  resolutionReason: null as string | null,
}

function ok(data: unknown) {
  return { json: { code: 'ok', httpStatus: 200, data, traceId: 'dead-ok' } }
}

async function fail(route: Route, status: number, code: string, userTip: string) {
  await route.fulfill({
    status,
    json: { code, httpStatus: status, userTip, traceId: `dead-${status}` },
  })
}

async function signIn(page: Page, permissions: string[]) {
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

test('Operator 查看、按服务和状态分页、重放及放弃，并可从服务故障恢复查看', async ({ page }) => {
  const runtimeErrors: string[] = []
  const consoleProblems: string[] = []
  page.on('pageerror', (error) => runtimeErrors.push(error.message))
  page.on('console', (message) => {
    if (!['error', 'warning'].includes(message.type())) return
    // 409 与 503 是本用例刻意模拟的业务拒绝和下游故障，Chrome 会记录 HTTP 资源错误。
    if (
      /Failed to load resource: the server responded with a status of (409|503)/.test(
        message.text(),
      )
    )
      return
    consoleProblems.push(message.text())
  })
  await signIn(page, ['platform:ops:read', 'platform:ops:manage'])
  await page.route('**/platform/ops/services', (route) =>
    route.fulfill(
      ok([
        { service: 'orders', available: true },
        { service: 'billing', available: false },
        { service: 'shipping', available: true },
      ]),
    ),
  )
  const records = [
    { ...baseEvent, id: '018d0000-0000-7000-8000-000000000001', eventId: 'order-replay' },
    {
      ...baseEvent,
      id: '018d0000-0000-7000-8000-000000000002',
      eventId: 'order-abandon',
      topic: 'order-cancelled',
      tenantId: null,
    },
    {
      ...baseEvent,
      id: '018d0000-0000-7000-8000-000000000003',
      eventId: 'page-two',
      topic: 'page-two-topic',
    },
  ]
  let replayAttempts = 0
  const commands: { action: string; reason: string }[] = []
  await page.route('**/platform/ops/services/*/dead-events**', async (route) => {
    const request = route.request()
    expect(request.headers().authorization).toBe('Bearer operator-access')
    const url = new URL(request.url())
    if (url.pathname.includes('/shipping/')) {
      await fail(route, 503, 'ops.serviceUnavailable', '服务暂不可用，请稍后重试')
      return
    }
    const suffix = url.pathname.split('/dead-events')[1]!
    if (suffix === '') {
      const number = Number(url.searchParams.get('page'))
      expect(url.searchParams.get('size')).toBe('20')
      const filtered = records.filter((item) => item.status === url.searchParams.get('status'))
      const items = number === 2 ? filtered.slice(2) : filtered.slice(0, 2)
      await route.fulfill({
        json: {
          ...ok(items).json,
          meta: {
            page: {
              number,
              size: 20,
              totalElements: url.searchParams.get('status') === 'PENDING' ? 21 : filtered.length,
              totalPages: url.searchParams.get('status') === 'PENDING' ? 2 : 1,
            },
          },
        },
      })
      return
    }
    const [, id, action] = suffix.split('/')
    const event = records.find((item) => item.id === id)!
    if (!action) {
      await route.fulfill(
        ok({ ...event, lastError: '最新错误：库存服务超时', payload: 'private-message-body' }),
      )
      return
    }
    expect(request.method()).toBe('POST')
    const { reason } = request.postDataJSON() as { reason: string }
    expect(reason.trim()).not.toBe('')
    if (action === 'replay' && replayAttempts++ === 0) {
      await fail(route, 409, 'ops.deadEventReplayFailed', '重放失败，请检查原服务后重试')
      return
    }
    commands.push({ action, reason })
    event.status = action === 'replay' ? 'REPLAYED' : 'ABANDONED'
    event.resolutionReason = reason
    event.resolvedAt = '2026-11-07T04:00:00Z'
    await route.fulfill(ok(event))
  })
  await page.goto('/dead-events')
  await expect(page).toHaveURL('http://127.0.0.1:5173/dead-events')
  await expect(page).toHaveTitle('SaaS Forge 平台控制台')
  await expect(page.getByRole('heading', { name: '死信事件', exact: true })).toBeVisible()
  await expect(page.getByTestId('list-page-total')).toContainText('21')
  const replayRow = page.getByRole('row').filter({ hasText: 'order-created' })
  await expect(replayRow).toContainText('16')
  await expect(replayRow).toContainText('2026-11-07')
  await page.screenshot({ animations: 'disabled', path: '/tmp/forge-mq-dead-events-desktop.png' })
  await replayRow.getByRole('button', { name: '查看', exact: true }).click()
  const detail = page.getByRole('dialog', { name: '死信事件详情' })
  await expect(detail).toContainText('orders-group')
  await expect(detail).toContainText('order-replay')
  await expect(detail).toContainText('最新错误：库存服务超时')
  await expect(page.getByText('private-message-body')).toHaveCount(0)
  await detail.getByRole('button', { name: '关闭', exact: true }).click()
  await page.locator('button.btn-next').click()
  await expect(page.getByRole('row').filter({ hasText: 'page-two-topic' })).toBeVisible()
  await choose(page, '状态', '已重放')
  await expect(page.getByText('暂无符合条件的死信事件')).toBeVisible()
  await choose(page, '状态', '待处置')
  await expect(replayRow).toBeVisible()

  await replayRow.getByRole('button', { name: '重放', exact: true }).click()
  const replay = page.getByRole('dialog', { name: '重放', exact: true })
  await expect(replay.getByRole('button', { name: '确认重放' })).toBeDisabled()
  await replay.getByLabel('处置原因').fill('   ')
  await expect(replay.getByRole('button', { name: '确认重放' })).toBeDisabled()
  await replay.getByLabel('处置原因').fill(' 已修复库存服务 ')
  await replay.getByRole('button', { name: '确认重放' }).click()
  await expect(replay.getByRole('alert')).toContainText('重放失败，请检查原服务后重试')
  await expect(replay.getByRole('alert')).toContainText('dead-409')
  await expect(replay.getByLabel('处置原因')).toHaveValue(' 已修复库存服务 ')
  await page.screenshot({
    animations: 'disabled',
    path: '/tmp/forge-mq-dead-events-replay-failure.png',
  })
  await replay.getByRole('button', { name: '确认重放' }).click()
  await expect(replay).not.toBeVisible()
  await expect(page.getByRole('alert')).toContainText('已重放该死信事件')
  await expect(replayRow).toHaveCount(0)
  await choose(page, '状态', '已重放')
  await expect(replayRow).toContainText('已重放')
  await expect(replayRow.getByRole('button', { name: '重放', exact: true })).toHaveCount(0)
  await choose(page, '状态', '待处置')

  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.locator('.list-page')).toHaveAttribute('aria-busy', 'false')
  await page.screenshot({
    animations: 'disabled',
    path: '/tmp/forge-mq-dead-events-mobile-list.png',
  })
  const abandonRow = page.getByRole('row').filter({ hasText: 'order-cancelled' })
  await abandonRow.getByRole('button', { name: '放弃', exact: true }).click()
  const abandon = page.getByRole('dialog', { name: '放弃', exact: true })
  await expect(abandon.getByRole('button', { name: '确认放弃' })).toBeDisabled()
  await abandon.getByLabel('处置原因').fill('该事件已人工补偿')
  await page.screenshot({ animations: 'disabled', path: '/tmp/forge-mq-dead-events-mobile.png' })
  await abandon.getByRole('button', { name: '确认放弃' }).click()
  await expect(page.getByRole('alert')).toContainText('已放弃该死信事件')
  await choose(page, '状态', '已放弃')
  await expect(abandonRow).toContainText('已放弃')
  await abandonRow.getByRole('button', { name: '查看', exact: true }).click()
  await expect(detail).toContainText('该事件已人工补偿')
  await expect(detail).toContainText('2026-11-07T04:00:00Z')
  await page.screenshot({
    animations: 'disabled',
    path: '/tmp/forge-mq-dead-events-mobile-detail.png',
  })
  await detail.getByRole('button', { name: '关闭', exact: true }).click()
  await choose(page, '服务', 'billing（不可用）')
  await expect(page.getByRole('alert').filter({ hasText: 'billing' })).toBeVisible()
  await choose(page, '服务', 'shipping')
  await expect(page.getByRole('alert').filter({ hasText: '服务暂不可用' })).toBeVisible()
  await choose(page, '服务', 'orders')
  await expect(abandonRow).toBeVisible()
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  expect(commands).toEqual([
    { action: 'replay', reason: '已修复库存服务' },
    { action: 'abandon', reason: '该事件已人工补偿' },
  ])
  expect(runtimeErrors).toEqual([])
  expect(consoleProblems).toEqual([])
})

test('read 决定入口和直接路由，manage 决定处置按钮', async ({ page }) => {
  await signIn(page, ['platform:ops:read'])
  await page.route('**/platform/ops/services', (route) =>
    route.fulfill(ok([{ service: 'orders', available: true }])),
  )
  await page.route('**/platform/ops/services/orders/dead-events?*', (route) =>
    route.fulfill({
      json: {
        ...ok([{ ...baseEvent, id: 'd1', eventId: 'read-only-event' }]).json,
        meta: { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } },
      },
    }),
  )
  await page.goto('/dead-events')
  await expect(page.locator('.shell-menu').getByRole('link', { name: '死信事件' })).toBeVisible()
  await expect(page.getByRole('button', { name: '查看', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '重放', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '放弃', exact: true })).toHaveCount(0)
  await page.route('**/iam/platform/access', (route) =>
    route.fulfill(ok({ roles: [], permissions: ['platform:ops:manage'] })),
  )
  await page.goto('/account/security')
  await expect(page.locator('.shell-menu').getByRole('link', { name: '死信事件' })).toHaveCount(0)
  await page.goto('/dead-events')
  await expect(page).toHaveURL('http://127.0.0.1:5173/403')
  await expect(page.getByTestId('status-403')).toBeVisible()
})
