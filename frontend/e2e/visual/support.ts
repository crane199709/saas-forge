import type { Page, Route } from '@playwright/test'

export function ok(data: unknown, page?: unknown) {
  return {
    json: {
      code: 'ok',
      httpStatus: 200,
      data,
      meta: page ? { page } : undefined,
      traceId: 'visual',
    },
  }
}

export const onePage = (items: unknown[]) =>
  ok(items, { number: 1, size: 20, totalElements: items.length, totalPages: 1 })

export const viewports = [
  ['desktop', 1440, 900],
  ['laptop', 1024, 768],
] as const

/** 以租户管理员身份打开页面；`respond` 处理页面自身的接口，返回 undefined 时按空列表应答。 */
export async function openAsAdmin(
  page: Page,
  url: string,
  respond: (path: string, route: Route) => Promise<void> | undefined,
  access: { permissions?: string[]; menus?: unknown[]; subApps?: unknown[]; layout?: unknown } = {},
) {
  await page.addInitScript(() =>
    localStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({
        accessToken: 'tenant-access',
        refreshToken: 'tenant-refresh',
        membershipId: 'm',
        tenantId: 't',
        tenantName: '示例科技有限公司',
        identityName: '测试成员',
      }),
    ),
  )
  await page.route('**/iam/**', (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/iam/auth/access')
      return route.fulfill(
        ok({
          permissions: [
            'announcement:manage',
            'iam:member:manage',
            'iam:org:manage',
            'iam:role:manage',
            'iam:audit:read',
            'iam:reference-data:manage',
            'iam:security:manage',
            'iam:menu:manage',
            ...(access.permissions ?? []),
          ],
          menus: access.menus ?? [],
          subApps: access.subApps ?? [],
          layout: access.layout ?? null,
        }),
      )
    if (path === '/iam/auth/memberships')
      return route.fulfill(
        ok([{ membershipId: 'm', tenantId: 't', tenantName: '示例科技有限公司' }]),
      )
    if (path.endsWith('/unread-count')) return route.fulfill(ok({ count: 3 }))
    return respond(path, route) ?? route.fulfill(ok([]))
  })
  await page.goto(url)
}
