import { expect, test, type Page } from '@playwright/test'
import { ok, onePage, openAsAdmin, viewports } from './support'

const access = {
  permissions: [
    'order:read',
    'order:create',
    'order:update',
    'order:delete',
    'order:import',
    'order:export',
  ],
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
        {
          code: 'demo.overview',
          name: '示例子应用',
          app: 'demo',
          path: 'overview',
          permission: 'order:read',
        },
      ],
    },
  ],
}

const orders = [
  {
    id: 'o1',
    title: '华东大区 2026 年第四季度办公设备集中采购订单',
    amount: 128000.5,
    status: 'unpaid',
  },
  { id: 'o2', title: '办公用品补货', amount: 860, status: 'closed' },
  { id: 'o3', title: '服务器扩容', amount: 45200, status: 'unpaid' },
]
const attachments = [
  {
    id: 'a1',
    filename: '采购合同-2026Q4-最终版.pdf',
    size: 2048576,
    createdAt: '2026-11-08T09:30:00Z',
  },
  { id: 'a2', filename: '报价单.xlsx', size: 512, createdAt: '2026-11-07T09:30:00Z' },
]

async function open(page: Page, url: string) {
  // 只拦截接口：页面地址 /apps/demo/... 同样含 demo 段。
  await page.route(/:\d+\/api\/demo\//, (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/demo/orders') return route.fulfill(onePage(orders))
    if (path === '/demo/orders/o1/attachments') return route.fulfill(ok(attachments))
    if (path === '/demo/context')
      return route.fulfill(ok({ identityId: 'i', tenantId: 't', membershipId: 'm', version: 1 }))
    return route.fulfill(onePage([]))
  })
  await openAsAdmin(page, url, () => undefined, access)
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })

    test('订单', async ({ page }) => {
      await open(page, '/apps/demo/overview')
      await expect(page.getByTestId('order-title').first()).toBeVisible()
      await expect(page).toHaveScreenshot(`sub-app-orders-${label}.png`)
    })

    test('附件', async ({ page }) => {
      await open(page, '/apps/demo/attachments')
      await expect(page.getByText('采购合同-2026Q4-最终版.pdf')).toBeVisible()
      await expect(page).toHaveScreenshot(`sub-app-attachments-${label}.png`)
    })

    test('附件-已选文件', async ({ page }) => {
      await open(page, '/apps/demo/attachments')
      await page.getByTestId('attachment-file').setInputFiles({
        name: '2026年第四季度采购合同补充协议（双方盖章版）.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('pdf'),
      })
      await expect(page.getByTestId('attachment-upload')).toBeEnabled()
      await expect(page).toHaveScreenshot(`sub-app-attachments-picked-${label}.png`)
    })
  })
}
