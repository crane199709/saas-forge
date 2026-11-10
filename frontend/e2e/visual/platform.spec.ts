import { expect, test, type Page, type Route } from '@playwright/test'
import { ok, onePage, viewports } from './support'

test.use({ baseURL: 'http://127.0.0.1:5183' })

const permissions = [
  'platform:announcement:manage',
  'platform:tenant:read',
  'platform:tenant:manage',
  'platform:plan:manage',
  'platform:sub-app:manage',
  'platform:operator:manage',
  'platform:role:manage',
  'platform:audit:read',
  'platform:ops:read',
  'platform:ops:manage',
  'platform:identity-mfa:reset',
]

// 数据覆盖最长值、空值和各种状态，截图才能暴露布局问题。
const tenant = (
  id: string,
  name: string,
  status: string,
  adminContact: string,
  region: string | null,
  planId: string | null,
) => ({
  id,
  name,
  status,
  adminContact,
  region,
  planId,
  expiresAt: null,
  purgeAt: null,
  purgedAt: null,
  createdAt: '2026-10-28T00:00:00Z',
})
const tenants = [
  tenant('t1', '示例科技有限公司', 'active', 'owner@example.com', 'east', 'pro'),
  tenant(
    't2',
    '华东大区供应链协同服务股份有限公司',
    'trial',
    'supply.chain.administrator@example-enterprise.com',
    null,
    'basic',
  ),
  tenant('t3', '已停用客户', 'suspended', '13800000000', 'west', null),
]
const detail = {
  tenant: tenants[0],
  allowedTransitions: ['suspended', 'deleted'],
  statusChanges: [
    {
      fromStatus: null,
      toStatus: 'active',
      reason: '开通',
      changedAt: '2026-10-28T00:00:00Z',
    },
  ],
  initializations: [
    { service: 'forge-iam', status: 'succeeded', error: null },
    { service: 'demo-service', status: 'failed', error: 'ForgeException: tenant.initFailed' },
  ],
}

