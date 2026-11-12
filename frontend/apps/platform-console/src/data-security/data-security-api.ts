import type { PlatformClient } from '@crane199709/sdk'

export type Strategy =
  | 'mask_middle'
  | 'mask_tail4'
  | 'keepFirst'
  | 'replace'
  | 'email'
  | 'phone'
  | 'idcard'
  | 'bankcard'
  | 'name'
export interface MaskingRule {
  code: string
  name: string
  rule: { strategy: Strategy; keepFirst: number | null; replacement: string | null }
}
export interface DataGrade {
  grade: string
  name: string
  defaultRule: string
  exportAllowed: boolean
}
export interface MaskingPreview {
  success: boolean
  value: string | null
  errorCode: string | null
  errorMessage: string | null
}
/** 运营端数据安全接口；规则和分级均由平台统一维护。 */
export function createDataSecurityApi(client: Pick<PlatformClient, 'api'>) {
  const base = '/iam/platform/data-security'
  const send = <T>(method: string, path: string, body?: unknown) =>
    client.api<T>(`${base}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  return {
    rules: () => client.api<MaskingRule[]>(`${base}/rules`),
    create: (rule: MaskingRule) => send<MaskingRule>('POST', '/rules', rule),
    update: (rule: MaskingRule) =>
      send<MaskingRule>('PUT', `/rules/${encodeURIComponent(rule.code)}`, {
        name: rule.name,
        rule: rule.rule,
      }),
    preview: (ruleCode: string, value: string) =>
      send<MaskingPreview>('POST', '/preview', { ruleCode, value }),
    remove: (code: string) => send<null>('DELETE', `/rules/${encodeURIComponent(code)}`),
    setGrade: (grade: string, defaultRule: string) =>
      send<DataGrade>('PUT', `/grades/${encodeURIComponent(grade)}`, { defaultRule }),
    grades: () => client.api<DataGrade[]>(`${base}/grades`),
  }
}
