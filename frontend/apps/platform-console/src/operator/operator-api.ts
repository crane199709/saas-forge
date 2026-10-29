import type { PlatformClient } from '@forge/sdk'

/** iam 运营账号接口的模型，由 platform-console 维护，不属于通用 SDK。 */
export interface Operator {
  id: string
  account: string
  displayName: string
  /** 负责的区域，平台角色的数据范围可据此限定可见的租户；未分配时为 null。 */
  region: string | null
  status: 'active' | 'disabled'
  /** 持有的平台角色。 */
  roleIds: string[]
  createdAt: string
}

export interface CreateOperator {
  account: string
  displayName: string
  password: string
  region?: string
  roleIds: string[]
}

export interface UpdateOperator {
  displayName: string
  region?: string
}

export function createOperatorApi(client: Pick<PlatformClient, 'api'>) {
  // 经网关的 iam 平台路由，只接受 Platform Realm Token
  const base = '/iam/platform/operators'
  const send = <T>(method: string, path: string, body?: unknown) =>
    client.api<T>(`${base}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })

  return {
    list: () => client.api<Operator[]>(base),
    create: (request: CreateOperator) => send<Operator>('POST', '', request),
    update: (id: string, request: UpdateOperator) => send<null>('PUT', `/${id}`, request),
    disable: (id: string) => send<null>('POST', `/${id}/disable`),
    enable: (id: string) => send<null>('POST', `/${id}/enable`),
    resetPassword: (id: string, password: string) =>
      send<null>('PUT', `/${id}/password`, { password }),
    assignRoles: (id: string, roleIds: string[]) => send<null>('PUT', `/${id}/roles`, { roleIds }),
  }
}

export type OperatorApi = ReturnType<typeof createOperatorApi>
