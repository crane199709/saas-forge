import { expect, test, type Page, type Route } from '@playwright/test'
import { ok, openAsAdmin, viewports } from './support'

const rules = [
  {
    code: 'phone',
    name: '手机号脱敏',
    rule: { strategy: 'phone', keepFirst: null, replacement: null },
  },
  {
    code: 'bankcard',
    name: '结算账户银行卡脱敏',
    rule: { strategy: 'bankcard', keepFirst: null, replacement: null },
  },
  {
    code: 'customer_display_name',
    name: '跨区域供应链客户展示名称保留前两位',
    rule: { strategy: 'keepFirst', keepFirst: 2, replacement: null },
  },
]
const fields = [
  {
    service: 'demo-service',
    path: 'order.customer.contact.primaryMobilePhoneNumber',
    grade: 'L3',
    defaultRule: 'phone',
    overrideRule: null,
    effectiveRule: 'phone',
    status: 'active',
    exportAllowed: true,
  },
  {
    service: 'regional-supply-chain-order-fulfillment-service',
    path: 'shipment.customsDeclaration.beneficiary.settlementAccount.bankAccountNumber',
    grade: 'L4',
    defaultRule: null,
    overrideRule: 'bankcard',
    effectiveRule: 'bankcard',
    status: 'active',
    exportAllowed: false,
  },
  {
    service: 'demo-service',
    path: 'order.customer.invoice.emailAddress',
    grade: 'L2',
    defaultRule: null,
    overrideRule: null,
    effectiveRule: null,
    status: 'missing-rule',
    exportAllowed: true,
  },
]
const grades = ['L1', 'L2', 'L3', 'L4'].map((grade) => ({
  grade,
  name: ({ L1: '公开', L2: '内部', L3: '敏感', L4: '极敏感' } as Record<string, string>)[grade],
  defaultRule: grade === 'L4' ? 'bankcard' : 'phone',
  exportAllowed: grade !== 'L4',
}))

function failure(route: Route, message: string, status = 503) {
  return route.fulfill({
    status,
    json: {
      code: 'field-masking.unavailable',
      httpStatus: status,
      userTip: message,
      traceId: 'visual-data-security',
    },
  })
}
function health(page: Page, expectedFailure: boolean) {
  const errors: string[] = []
  page.on('pageerror', (cause) => errors.push(cause.message))
  page.on('console', (message) => {
    if (!['error', 'warning'].includes(message.type())) return
    if (
      expectedFailure &&
      /^Failed to load resource: the server responded with a status of (400|409|503)/.test(
        message.text(),
      )
    )
      return
    errors.push(message.text())
  })
  return errors
}
async function identity(page: Page, platform: boolean) {
  await expect(page).toHaveURL(/\/data-security$/)
  await expect(page).toHaveTitle(platform ? 'SaaS Forge 平台控制台' : 'SaaS Forge 租户端')
  await expect(page.getByRole('heading', { name: '数据安全', exact: true })).toBeVisible()
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
}
async function openTenant(page: Page, state: string) {
  const errors = health(page, state.includes('failed'))
  await openAsAdmin(
    page,
    '/data-security',
    (path, route) => {
      if (path.endsWith('/data-security/fields'))
        return state === 'load-failed'
          ? failure(route, '敏感字段登记暂不可用，请重试')
          : route.fulfill(ok(state === 'empty' ? [] : fields))
      if (path.endsWith('/data-security/rules')) return route.fulfill(ok(rules))
      if (path.endsWith('/rule')) return failure(route, '所选规则暂不可用，请重新选择后保存', 400)
      if (path.endsWith('/preview'))
        return route.fulfill(
          ok({ success: true, value: '138****5678', errorCode: null, errorMessage: null }),
        )
      return undefined
    },
    { permissions: ['iam:data-security:read', 'iam:data-security:manage'] },
  )
  await identity(page, false)
  return errors
}
async function openPlatform(page: Page, state: string) {
  const errors = health(page, state.includes('failed'))
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.platform.session',
      JSON.stringify({
        accessToken: 'operator-access',
        refreshToken: 'operator-refresh',
        operatorName: '平台运营',
      }),
    ),
  )
  await page.route('**/iam/platform/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api/, '')
    if (path === '/iam/platform/access')
      return route.fulfill(
        ok({
          roles: [],
          permissions: ['platform:data-security:read', 'platform:data-security:manage'],
          layout: null,
        }),
      )
    if (path.endsWith('/data-security/rules') && route.request().method() === 'GET')
      return state === 'load-failed'
        ? failure(route, '脱敏规则暂不可用，请重试')
        : route.fulfill(ok(state === 'empty' ? [] : rules))
    if (path.endsWith('/data-security/grades')) return route.fulfill(ok(grades))
    if (path.endsWith('/preview'))
      return route.fulfill(
        ok({ success: true, value: '138****5678', errorCode: null, errorMessage: null }),
      )
    if (route.request().method() === 'DELETE')
      return failure(route, '规则已被敏感字段引用，不能删除', 409)
    if (route.request().method() === 'PUT')
      return failure(route, '规则配置不合法，请检查后重试', 400)
    return route.fulfill(ok([]))
  })
  await page.goto('http://127.0.0.1:5183/data-security')
  await identity(page, true)
  return errors
}

