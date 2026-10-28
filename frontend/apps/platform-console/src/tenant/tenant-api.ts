import type { Page, PlatformClient } from '@forge/sdk'

/** forge-platform 租户接口的模型，由 platform-console 维护，不属于通用 SDK。 */
export interface Tenant {
  id: string
  name: string
  status: string
  adminContact: string
  createdAt: string
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
    create: (name: string, adminContact: string) => post<TenantDetail>('', { name, adminContact }),
    retry: (id: string, service: string) =>
      post<Initialization>(`/${id}/initializations/${encodeURIComponent(service)}/retry`),
  }
}

export type TenantApi = ReturnType<typeof createTenantApi>
