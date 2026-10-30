import { expect, test, type BrowserContext, type Page, type Route } from '@playwright/test'

// 同一浏览器上下文中的多个页面共享 localStorage，模拟同一浏览器的多个标签页。
// 后端以内存状态模拟 IAM：刷新凭证一次性使用，重放已轮换的凭证会撤销整个授权族。

const memberships = [
  { membershipId: 'membership-a', tenantId: 'tenant-a', tenantName: '租户 A' },
  { membershipId: 'membership-b', tenantId: 'tenant-b', tenantName: '租户 B' },
]

const demoMenu = {
  code: 'demo',
  name: '示例应用',
  children: [
    {
      code: 'demo.overview',
      name: '示例子应用',
      app: 'demo',
      path: 'overview',
      permission: 'order:read',
    },
  ],
}

const demoApp = {
  code: 'demo',
  name: '示例',
  version: '1.0.0',
  remoteEntry: 'http://127.0.0.1:5175/remoteEntry.js',
  sharedRequirements: { vue: '3.5.0' },
}

function ok(data: unknown, meta?: object) {
  return { json: { code: 'ok', httpStatus: 200, data, meta, traceId: 'trace-ok' } }
}

function unauthorized(code = 'auth.unauthenticated') {
  return { status: 401, json: { code, httpStatus: 401, errorMessage: code, traceId: 'trace-401' } }
}

interface Backend {
  /** 当前有效的 Access Token；角色变更使其失效后为 null，直到刷新换发。 */
  access: string | null
  refresh: string | null
  membershipId: string
  /** 角色变更后 A 租户多出新建订单权限。 */
  roleChanged: boolean
  revoked: boolean
  refreshCalls: number
  /** 角色变更后到达、尚未答复的业务请求；凑齐两个标签页的请求后同时返回 401。 */
  heldOrders: Route[]
}

async function mockBackend(context: BrowserContext): Promise<Backend> {
  const backend: Backend = {
    access: null,
    refresh: null,
    membershipId: 'membership-a',
    roleChanged: false,
    revoked: false,
    refreshCalls: 0,
    heldOrders: [],
  }
  let issued = 0
  const used = new Set<string>()

  function issue(membershipId: string) {
    issued += 1
    if (backend.refresh) used.add(backend.refresh)
    backend.access = `access-${issued}`
    backend.refresh = `refresh-${issued}`
    backend.membershipId = membershipId
    const membership = memberships.find((item) => item.membershipId === membershipId)!
    return ok({
      accessToken: backend.access,
      refreshToken: backend.refresh,
      tokenType: 'Bearer',
      expiresIn: 900,
      identityName: '多成员身份',
      ...membership,
    })
  }

  function authorized(route: Route) {
    const token = route.request().headers().authorization
    return !backend.revoked && backend.access !== null && token === `Bearer ${backend.access}`
  }

  function tenant() {
    return memberships.find((item) => item.membershipId === backend.membershipId)!
  }

  await context.route('**/iam/auth/login', (route) =>
    route.fulfill(
      ok({ clientId: 'tenant-app', redirectUri: 'http://127.0.0.1:5174/', memberships }),
    ),
  )
  await context.route('**/iam/oauth2/authorize?*', async (route) => {
    const state = new URL(route.request().url()).searchParams.get('state')
    await route.fulfill({ status: 302, headers: { Location: '/?code=c&state=' + state } })
  })
  await context.route('**/iam/auth/exchange', (route) =>
    route.fulfill(issue(route.request().postDataJSON().membershipId)),
  )
  await context.route('**/iam/auth/refresh', async (route) => {
    backend.refreshCalls += 1
    const { refreshToken } = route.request().postDataJSON()
    if (used.has(refreshToken)) backend.revoked = true
    if (backend.revoked || refreshToken !== backend.refresh) {
      await route.fulfill(unauthorized('auth.failed'))
      return
    }
    // 放慢刷新，让另一个标签页在轮换完成前也遇到 401 并发起刷新。
    await new Promise((resolve) => setTimeout(resolve, 300))
    await route.fulfill(issue(backend.membershipId))
  })
  await context.route('**/iam/auth/switch', async (route) => {
    const { refreshToken, membershipId } = route.request().postDataJSON()
    if (backend.revoked || refreshToken !== backend.refresh) {
      await route.fulfill(unauthorized('auth.failed'))
      return
    }
    await route.fulfill(issue(membershipId))
  })
  await context.route('**/iam/auth/logout', (route) => route.fulfill(ok(null)))
  await context.route('**/iam/auth/memberships', (route) =>
    route.fulfill(authorized(route) ? ok(memberships) : unauthorized()),
  )
  await context.route('**/iam/auth/access', (route) => {
    if (!authorized(route)) return route.fulfill(unauthorized())
    const inA = backend.membershipId === 'membership-a'
    const permissions = inA
      ? ['order:read', 'iam:member:manage', ...(backend.roleChanged ? ['order:create'] : [])]
      : ['order:read']
    return route.fulfill(ok({ permissions, menus: [demoMenu], subApps: [demoApp] }))
  })
  await context.route('**/demo/context', (route) =>
    route.fulfill(
      authorized(route)
        ? ok({
            identityId: 'identity-1',
            tenantId: tenant().tenantId,
            membershipId: backend.membershipId,
            version: 1,
          })
        : unauthorized(),
    ),
  )
  await context.route('**/demo/orders?*', async (route) => {
    if (!authorized(route)) {
      if (backend.access === null) {
        backend.heldOrders.push(route)
        if (backend.heldOrders.length < 2) return
        const held = backend.heldOrders.splice(0)
        await Promise.all(held.map((item) => item.fulfill(unauthorized())))
        return
      }
      await route.fulfill(unauthorized())
      return
    }
    const title = `${tenant().tenantName} 订单`
    await route.fulfill(
      ok([{ id: '1', title, amount: 1 }], {
        page: { number: 1, size: 20, totalElements: 1, totalPages: 1 },
      }),
    )
  })
  return backend
}

