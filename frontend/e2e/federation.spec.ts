import { expect, test, type Page } from '@playwright/test'

const browserErrors = new WeakMap<Page, string[]>()
// 用例有意制造的加载失败会在控制台留下错误，只放行与之匹配的消息
const expectedErrors = new WeakMap<Page, RegExp[]>()

const V1 = 'http://127.0.0.1:5175/remoteEntry.js'
const V2 = 'http://127.0.0.1:5176/remoteEntry.js'

function demoApp(remoteEntry = V1, sharedRequirements: Record<string, string> = { vue: '3.5.0' }) {
  return { code: 'demo', name: '示例', version: '1.0.0', remoteEntry, sharedRequirements }
}

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

function ok(data: unknown) {
  return { json: { code: 'ok', httpStatus: 200, data, traceId: 'trace-ok' } }
}

// Sub-App 路由按后端下发的菜单与可加载的 Sub-App 注册，未登录时不存在；这里模拟已进入租户的会话，
// 以及 IAM 按套餐与 App Registry 副本下发的菜单和入口地址。
async function signIn(
  page: Page,
  permissions = ['order:read'],
  subApps: object[] = [demoApp()],
  menus: object[] = [demoMenu],
) {
  // 只在没有会话时写入：切换租户后重载，Shell 保存的新会话不应被改回
  await page.addInitScript(() => {
    if (sessionStorage.getItem('forge.tenant.session')) return
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
        tokenType: 'Bearer',
        expiresIn: 900,
        identityName: '演示身份',
        tenantId: 'tenant-1',
        tenantName: '演示租户',
        membershipId: 'membership-1',
      }),
    )
  })
  await page.route('**/iam/auth/access', (route) =>
    route.fulfill(ok({ permissions, menus, subApps })),
  )
  await page.route('**/iam/auth/memberships', (route) =>
    route.fulfill(
      ok([{ membershipId: 'membership-1', tenantId: 'tenant-1', tenantName: '演示租户' }]),
    ),
  )
  await page.route('**/demo/context', (route) =>
    route.fulfill(
      ok({
        identityId: 'identity-1',
        tenantId: 'tenant-1',
        membershipId: 'membership-1',
        version: 1,
      }),
    ),
  )
}

test.beforeEach(async ({ page }) => {
  const errors: string[] = []
  browserErrors.set(page, errors)
  await signIn(page)
  await page.route('**/demo/orders?*', (route) =>
    route.fulfill({
      json: {
        code: 'ok',
        httpStatus: 200,
        data: [],
        traceId: 'trace-orders',
        meta: { page: { number: 1, size: 20, totalElements: 0, totalPages: 0 } },
      },
    }),
  )
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
})

test.afterEach(async ({ page }) => {
  const allowed = expectedErrors.get(page) ?? []
  expect(
    browserErrors.get(page)!.filter((error) => !allowed.some((pattern) => pattern.test(error))),
  ).toEqual([])
})

async function resignIn(
  page: Page,
  ...args: Parameters<typeof signIn> extends [Page, ...infer R] ? R : never
) {
  await page.unroute('**/iam/auth/access')
  await signIn(page, ...args)
}

test('修改注册表中的入口地址后刷新即加载新版本，Shell 无需重新发布', async ({ page }) => {
  await page.goto('/apps/demo/overview')
  await expect(page.getByRole('heading', { name: '示例子应用 v1' })).toBeVisible()
  // Sub-App 经共享的 @forge/sdk 取得 Shell 的会话与租户上下文
  await expect(page.getByTestId('remote-tenant')).toHaveText('当前租户：演示租户')

  // Operator 在 App Registry 中把地址改为 v2，IAM 的副本随之下发新地址
  await resignIn(page, ['order:read'], [demoApp(V2)])
  const remoteResponse = page.waitForResponse(V2)
  await page.reload()
  expect((await remoteResponse).status()).toBe(200)
  await expect(page.getByRole('heading', { name: '示例子应用 v2' })).toBeVisible()
  await page.screenshot({ path: '/tmp/forge-21-v2.png' })
})

test('不在套餐中的 Sub-App 不被加载', async ({ page }) => {
  const requested: string[] = []
  page.on('request', (request) => {
    if (request.url().endsWith('/remoteEntry.js')) requested.push(request.url())
  })
  // IAM 已按套餐裁掉指向 demo 的菜单，可加载列表中也没有它
  await resignIn(page, ['order:read'], [], [])
  await page.goto('/apps/demo/overview')
  await expect(page.getByRole('heading', { name: '欢迎使用 SaaS Forge 租户端' })).toBeVisible()
  await expect(page.getByRole('link', { name: '示例子应用', exact: true })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '概览', exact: true })).toHaveCount(0)
  // 菜单仍可见但当前环境没有可加载的入口时，同样不请求、不展示
  await resignIn(page, ['order:read'], [])
  await page.reload()
  await expect(page.getByRole('heading', { name: '欢迎使用 SaaS Forge 租户端' })).toBeVisible()
  await expect(page.getByRole('link', { name: '示例子应用', exact: true })).toHaveCount(0)
  expect(requested).toEqual([])
})

