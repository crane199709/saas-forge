import type { PlatformClient } from '@forge/sdk'

/** forge-platform App Registry 接口的模型，由 platform-console 维护，不属于通用 SDK。 */
export interface SubApp {
  id: string
  code: string
  name: string
  /** 当前版本，仅用于展示与排查。 */
  version: string
  /** 各环境的 remoteEntry.js 地址，键为环境名。 */
  remoteEntries: Record<string, string>
  /** 所需的最低共享依赖版本（x.y.z），键为包名。 */
  sharedRequirements: Record<string, string>
  updatedAt: string
}

export interface SubAppContent {
  name: string
  version: string
  remoteEntries: Record<string, string>
  sharedRequirements: Record<string, string>
}

export interface CreateSubApp extends SubAppContent {
  code: string
}

export function createSubAppApi(client: Pick<PlatformClient, 'api'>) {
  // 经网关的 platform 路由，只接受 Platform Realm Token
  const base = '/platform/sub-apps'
  const send = <T>(method: string, path: string, body?: unknown) =>
    client.api<T>(`${base}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })

  return {
    list: () => client.api<SubApp[]>(base),
    create: (request: CreateSubApp) => send<SubApp>('POST', '', request),
    update: (id: string, request: SubAppContent) => send<SubApp>('PUT', `/${id}`, request),
    remove: (id: string) => send<null>('DELETE', `/${id}`),
  }
}

export type SubAppApi = ReturnType<typeof createSubAppApi>
