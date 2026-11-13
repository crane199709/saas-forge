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
  access: {
    permissions?: string[]
    readPermissions?: string[]
    menus?: unknown[]
    subApps?: unknown[]
    layout?: unknown
    session?: { accessToken: string; membershipId: string; tenantId: string }
  } = {},
) {
  await page.addInitScript(
    (session) =>
      localStorage.setItem(
        'forge.tenant.session',
        JSON.stringify({
          accessToken: 'tenant-access',
          refreshToken: 'tenant-refresh',
          membershipId: 'm',
          tenantId: 't',
          tenantName: '示例科技有限公司',
          identityName: '测试成员',
          ...session,
        }),
      ),
    access.session ?? {},
  )
  await page.route('**/iam/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/iam/auth/access')
      return route.fulfill(
        ok({
          permissions: [
            'announcement:manage',
            ...(access.readPermissions ?? ['todo:read', 'notification:read', 'announcement:read']),
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
        ok([
          {
            membershipId: access.session?.membershipId ?? 'm',
            tenantId: access.session?.tenantId ?? 't',
            tenantName: '示例科技有限公司',
          },
        ]),
      )
    const response = respond(path, route)
    if (response) return response
    if (path.endsWith('/unread-count')) return route.fulfill(ok({ count: 3 }))
    if (path === '/iam/todos' || path === '/iam/notifications') return route.fulfill(onePage([]))
    return route.fulfill(ok([]))
  })
  await page.goto(url)
}