const catalog = [
  ['order:read', '查看订单', '订单'],
  ['order:approve', '审批订单', '订单'],
  ['order:export', '导出订单', '订单'],
  ['report:view', '查看报表', '报表'],
  ['member:invite', '邀请成员', '成员'],
].map(([code, name, group]) => ({ code, name, service: 'demo-service', group }))
const plan = (
  id: string,
  name: string,
  granted: string[],
  subApps: string[],
  memberQuota: number | null,
  storageQuotaMb: number | null,
) => ({
  id,
  code: id,
  name,
  permissions: granted,
  subApps,
  memberQuota,
  storageQuotaMb,
  updatedAt: '2026-10-28T00:00:00Z',
})
const plans = [
  plan('pro', '专业版', ['order:read', 'order:approve'], ['demo', 'crm', 'wms'], 200, 102400),
  plan('basic', '基础版', ['order:read'], [], 5, 1024),
  plan('enterprise', '旗舰企业定制版', [], ['demo'], null, null),
]
const subApps = [
  {
    id: 's1',
    code: 'demo',
    name: '示例子应用',
    version: '1.2.0',
    remoteEntries: {
      production: 'https://cdn.example.com/sub-apps/demo/1.2.0/remoteEntry.js',
      staging: 'https://staging.example.com/remoteEntry.js',
    },
    sharedRequirements: { vue: '3.5.0', 'element-plus': '2.14.0' },
    updatedAt: '2026-10-28T00:00:00Z',
  },
]
const roles = [
  {
    id: 'r1',
    code: 'super-admin',
    name: '超级管理员',
    builtin: true,
    operatorCount: 2,
    permissions: [],
    dataPolicies: [],
  },
  {
    id: 'r2',
    code: 'east-operations',
    name: '华东区域运营',
    builtin: false,
    operatorCount: 12,
    permissions: ['platform:tenant:read', 'platform:plan:manage'],
    dataPolicies: [{ resource: 'tenant', scope: 'all' }],
  },
]
const resources = [
  {
    code: 'tenant',
    name: '租户',
    service: 'forge-platform',
    scopes: ['all'],
    attributes: [{ name: 'resource.region', label: '区域', type: 'string', multiValued: false }],
  },
]
const rolePermissions = [
  ['platform:tenant:read', '查看租户', '租户'],
  ['platform:tenant:manage', '管理租户', '租户'],
  ['platform:plan:manage', '管理套餐', '套餐'],
].map(([code, name, group]) => ({ code, name, service: 'forge-platform', group }))
const operators = [
  {
    id: 'o1',
    account: 'zhangwei',
    displayName: '张伟',
    region: 'east',
    status: 'active',
    roleIds: ['r1', 'r2'],
    createdAt: '2026-10-28T00:00:00Z',
  },
  {
    id: 'o2',
    account: 'contractor.operations.long',
    displayName: '外包运营人员',
    region: null,
    status: 'disabled',
    roleIds: [],
    createdAt: '2026-10-28T00:00:00Z',
  },
]
const events = [
  {
    id: 'a',
    occurredAt: '2026-11-08T09:30:00Z',
    category: 'operation',
    action: 'platform.tenant.create',
    actorId: 'u',
    actorAccount: 'zhangwei',
    actorName: '张伟',
    resourceType: 'tenant',
    resourceId: 't1',
    outcome: 'success',
    errorCode: null,
    detail: {},
    service: 'forge-platform',
    traceId: 't',
  },
  {
    id: 'b',
    occurredAt: '2026-11-08T09:40:00Z',
    category: 'login',
    action: 'auth.login',
    actorId: null,
    actorAccount: null,
    actorName: null,
    resourceType: null,
    resourceId: null,
    outcome: 'failure',
    errorCode: 'auth.invalidCredentials',
    detail: {},
    service: 'forge-iam',
    traceId: 't',
  },
]
const services = [
  { service: 'demo-service', available: true },
  { service: 'crm-service', available: false },
]
const deadEvents = ['PENDING', 'REPLAYED', 'ABANDONED'].map((status, index) => ({
  id: `d${index}`,
  consumerGroup: 'orders-group',
  eventId: `e${index}`,
  tenantId: index === 2 ? null : '018d0000-0000-7000-8000-000000000010',
  topic: 'order-created',
  tag: 'created',
  deliveryAttempt: 16,
  lastError: '库存服务超时，消费事务已回滚，下游依赖在重试窗口内持续不可用',
  status,
  createdAt: '2026-11-07T03:00:00Z',
  resolvedAt: null,
  resolutionReason: null,
}))
const taskRun = {
  id: '018d0000-0000-7000-8000-000000000001',
  name: '租户清理',
  type: 'TENANT',
  trigger: 'SCHEDULED',
  operatorId: null,
  targetTenantId: null,
  status: 'PARTIALLY_FAILED',
  startedAt: '2026-11-07T03:00:00Z',
  finishedAt: '2026-11-07T03:02:00Z',
  successCount: 12,
  failureCount: 3,
  failedTenants: [],
  errorSummary: null,
}
const tasks = [
  {
    name: '租户清理',
    type: 'TENANT',
    cron: '0 0 3 * * *',
    fixedDelay: null,
    zone: 'Asia/Shanghai',
    latestRun: taskRun,
  },
  {
    name: 'health-check',
    type: 'GLOBAL',
    cron: null,
    fixedDelay: 'PT1H',
    zone: null,
    latestRun: null,
  },
]
const announcements = [
  {
    id: 'n1',
    title: '系统维护通知',
    body: '本周六凌晨 2 点至 4 点进行数据库升级，期间服务可能短暂不可用。',
    subApp: null,
    route: null,
    expiresAt: '2026-11-15T00:00:00Z',
    publishedAt: '2026-11-08T09:00:00Z',
    revoked: false,
    allTenants: true,
    tenantIds: [],
  },
  {
    id: 'n2',
    title: '已撤回的旧公告',
    body: '这条公告已经撤回。',
    subApp: null,
    route: null,
    expiresAt: null,
    publishedAt: '2026-11-01T09:00:00Z',
    revoked: true,
    allTenants: false,
    tenantIds: ['t1', 't2'],
  },
]