test('单个 Sub-App 加载失败只在其区域提示错误，Shell 导航与其他 Sub-App 照常可用', async ({
  page,
}) => {
  expectedErrors.set(page, [/ERR_CONNECTION_REFUSED|Failed to load Sub-App broken|Failed to fetch/])
  await resignIn(
    page,
    ['order:read'],
    [demoApp(), { ...demoApp('http://127.0.0.1:5199/remoteEntry.js'), code: 'broken' }],
    [
      demoMenu,
      { code: 'broken', name: '故障应用', app: 'broken', path: 'home', permission: 'order:read' },
    ],
  )
  await page.goto('/apps/broken/home')
  await expect(page.getByTestId('sub-app-error-broken')).toContainText('子应用 broken 加载失败')
  await expect(page.getByRole('heading', { name: '欢迎使用 SaaS Forge 租户端' })).toBeVisible()

  await page.getByRole('link', { name: '示例子应用', exact: true }).click()
  await expect(page.getByRole('heading', { name: '概览', exact: true })).toBeVisible()
  await expect(page.getByTestId('sub-app-error-broken')).toHaveCount(0)
})

test('Shell 的共享依赖低于 Sub-App 声明的最低版本时拒绝加载并提示', async ({ page }) => {
  expectedErrors.set(page, [/Failed to load Sub-App demo/])
  const requested: string[] = []
  page.on('request', (request) => {
    if (request.url().endsWith('/remoteEntry.js')) requested.push(request.url())
  })
  await resignIn(page, ['order:read'], [demoApp(V1, { vue: '9.0.0' })])
  await page.goto('/apps/demo/overview')
  await expect(page.getByTestId('sub-app-error-demo')).toContainText(
    '需要更高版本的共享依赖（vue 3.5.44 < 9.0.0）',
  )
  expect(requested).toEqual([])
})

test('切换租户时卸载 Sub-App 并重载，Sub-App 读到新租户的上下文', async ({ page }) => {
  const memberships = [
    { membershipId: 'membership-1', tenantId: 'tenant-1', tenantName: '演示租户' },
    { membershipId: 'membership-2', tenantId: 'tenant-2', tenantName: '另一租户' },
  ]
  await page.unroute('**/iam/auth/memberships')
  await page.route('**/iam/auth/memberships', (route) => route.fulfill(ok(memberships)))
  await page.route('**/iam/auth/switch', (route) =>
    route.fulfill(
      ok({
        accessToken: 'access-2',
        refreshToken: 'refresh-2',
        tokenType: 'Bearer',
        expiresIn: 900,
        identityName: '演示身份',
        ...memberships[1],
      }),
    ),
  )
  await page.goto('/apps/demo/overview')
  await expect(page.getByTestId('remote-tenant')).toHaveText('当前租户：演示租户')

  await page.getByTestId('tenant-switcher').click()
  await page.getByRole('option', { name: '另一租户' }).click()
  await expect(page).toHaveURL('http://127.0.0.1:5174/')
  await expect(page.getByRole('heading', { name: '概览', exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: '示例子应用', exact: true }).click()
  await expect(page.getByTestId('remote-tenant')).toHaveText('当前租户：另一租户')
})

for (const viewport of [
  { width: 1280, height: 900 },
  { width: 390, height: 844 },
]) {
  test(`Sub-App 弹层位于视口内且使用共享样式（${viewport.width}px）`, async ({ page }) => {
    await page.setViewportSize(viewport)
    await page.goto('/apps/demo/overview')
    await page.getByRole('button', { name: '打开弹窗' }).click()
    const dialog = page.getByRole('dialog', { name: '子应用弹窗' })
    await expect(dialog).toBeVisible()
    const panel = dialog.locator('.el-dialog')
    await expect(panel).toHaveCSS('background-color', 'rgb(255, 255, 255)')
    await expect(panel).toHaveCSS('position', 'relative')
    const box = await panel.boundingBox()
    expect(box).not.toBeNull()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.y).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(viewport.width)
    expect(box!.y + box!.height).toBeLessThanOrEqual(viewport.height)
    const select = page.getByRole('combobox', { name: '选择颜色' })
    await dialog.getByText('选择颜色', { exact: true }).click()
    const option = page.getByRole('option', { name: '蓝色' })
    await expect(option).toBeVisible()
    const optionBox = await option.boundingBox()
    const selectBox = await select.boundingBox()
    expect(optionBox!.y).toBeGreaterThan(selectBox!.y)
    expect(optionBox!.y - selectBox!.y).toBeLessThan(100)
    await option.click()
    await expect(dialog.getByText('已选择：蓝色')).toBeVisible()
    await dialog.getByRole('button', { name: '提示说明' }).hover()
    const tooltip = page.getByRole('tooltip', { name: '共享样式的提示' })
    await expect(tooltip).toBeVisible()
    await expect(tooltip).toHaveCSS('opacity', '1')
    await expect(tooltip).toHaveCSS('background-color', 'rgb(48, 49, 51)')
    const tooltipBox = await tooltip.boundingBox()
    expect(tooltipBox!.y).toBeGreaterThanOrEqual(0)
    expect(tooltipBox!.x + tooltipBox!.width).toBeLessThanOrEqual(viewport.width)
    await page.screenshot({ path: `/tmp/forge-02-overlays-${viewport.width}.png` })
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(dialog).toBeHidden()
  })
}

