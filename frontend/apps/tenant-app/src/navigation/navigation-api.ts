import type { MenuNode, NavigationLayout, TenantClient } from '@crane199709/sdk'

/** 管理页读取的布局与目录；`menus` 按套餐裁剪、不按操作者本人权限裁剪，不含页面操作。 */
export interface TenantNavigationLayout {
  layout: NavigationLayout | null
  menus: MenuNode[]
}

/** 租户导航布局接口；需要 `iam:menu:manage`。 */
export function createNavigationApi(client: Pick<TenantClient, 'api'>) {
  // 经网关的 IAM 路由，与 `/iam/auth` 同源
  const path = '/iam/navigation-layout'
  return {
    read: () => client.api<TenantNavigationLayout>(path, { method: 'GET' }),
    replace: (layout: NavigationLayout) =>
      client.api<NavigationLayout>(path, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(layout),
      }),
    reset: () => client.api<null>(path, { method: 'DELETE' }),
  }
}
