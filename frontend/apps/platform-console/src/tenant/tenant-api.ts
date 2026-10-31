import type { Page, PlatformClient } from '@crane199709/sdk'

/** forge-platform 租户接口的模型，由 platform-console 维护，不属于通用 SDK。 */
/** Tenant Lifecycle 状态，合法迁移由服务端的迁移表决定。 */
export type TenantStatus =
  'pending' | 'trial' | 'active' | 'suspended' | 'expired' | 'archived' | 'deleted'

export interface Tenant {
  id: string
  name: string
  status: TenantStatus
  adminContact: string
  /** 所在区域，平台角色的数据范围可据此限定可见的租户；未分配时为 null。 */
  region: string | null
  /** 适用的套餐；套餐上线前创建的租户为 null，不受套餐限制。 */
  planId: string | null
  /** 试用或订购的到期时间。 */
  expiresAt: string | null
  /** 删除状态下冷静期的结束时间，期内可撤回。 */
  purgeAt: string | null
  /** 冷静期已结束、各服务开始清理数据的时间。 */
  purgedAt: string | null
  createdAt: string
}

/** 一次状态变更，fromStatus 为 null 表示创建。 */
export interface StatusChange {
  fromStatus: TenantStatus | null
  toStatus: TenantStatus
  reason: string | null
  changedBy: string | null
  changedAt: string
}

export type InitializationStatus = 'pending' | 'succeeded' | 'failed'

/** 某个服务对该租户的初始化状态。 */
export interface Initialization {
  service: string
  status: InitializationStatus
  error: string | null
  updatedAt: string
}

export interface TenantDetail {
  tenant: Tenant
  initializations: Initialization[]
  /** 当前可迁移到的状态；冷静期内只有删除前的状态，即撤回删除。 */
  allowedTransitions: TenantStatus[]
  /** 状态变更历史，最近的在前。 */
  statusChanges: StatusChange[]
}

export interface CreateTenant {
  name: string
  adminContact: string
  region?: string
  status: 'trial' | 'active'
  expiresAt?: string
  planId: string
}

export interface Transition {
  status: TenantStatus
  reason?: string
  /** 只在迁移到试用或正常时可填。 */
  expiresAt?: string
}

export function createTenantApi(client: Pick<PlatformClient, 'api' | 'page'>) {
  // 经网关的 platform 路由，只接受 Platform Realm Token
  const base = '/platform/tenants'
  const post = <T>(path: string, body?: unknown) =>
    client.api<T>(`${base}${path}`, {
      method: 'POST',
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })

  return {
    list: (page: number, size: number): Promise<Page<Tenant>> =>
      client.page<Tenant>(`${base}?page=${page}&size=${size}`),
    get: (id: string) => client.api<TenantDetail>(`${base}/${id}`),
    create: (request: CreateTenant) => post<TenantDetail>('', request),
    transition: (id: string, request: Transition) =>
      post<TenantDetail>(`/${id}/transitions`, request),
    /** 更换套餐；降级后超出新套餐的授权随即失效但保留记录，升级后恢复。 */
    changePlan: (id: string, planId: string) =>
      client.api<TenantDetail>(`${base}/${id}/plan`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ planId }),
      }),
    retry: (id: string, service: string) =>
      post<Initialization>(`/${id}/initializations/${encodeURIComponent(service)}/retry`),
  }
}

export type TenantApi = ReturnType<typeof createTenantApi>
