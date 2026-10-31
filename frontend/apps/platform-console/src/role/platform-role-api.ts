import type { PlatformClient } from '@crane199709/sdk'

/**
 * 平台角色在一个数据资源上的 Data Policy：预设（如 `self`、`all`）或自定义条件 `custom`。
 * 自定义条件是 JSON 表达式树，可引用 Operator 的负责区域 `subject.attributes.region`。
 */
export interface DataPolicy {
  resource: string
  scope: string
  condition?: unknown
}

/** iam 平台角色接口的模型，由 platform-console 维护，不属于通用 SDK。 */
export interface PlatformRole {
  id: string
  code: string
  name: string
  /** 超级管理员：拥有全部平台权限与全部数据，不可修改或删除。 */
  builtin: boolean
  operatorCount: number
  permissions: string[]
  dataPolicies: DataPolicy[]
}

/** 可授予平台角色的权限码，只来自 Platform Realm 服务的 Resource Manifest。 */
export interface PlatformPermission {
  code: string
  name: string
  service: string
  group: string | null
}

/** 可配置 Data Policy 的平台数据资源。 */
export interface PlatformDataResource {
  code: string
  name: string
  service: string
  /** 可用的 Data Scope 预设。 */
  scopes: string[]
  attributes: { name: string; label: string | null; type: string; multiValued: boolean }[]
}

export interface PlatformRoleContent {
  name: string
  permissions: string[]
  dataPolicies: DataPolicy[]
}

export interface CreatePlatformRole extends PlatformRoleContent {
  code: string
}

export function createPlatformRoleApi(client: Pick<PlatformClient, 'api'>) {
  // 经网关的 iam 平台路由，只接受 Platform Realm Token
  const base = '/iam/platform/roles'
  const send = <T>(method: string, path: string, body?: unknown) =>
    client.api<T>(`${base}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })

  return {
    list: () => client.api<PlatformRole[]>(base),
    permissions: () => client.api<PlatformPermission[]>(`${base}/permissions`),
    dataResources: () => client.api<PlatformDataResource[]>(`${base}/data-resources`),
    create: (request: CreatePlatformRole) => send<PlatformRole>('POST', '', request),
    update: (id: string, request: PlatformRoleContent) => send<null>('PUT', `/${id}`, request),
    remove: (id: string) => send<null>('DELETE', `/${id}`),
  }
}

export type PlatformRoleApi = ReturnType<typeof createPlatformRoleApi>