for (const host of ['127.0.0.1', 'localhost']) {
  test(`Shell 从 ${host} 加载 Sub-App，共享依赖具有相同的运行时引用`, async ({ page }) => {
    await page.goto(`http://${host}:5174/apps/demo/overview`)
    await expect(page.getByRole('heading', { name: '示例子应用 v1' })).toBeVisible()
    await expect(page).toHaveTitle('SaaS Forge 租户端')
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    for (const dependency of [
      'vue',
      'vue-router',
      'pinia',
      'element-plus',
      '@forge/ui',
      '@forge/sdk',
      'vue-i18n',
    ]) {
      await expect(page.getByTestId(`shared-demo-${dependency}`)).toHaveText(
        `${dependency}: singleton`,
      )
    }
  })
}

test('主题 token 同步，Sub-App scoped CSS 不泄漏到 Shell', async ({ page }) => {
  await page.goto('/apps/demo/overview')
  const shell = page.getByTestId('shell-theme')
  const remote = page.getByTestId('remote-theme')
  await expect(shell).toHaveCSS('background-color', 'rgb(64, 158, 255)')
  await expect(remote).toHaveCSS('background-color', 'rgb(64, 158, 255)')
  await expect(page.getByTestId('shell-scope')).toHaveCSS('border-top-width', '0px')
  await expect(page.getByTestId('remote-scope')).toHaveCSS('border-top-width', '7px')
  await page.getByRole('button', { name: '切换主题' }).click()
  await page.getByRole('heading', { name: '欢迎使用 SaaS Forge 租户端' }).click()
  await expect(shell).toHaveCSS('background-color', 'rgb(139, 92, 246)')
  await expect(remote).toHaveCSS('background-color', 'rgb(139, 92, 246)')
  await expect(page.getByTestId('shell-scope')).toHaveCSS('border-top-width', '0px')
})

test('Sub-App 嵌套路由与 Shell 同步，支持前进、后退及深链接刷新', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('link', { name: '示例子应用', exact: true }).click()
  await expect(page.getByRole('heading', { name: '概览', exact: true })).toBeVisible()
  await page.getByRole('link', { name: '查看详情' }).click()
  await expect(page).toHaveURL(/\/apps\/demo\/details\/42$/)
  await expect(page.getByTestId('shell-route')).toHaveText('/apps/demo/details/42')
  await expect(page.getByRole('heading', { name: '详情 42' })).toBeVisible()
  await page.goBack()
  await expect(page.getByRole('heading', { name: '概览', exact: true })).toBeVisible()
  await page.goForward()
  await expect(page.getByRole('heading', { name: '详情 42' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: '详情 42' })).toBeVisible()
  await page.getByRole('link', { name: 'Shell 首页' }).click()
  await expect(page.getByRole('heading', { name: '示例子应用 v1' })).toHaveCount(0)
})

test('v-perm 按当前 Membership 的权限显示或隐藏 Sub-App 中的按钮', async ({ page }) => {
  await page.goto('/apps/demo/overview')
  await expect(page.getByRole('heading', { name: '概览', exact: true })).toBeVisible()
  await expect(page.getByTestId('create-order')).toBeHidden()

  await resignIn(page, ['order:read', 'order:create'])
  await page.reload()
  await expect(page.getByTestId('create-order')).toBeVisible()
})

test('菜单中没有的 Sub-App 不注册路由', async ({ page }) => {
  await resignIn(page, [], [demoApp()], [])
  await page.goto('/apps/demo/overview')
  await expect(page.getByRole('link', { name: '示例子应用', exact: true })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '概览', exact: true })).toHaveCount(0)
})
