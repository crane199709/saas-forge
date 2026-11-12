import type { TenantClient } from '@crane199709/sdk'

export interface MaskingRule {
  code: string
  name: string
  rule: { strategy: string; keepFirst: number | null; replacement: string | null }
}
export interface SensitiveField {
  service: string
  path: string
  grade: string
  defaultRule: string | null
  overrideRule: string | null
  effectiveRule: string | null
  status: 'active' | 'missing-rule'
  exportAllowed: boolean
}
export interface MaskingPreview {
  success: boolean
  value: string | null
  errorCode: string | null
  errorMessage: string | null
}

/** 租户数据安全接口；租户身份由会话决定。 */
export function createDataSecurityApi(client: Pick<TenantClient, 'api'>) {
  const base = '/iam/data-security'
  const fieldPath = (field: SensitiveField) =>
    `${base}/fields/${encodeURIComponent(field.service)}/${encodeURIComponent(field.path)}/rule`
  const send = <T>(method: string, path: string, body?: unknown) =>
    client.api<T>(path, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  return {
    fields: () => client.api<SensitiveField[]>(`${base}/fields`),
    preview: (ruleCode: string, value: string) =>
      send<MaskingPreview>('POST', `${base}/preview`, { ruleCode, value }),
    rules: () => client.api<MaskingRule[]>(`${base}/rules`),
    setRule: (field: SensitiveField, ruleCode: string) =>
      send<SensitiveField>('PUT', fieldPath(field), { ruleCode }),
    resetRule: (field: SensitiveField) => send<null>('DELETE', fieldPath(field)),
  }
}