for (const [label, width, height] of viewports) {
  test.describe(label, () => {
    test.use({ viewport: { width, height } })
    for (const state of ['default', 'edit', 'preview', 'save-failed', 'load-failed', 'empty']) {
      test(`tenant ${state}`, async ({ page }) => {
        const errors = await openTenant(page, state)
        if (state === 'empty') await expect(page.getByText('暂无已登记的敏感字段')).toBeVisible()
        else if (state === 'load-failed')
          await expect(page.getByRole('button', { name: '重试', exact: true })).toBeVisible()
        else
          await expect(
            page.getByText('order.customer.contact.primaryMobilePhoneNumber', { exact: true }),
          ).toBeVisible()
        if (state === 'edit' || state === 'save-failed') {
          await page.getByTestId('field-edit').first().click()
          await expect(page.getByRole('dialog', { name: '设置规则' })).toBeVisible()
          if (state === 'save-failed') {
            await page.getByTestId('field-save').click()
            await expect(page.getByRole('alert')).toContainText('所选规则暂不可用')
            await expect(page.getByTestId('field-rule')).toContainText('手机号脱敏')
          }
        }
        if (state === 'preview') {
          await page.getByTestId('field-preview').first().click()
          await page.getByTestId('preview-value').fill('13812345678')
          await page.getByTestId('preview-run').click()
          await expect(page.getByTestId('preview-result')).toHaveText('138****5678')
        }
        if (label === 'laptop' && state === 'default') {
          const table = page.locator('.el-table')
          const scroll = table.locator('.el-scrollbar__wrap')
          await scroll.evaluate((element) => {
            element.scrollLeft = element.scrollWidth - element.clientWidth
          })
          const row = table
            .getByRole('row')
            .filter({ hasText: 'order.customer.contact.primaryMobilePhoneNumber' })
          const tableBox = await table.boundingBox()
          const actionsBox = await row.locator('td').last().boundingBox()
          expect(tableBox).not.toBeNull()
          expect(actionsBox).not.toBeNull()
          for (const text of ['手机号脱敏', '生效中', '允许导出']) {
            const cell = row.getByRole('cell', { name: text, exact: true })
            await expect(cell).toBeVisible()
            const box = await cell.boundingBox()
            expect(box).not.toBeNull()
            expect(box!.x).toBeGreaterThanOrEqual(tableBox!.x)
            expect(box!.x + box!.width).toBeLessThanOrEqual(actionsBox!.x + 1)
          }
          await scroll.evaluate((element) => {
            element.scrollLeft = 0
          })
        }
        await expect(page).toHaveScreenshot(`data-security-tenant-${state}-${label}.png`, {
          fullPage: true,
        })
        expect(errors).toEqual([])
      })
    }
    for (const state of [
      'default',
      'edit',
      'preview',
      'save-failed',
      'load-failed',
      'empty',
      'grades',
      'grade-edit',
      'delete-failed',
    ]) {
      test(`platform ${state}`, async ({ page }) => {
        const errors = await openPlatform(page, state)
        if (state === 'empty') await expect(page.getByText('暂无脱敏规则')).toBeVisible()
        else if (state === 'load-failed')
          await expect(page.getByRole('button', { name: '重试', exact: true })).toBeVisible()
        else
          await expect(
            page.getByRole('cell', { name: '手机号脱敏', exact: true }).first(),
          ).toBeVisible()
        if (state === 'edit' || state === 'save-failed') {
          await page.getByTestId('rule-edit').nth(2).click()
          await expect(page.getByRole('dialog', { name: '编辑规则' })).toBeVisible()
          if (state === 'save-failed') {
            await page.getByTestId('rule-name').fill('客户展示名称')
            await page.getByTestId('rule-save').click()
            await expect(page.getByRole('alert')).toContainText('规则配置不合法')
            await expect(page.getByTestId('rule-name')).toHaveValue('客户展示名称')
          }
        }
        if (state === 'preview') {
          await page.getByTestId('rule-preview').first().click()
          await page.getByTestId('preview-value').fill('13812345678')
          await page.getByTestId('preview-run').click()
          await expect(page.getByTestId('preview-result')).toHaveText('138****5678')
        }
        if (state === 'grades' || state === 'grade-edit') {
          await page.getByRole('tab', { name: '数据分级', exact: true }).click()
          await expect(page.getByRole('cell', { name: 'L4 极敏感', exact: true })).toBeVisible()
          if (state === 'grade-edit') {
            await page.getByTestId('grade-edit').nth(3).click()
            await expect(page.getByRole('dialog', { name: '编辑默认规则' })).toBeVisible()
          }
        }
        if (state === 'delete-failed') {
          await page.getByTestId('rule-delete').first().click()
          await page.getByTestId('rule-delete-confirm').click()
          await expect(
            page.getByRole('alert').filter({ hasText: '规则已被敏感字段引用' }),
          ).toBeVisible()
        }
        await expect(page).toHaveScreenshot(`data-security-platform-${state}-${label}.png`, {
          fullPage: true,
        })
        expect(errors).toEqual([])
      })
    }
  })
}
