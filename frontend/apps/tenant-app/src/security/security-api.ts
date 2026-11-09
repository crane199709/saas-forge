import type { TenantClient } from '@crane199709/sdk'

/** 本租户的安全设置，由 tenant-app 维护，不属于通用 SDK。 */
export interface SecuritySettings {
  /** MFA Requirement：进入本租户的授权须已完成 MFA。 */
  mfaRequired: boolean
}

/** 租户安全设置接口，需要 iam:security:manage。 */
export function createSecurityApi(client: Pick<TenantClient, 'api'>) {
  // 经网关的 IAM 路由，与 `/iam/auth` 同源
  const path = '/iam/security-settings'
  return {
    getIpAllowlist: () =>
      client.api<{ rules: string[]; currentIp: string }>(`${path}/ip-allowlist`),
    updateIpAllowlist: (rules: string[]) =>
      client.api<{ rules: string[]; currentIp: string }>(`${path}/ip-allowlist`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ rules }),
      }),
    get: () => client.api<SecuritySettings>(path),
    update: (settings: SecuritySettings) =>
      client.api<SecuritySettings>(path, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settings),
      }),
  }
}
