import type { PlatformClient } from '@crane199709/sdk'
import type { GrantablePermission, PermissionMenu } from '@crane199709/ui'

/** forge-platform 套餐接口的模型，由 platform-console 维护，不属于通用 SDK。 */
export interface Plan {
  id: string
  code: string
  name: string
  /** Feature Pack 中的权限码，按编码排序。 */
  permissions: string[]
  /** Feature Pack 中的 Sub-App 编码，按编码排序。 */
  subApps: string[]
  /** 成员数上限，null 为不限。 */
  memberQuota: number | null
  /** 存储容量上限（MiB），null 为不限。 */
  storageQuotaMb: number | null
  /** 每租户每月 Token 总量上限，null 为不限。 */
  tokenQuota: number | null
  updatedAt: string
}

/** Feature Pack 授权树的菜单节点；叶子带所属 Sub-App，分组节点的 `app` 为 null。 */
export interface CatalogMenu extends PermissionMenu {
  app: string | null
  children: CatalogMenu[]
}

/** Feature Pack 授权树，来自各业务服务的 Resource Manifest，不按 Plan 或 Sub-App 裁剪。 */
export interface CatalogPermissionTree {
  menus: CatalogMenu[]
  /** 未挂在菜单上的权限码，按服务、分组、编码排序。 */
  others: GrantablePermission[]
}

export interface PlanContent {
  name: string
  permissions: string[]
  subApps: string[]
  memberQuota: number | null
  storageQuotaMb: number | null
  /** 每租户每月 Token 总量上限，null 为不限。 */
  tokenQuota: number | null
}

export interface CreatePlan extends PlanContent {
  code: string
}

export function createPlanApi(client: Pick<PlatformClient, 'api'>) {
  // 经网关的 platform 路由，只接受 Platform Realm Token
  const base = '/platform/plans'
  const send = <T>(method: string, path: string, body?: unknown) =>
    client.api<T>(`${base}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })

  return {
    list: () => client.api<Plan[]>(base),
    permissionTree: () => client.api<CatalogPermissionTree>(`${base}/permission-tree`),
    create: (request: CreatePlan) => send<Plan>('POST', '', request),
    update: (id: string, request: PlanContent) => send<Plan>('PUT', `/${id}`, request),
    remove: (id: string) => send<null>('DELETE', `/${id}`),
  }
}

export type PlanApi = ReturnType<typeof createPlanApi>
