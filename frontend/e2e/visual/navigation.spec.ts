import { expect, test, type Page } from '@playwright/test'
import { ok, openAsAdmin, viewports } from './support'

// 清单三层分组，加上管理员的自定义分组；名称取接近真实的最长值。
const access = {
  permissions: ['order:read'],
  subApps: [
    {
      code: 'demo',
      name: '示例',
      version: '1.0.0',
      remoteEntry: 'http://127.0.0.1:5185/remoteEntry.js',
      sharedRequirements: { vue: '3.5.0' },
    },
  ],
  menus: [
    {
      code: 'demo',
      name: '示例应用',
      children: [
        { code: 'demo.overview', name: '订单概览', app: 'demo', path: 'overview' },
        {
          code: 'demo.finance',
          name: '财务结算与发票管理',
          children: [
            { code: 'demo.bills', name: '账单', app: 'demo', path: 'bills' },
            { code: 'demo.invoices', name: '供应商发票核对与归档', app: 'demo', path: 'invoices' },
          ],
        },
      ],
    },
  ],
  layout: {
    nodes: [
      { page: 'members' },
      {
        group: 'daily',
        name: '日常工作',
        children: [
          { menu: 'demo.attachments' },
          { menu: 'demo.overview' },
          { page: 'announcements' },
        ],
      },
      { menu: 'demo' },
      {
        group: 'settings',
        name: '系统设置',
        children: [{ page: 'dictionaries' }, { page: 'parameters' }],
      },
    ],
  },
}

async function openHome(page: Page) {
  await page.route('**/demo/context', (route) =>
    route.fulfill(ok({ identityId: 'i', tenantId: 't', membershipId: 'm' })),
  )
  await openAsAdmin(page, '/', () => undefined, access)
  await expect(page.getByTestId('menu-group-demo.finance')).toBeVisible()
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    test('导航', async ({ page }) => {
      await openHome(page)
      await expect(page).toHaveScreenshot(`navigation-${label}.png`)
    })
  })
}

test('导航-分组收起', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openHome(page)
  await page.getByTestId('menu-group-daily').locator('.el-sub-menu__title').first().click()
  await expect(page.getByTestId('menu-demo.overview')).toBeHidden()
  await expect(page).toHaveScreenshot('navigation-collapsed-desktop.png')
})