function respond(path: string, route: Route) {
  const table: Record<string, unknown> = {
    '/platform/plans': ok(plans),
    '/platform/plans/permissions': ok(catalog),
    '/platform/sub-apps': ok(subApps),
    '/platform/tenants': onePage(tenants),
    '/platform/tenants/t1': ok(detail),
    '/platform/ops/services': ok(services),
    '/platform/ops/services/demo-service/dead-events': onePage(deadEvents),
    '/platform/ops/services/demo-service/tasks': ok(tasks),
    [`/platform/ops/services/demo-service/tasks/${encodeURIComponent('租户清理')}/runs`]: ok([
      taskRun,
    ]),
    '/iam/platform/access': ok({ roles: [], permissions }),
    '/iam/platform/operators': ok(operators),
    '/iam/platform/roles': ok(roles),
    '/iam/platform/roles/permissions': ok(rolePermissions),
    '/iam/platform/roles/data-resources': ok(resources),
    '/iam/platform/audit/events': onePage(events),
    '/iam/platform/announcements': ok(announcements),
    '/iam/platform/identities/lookup': ok({
      identityId: 'i1',
      displayName: '李娜',
      mfaBound: true,
    }),
  }
  return route.fulfill((table[path] ?? ok([])) as Parameters<Route['fulfill']>[0])
}

async function open(page: Page, url: string) {
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.platform.session',
      JSON.stringify({
        accessToken: 'operator-access',
        refreshToken: 'operator-refresh',
        operatorName: '值班运营',
      }),
    ),
  )
  await page.route('**/platform/**', (route) =>
    respond(new URL(route.request().url()).pathname, route),
  )
  await page.goto(url)
}

const pages = [
  ['tenants', '/tenants', () => '示例科技有限公司'],
  ['plans', '/plans', () => '专业版'],
  ['sub-apps', '/sub-apps', () => '示例子应用'],
  ['operators', '/operators', () => '外包运营人员'],
  ['roles', '/roles', () => '华东区域运营'],
  ['audit', '/audit', () => 'platform.tenant.create'],
  ['task-runs', '/task-runs', () => 'health-check'],
  ['dead-events', '/dead-events', () => 'order-created'],
  ['announcements', '/announcements', () => '这条公告已经撤回。'],
] as const

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    for (const [name, url, anchor] of pages) {
      test(name, async ({ page }) => {
        await open(page, url)
        await expect(page.getByText(anchor(), { exact: true }).first()).toBeVisible()
        await expect(page).toHaveScreenshot(`platform-${name}-${label}.png`)
      })
    }

    test('identity', async ({ page }) => {
      await open(page, '/identities')
      await page.locator('input[name="identifier"]').fill('lina@example.com')
      await page.getByTestId('identity-lookup').getByRole('button').click()
      await expect(page.getByTestId('identity-mfa-reset')).toBeVisible()
      await expect(page).toHaveScreenshot(`platform-identity-${label}.png`)
    })

    test('account', async ({ page }) => {
      await open(page, '/account/security')
      await expect(page.getByTestId('password-form')).toBeVisible()
      await expect(page).toHaveScreenshot(`platform-account-${label}.png`)
    })
  })
}

test.describe('drawers', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('tenant detail', async ({ page }) => {
    await open(page, '/tenants')
    await page.getByRole('button', { name: '详情', exact: true }).first().click()
    await expect(page.getByRole('button', { name: '更换套餐', exact: true })).toBeVisible()
    await expect(page).toHaveScreenshot('platform-tenant-detail.png')
  })

  test('plan edit', async ({ page }) => {
    await open(page, '/plans')
    await page.getByTestId('plan-edit-pro').click()
    await expect(page.getByTestId('plan-perm-order:read')).toBeVisible()
    await expect(page).toHaveScreenshot('platform-plan-edit.png')
  })

  test('role edit', async ({ page }) => {
    await open(page, '/roles')
    await page.getByTestId('platform-role-edit-east-operations').click()
    await expect(page.getByTestId('platform-permission-platform:tenant:read')).toBeVisible()
    await expect(page).toHaveScreenshot('platform-role-edit.png')
  })

  test('task run history', async ({ page }) => {
    await open(page, '/task-runs')
    await page
      .getByRole('row')
      .filter({ hasText: '租户清理' })
      .getByRole('button', { name: '运行历史' })
      .click()
    await expect(page.getByTestId('task-run-retention')).toBeVisible()
    await expect(page).toHaveScreenshot('platform-task-run-history.png')
  })

  test('sub-app edit', async ({ page }) => {
    await open(page, '/sub-apps')
    await page.getByTestId('sub-app-edit-demo').click()
    await expect(page.getByTestId('sub-app-env-0')).toBeVisible()
    await expect(page).toHaveScreenshot('platform-sub-app-edit.png')
  })
})