async function login(page: Page) {
  await page.goto('/')
  await page.getByLabel('账号或邮箱').fill('multi')
  await page.getByLabel('密码', { exact: true }).fill('correct-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('button', { name: '租户 A' }).click()
  await expect(page.getByTestId('tenant-switcher')).toBeVisible()
}

async function openOrders(page: Page) {
  await page.getByTestId('menu-demo.overview').click()
  await expect(page.getByTestId('order-title')).toBeVisible()
}

test('A 登录后 B 刷新即已登录，关闭全部页面再打开仍已登录', async ({ context, page: a }) => {
  await mockBackend(context)
  const b = await context.newPage()
  await b.goto('/')
  await expect(b.getByLabel('账号或邮箱')).toBeVisible()
  await login(a)
  // A 登录后 B 自行整页回首页；等它完成再刷新，避免两次导航相互打断
  await expect(b.getByTestId('tenant-switcher')).toBeVisible()
  await expect(b.getByText('当前租户：租户 A')).toBeVisible()
  await b.reload()
  await expect(b.getByTestId('tenant-switcher')).toBeVisible()
  await expect(b.getByText('当前租户：租户 A')).toBeVisible()

  await a.close()
  await b.close()
  const reopened = await context.newPage()
  await reopened.goto('/')
  await expect(reopened.getByTestId('tenant-switcher')).toBeVisible()
  await expect(reopened.getByText('当前租户：租户 A')).toBeVisible()
})

test('A 切换租户，B 的菜单与 Sub-App 数据随之切换', async ({ context, page: a }) => {
  await mockBackend(context)
  await login(a)
  const b = await context.newPage()
  await b.goto('/')
  await openOrders(b)
  await expect(b.getByTestId('order-title')).toHaveText(['租户 A 订单'])
  await expect(b.getByTestId('menu-members')).toBeVisible()

  await a.getByTestId('tenant-switcher').click()
  await a.getByRole('option', { name: '租户 B' }).click()
  await expect(a.getByText('当前租户：租户 B')).toBeVisible()

  // B 卸载旧租户的 Sub-App 回到首页，菜单按新租户的权限显示。
  await expect(b.getByTestId('order-title')).toHaveCount(0)
  await expect(b.getByText('当前租户：租户 B')).toBeVisible()
  await expect(b.getByTestId('menu-members')).toBeHidden()
  await openOrders(b)
  await expect(b.getByTestId('remote-tenant')).toHaveText('当前租户：租户 B')
  await expect(b.getByTestId('order-title')).toHaveText(['租户 B 订单'])
})

test('A 退出，B 回到登录', async ({ context, page: a }) => {
  await mockBackend(context)
  await login(a)
  const b = await context.newPage()
  await b.goto('/')
  await openOrders(b)

  await a.getByTestId('user-menu').click()
  await a.getByTestId('user-logout').click()
  await expect(a.getByLabel('账号或邮箱')).toBeVisible()
  await expect(b.getByLabel('账号或邮箱')).toBeVisible()
  await expect(b.getByTestId('order-title')).toHaveCount(0)
  await expect(b.getByTestId('tenant-switcher')).toBeHidden()
})

test('角色变更后 A、B 同时发起请求，只刷新一次且都按新权限继续工作', async ({
  context,
  page: a,
}) => {
  const backend = await mockBackend(context)
  await login(a)
  await openOrders(a)
  const b = await context.newPage()
  await b.goto('/')
  await openOrders(b)
  await expect(a.getByTestId('create-order')).toBeHidden()
  await expect(b.getByTestId('create-order')).toBeHidden()

  // 管理员修改该成员角色：旧 Access Token 立即失效，刷新换发的新 Token 带上新权限。
  backend.roleChanged = true
  backend.access = null
  await Promise.all([
    a.getByRole('button', { name: '刷新订单', exact: true }).click(),
    b.getByRole('button', { name: '刷新订单', exact: true }).click(),
  ])

  for (const page of [a, b]) {
    await expect(page.getByTestId('order-title')).toHaveText(['租户 A 订单'])
    await expect(page.getByTestId('create-order')).toBeVisible()
    await expect(page.getByTestId('tenant-switcher')).toBeVisible()
  }
  expect(backend.refreshCalls).toBe(1)
  expect(backend.revoked).toBe(false)
})
