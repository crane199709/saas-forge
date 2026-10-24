import { expect, test, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFile, readdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const browserErrors = new WeakMap<Page, string[]>()

test.beforeEach(async ({ page }) => {
  const errors: string[] = []
  browserErrors.set(page, errors)
  page.on('pageerror', (error) => errors.push(error.message))
  page.on('console', (message) => {
    if (['error', 'warning'].includes(message.type())) errors.push(message.text())
  })
})

test.afterEach(async ({ page }) => {
  expect(browserErrors.get(page)).toEqual([])
})

test('只替换运行时 remoteEntry 地址，刷新即加载 v2，Shell 构建字节不变', async ({ page }) => {
  const dist = resolve('apps/tenant-app/dist')
  const configPath = resolve(dist, 'sub-apps.json')
  const originalConfig = await readFile(configPath, 'utf8')
  async function shellHash() {
    const hash = createHash('sha256')
    const files = await readdir(dist, { recursive: true, withFileTypes: true })
    for (const file of files
      .filter((entry) => entry.isFile() && entry.name !== 'sub-apps.json')
      .sort((a, b) => `${a.parentPath}/${a.name}`.localeCompare(`${b.parentPath}/${b.name}`))) {
      hash.update(await readFile(resolve(file.parentPath, file.name)))
    }
    return hash.digest('hex')
  }
  const before = await shellHash()
  try {
    await page.goto('/apps/demo/overview')
    await expect(page.getByRole('heading', { name: '示例子应用 v1' })).toBeVisible()
    await writeFile(
      configPath,
      JSON.stringify({ remoteEntry: 'http://127.0.0.1:5176/remoteEntry.js' }),
    )
    const remoteResponse = page.waitForResponse('http://127.0.0.1:5176/remoteEntry.js')
    await page.reload()
    expect((await remoteResponse).status()).toBe(200)
    await expect(page.getByRole('heading', { name: '示例子应用 v2' })).toBeVisible()
    await page.screenshot({ path: '/tmp/forge-02-v2.png' })
    expect(await shellHash()).toBe(before)
  } finally {
    await writeFile(configPath, originalConfig)
  }
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

test('Shell 从独立端口加载 Sub-App，六个共享依赖具有相同的运行时引用', async ({ page }) => {
  await page.goto('/apps/demo/overview')
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
    await expect(page.getByTestId(`shared-${dependency}`)).toHaveText(`${dependency}: singleton`)
  }
})

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
