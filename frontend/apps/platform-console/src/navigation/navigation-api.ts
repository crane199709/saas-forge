import type { NavigationLayout, PlatformClient } from '@crane199709/sdk'

/** 平台控制台的导航布局；没有布局时 `layout` 为 null。 */
export interface PlatformNavigationLayout {
  layout: NavigationLayout | null
}

/** 平台导航布局接口；需要 `platform:menu:manage`。 */
export function createNavigationApi(client: Pick<PlatformClient, 'api'>) {
  const path = '/iam/platform/navigation-layout'
  return {
    read: () => client.api<PlatformNavigationLayout>(path, { method: 'GET' }),
    replace: (layout: NavigationLayout) =>
      client.api<NavigationLayout>(path, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(layout),
      }),
    reset: () => client.api<null>(path, { method: 'DELETE' }),
  }
}
