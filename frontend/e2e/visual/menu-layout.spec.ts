import { expect, test, type Page } from '@playwright/test'
import { ok, openAsAdmin, viewports } from './support'

// 目录含三层清单分组，布局中有自定义分组与已下线的引用；名称取接近真实的最长值。
const menus = [
  {
    code: 'demo',
    name: '示例应用',
    children: [
      { code: 'demo.overview', name: '订单概览', app: 'demo', path: 'overview', children: [] },
      {
        code: 'demo.finance',
        name: '财务结算与发票管理',
        children: [
          { code: 'demo.bills', name: '账单', app: 'demo', path: 'bills', children: [] },
          {
            code: 'demo.invoices',
            name: '供应商发票核对与归档',
            app: 'demo',
            path: 'invoices',
            children: [],
          },
        ],
      },
    ],
  },
]

const layout = {
  nodes: [
    { page: 'members' },
    {
      group: 'daily',
      name: '日常工作与跨部门协同事项',
      children: [{ menu: 'demo.overview' }, { page: 'announcements' }, { menu: 'retired.report' }],
    },
    { menu: 'demo' },
    {
      group: 'settings',
      name: '系统设置',
      children: [{ page: 'dictionaries' }, { page: 'parameters' }],
    },
  ],
}

async function openMenuLayout(page: Page, failRead = false) {
  await openAsAdmin(page, '/menu-layout', (path, route) =>
    path === '/iam/navigation-layout'
      ? route.fulfill(
          failRead
            ? {
                status: 500,
                json: {
                  code: 'system.error',
                  httpStatus: 500,
                  userTip: '服务暂时不可用，请稍后重试',
                  data: null,
                  traceId: 'visual-trace-0001',
                },
              }
            : ok({ layout, menus }),
        )
      : undefined,
  )
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    test('菜单管理', async ({ page }) => {
      await openMenuLayout(page)
      await expect(page.getByTestId('navigation-node-menu:demo.invoices')).toBeVisible()
      await expect(page).toHaveScreenshot(`menu-layout-${label}.png`)
    })
  })
}

test('菜单管理-重命名分组', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openMenuLayout(page)
  await page.getByTestId('navigation-rename-daily').click()
  await expect(page.getByTestId('navigation-group-name')).toBeVisible()
  await expect(page).toHaveScreenshot('menu-layout-rename-desktop.png')
})

test('菜单管理-恢复默认确认', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openMenuLayout(page)
  await page.getByTestId('navigation-reset').click()
  await expect(page.getByTestId('menu-layout-reset-confirm')).toBeVisible()
  await expect(page).toHaveScreenshot('menu-layout-reset-desktop.png')
})

test('菜单管理-读取失败', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openMenuLayout(page, true)
  await expect(page.getByTestId('menu-layout-retry')).toBeVisible()
  await expect(page).toHaveScreenshot('menu-layout-error-desktop.png')
})
