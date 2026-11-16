import { expect, test, type Locator } from '@playwright/test'
import { openAsAdmin } from './visual/support'

// Browser plugin not available；通过真实登录页控件验证共享组件样式。
async function buttonColors(button: Locator) {
  return button.evaluate((element) => {
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = 1
    const context = canvas.getContext('2d', { willReadFrequently: true })!
    const style = getComputedStyle(element)
    const sample = (color: string) => {
      context.clearRect(0, 0, 1, 1)
      context.fillStyle = color
      context.fillRect(0, 0, 1, 1)
      return Array.from(context.getImageData(0, 0, 1, 1).data).slice(0, 3)
    }
    return {
      background: sample(style.backgroundColor),
      border: sample(style.borderColor),
      text: sample(style.color),
    }
  })
}

function contrast(first: number[], second: number[]) {
  const luminance = (rgb: number[]) => {
    const linear = rgb.map((value) => {
      const channel = value / 255
      return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
    })
    return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
  }
  const values = [luminance(first), luminance(second)].sort((a, b) => b - a)
  return (values[0] + 0.05) / (values[1] + 0.05)
}

for (const app of [
  { name: '租户', url: '/login', title: 'SaaS Forge 租户端', heading: '租户登录' },
  {
    name: '运营端',
    url: 'http://127.0.0.1:5173/login',
    title: 'SaaS Forge 平台控制台',
    heading: '运营登录',
  },
]) {
  test(`${app.name}登录的实心主按钮在默认、悬停、按下与键盘焦点状态可读`, async ({
    page,
  }, testInfo) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) errors.push(message.text())
    })
    await page.goto(app.url)
    await expect(page).toHaveTitle(app.title)
    await expect(page.getByRole('heading', { name: app.heading })).toBeVisible()
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    const button = page.getByRole('button', { name: '登录', exact: true })
    const check = async (background: number[]) => {
      await expect.poll(async () => (await buttonColors(button)).background).toEqual(background)
      const colors = await buttonColors(button)
      expect(colors.border).toEqual(background)
      expect(colors.text).toEqual([255, 255, 255])
      expect(contrast(colors.background, colors.text)).toBeGreaterThanOrEqual(4.5)
    }
    await page.mouse.move(0, 0)
    await check([79, 70, 229])
    await page.screenshot({ path: testInfo.outputPath('default.png') })
    await button.hover()
    await check([71, 63, 206])
    await page.screenshot({ path: testInfo.outputPath('hover.png') })
    await page.mouse.down()
    await check([63, 56, 183])
    await page.screenshot({ path: testInfo.outputPath('pressed.png') })
    await page.mouse.move(0, 0)
    await page.mouse.up()
    await page.getByLabel('密码', { exact: true }).focus()
    // Element Plus 的显示密码控件可能占一个 tab，直到焦点到达提交按钮。
    for (
      let step = 0;
      step < 3 && !(await button.evaluate((element) => element === document.activeElement));
      step++
    )
      await page.keyboard.press('Tab')
    await expect(button).toBeFocused()
    await expect(button).toHaveCSS('outline-style', 'solid')
    await expect(button).toHaveCSS('outline-width', '2px')
    await page.screenshot({ path: testInfo.outputPath('focus.png') })
    if (app.name === '租户') {
      const link = page.getByTestId('mode-password')
      await expect(link).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
      await expect(link).toHaveCSS('color', 'rgb(79, 70, 229)')
      await link.hover()
      await expect(link).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
    }
    expect(errors).toEqual([])
  })
}

test('plain 视图按钮保留浅底主色文字及原有悬停语义', async ({ page }) => {
  await openAsAdmin(page, '/roles', () => undefined)
  const active = page.getByTestId('restore-view-active')
  await expect(active).toHaveAttribute('aria-pressed', 'true')
  await page.mouse.move(0, 0)
  await expect.poll(async () => (await buttonColors(active)).background).toEqual([237, 237, 252])
  expect((await buttonColors(active)).text).toEqual([79, 70, 229])
  await active.hover()
  await expect.poll(async () => (await buttonColors(active)).background).toEqual([79, 70, 229])
  expect((await buttonColors(active)).text).toEqual([255, 255, 255])
  await page.getByTestId('restore-view-deleted').click()
  await expect(active).toHaveAttribute('aria-pressed', 'false')
  await expect(page.getByTestId('restore-view-deleted')).toHaveAttribute('aria-pressed', 'true')
})

test('浅色主题的表格分隔线使用 Slate200', async ({ page }) => {
  await openAsAdmin(page, '/roles', () => undefined)
  await expect(page.locator('.el-table th.el-table__cell').first()).toHaveCSS(
    'border-bottom-color',
    'rgb(226, 232, 240)',
  )
})

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 1024, height: 768 },
]) {
  test(`开发宿主的页底、内容面、正文与顶栏分隔线使用统一浅色主题（${viewport.width}）`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(viewport)
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    page.on('console', (message) => {
      if (['error', 'warning'].includes(message.type())) errors.push(message.text())
    })
    await page.addInitScript(() =>
      localStorage.setItem(
        'forge.tenant.session',
        JSON.stringify({
          accessToken: 'theme-proof',
          refreshToken: 'theme-proof',
          membershipId: 'theme-member',
          tenantId: 'theme-tenant',
          tenantName: '主题验收租户',
          identityName: '测试成员',
        }),
      ),
    )
    await page.route('**/api/iam/auth/access', (route) =>
      route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: { permissions: ['order:read', 'order:create'], menus: [], subApps: [] },
        },
      }),
    )
    await page.route('**/api/demo/orders?*', (route) =>
      route.fulfill({
        json: {
          code: 'ok',
          httpStatus: 200,
          data: [{ id: 'theme-order', title: '主题验收订单', amount: 860, status: 'unpaid' }],
          meta: { page: { number: 1, size: 20, totalElements: 1, totalPages: 1 } },
        },
      }),
    )
    await page.goto('http://127.0.0.1:5177/apps/demo/overview')
    await expect(page).toHaveTitle('demo Sub-App 开发宿主')
    await expect(page.getByText('主题验收订单', { exact: true })).toBeVisible()
    await expect(page.locator('vite-error-overlay')).toHaveCount(0)
    await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(248, 250, 252)')
    await expect(page.locator('body')).toHaveCSS('color', 'rgb(51, 65, 85)')
    await expect(page.getByRole('main')).toHaveCSS('background-color', 'rgb(255, 255, 255)')
    await expect(page.getByRole('main')).toHaveCSS('color', 'rgb(51, 65, 85)')
    await expect(page.getByRole('banner')).toHaveCSS('background-color', 'rgb(255, 255, 255)')
    await expect(page.getByRole('banner')).toHaveCSS('border-bottom-color', 'rgb(226, 232, 240)')
    await expect(page.locator('.el-table th.el-table__cell').first()).toHaveCSS(
      'border-bottom-color',
      'rgb(226, 232, 240)',
    )
    await page.getByRole('button', { name: '打开弹窗', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '子应用弹窗' })).toBeVisible()
    await page.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '子应用弹窗' })).toBeHidden()
    await page.mouse.move(0, 0)
    await page.screenshot({ path: testInfo.outputPath('devhost.png'), animations: 'disabled' })
    expect(errors).toEqual([])
  })
}
