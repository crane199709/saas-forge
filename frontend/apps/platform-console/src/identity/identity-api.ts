import type { PlatformClient } from '@crane199709/sdk'

/** 按登录标识精确查到的身份；查不到时接口以 `mfa.identityNotFound` 失败，不是空结果。 */
export interface IdentityLookup {
  identityId: string
  displayName: string
  mfaBound: boolean
}

export function createIdentityApi(client: Pick<PlatformClient, 'api'>) {
  // 经网关的 iam 平台路由，只接受 Platform Realm Token
  const base = '/iam/platform/identities'

  return {
    lookup: (identifier: string) =>
      client.api<IdentityLookup>(`${base}/lookup`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ identifier }),
      }),
    resetMfa: (identityId: string) =>
      client.api<null>(`${base}/${identityId}/mfa/reset`, { method: 'POST' }),
  }
}

export type IdentityApi = ReturnType<typeof createIdentityApi>
