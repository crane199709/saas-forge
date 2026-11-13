import type { TenantClient } from '@crane199709/sdk'

/** IAM 参考数据接口的模型，由 tenant-app 维护，不属于通用 SDK。 */
export type ItemStatus = 'enabled' | 'disabled' | 'retired'

export interface DictionaryItem {
  code: string
  name: string
  sort: number
  /** 租户新增的自定义项，编码带 `custom.` 前缀。 */
  custom: boolean
  /** 只有 enabled 可选；retired 是新版本 Manifest 删掉的内置项或字典改为固定后的自定义项。 */
  status: ItemStatus
  /** 与同一字典的其他可选项重名（服务新版本新增的内置项造成），按名称导入会出错，需改名其中之一。 */
  nameConflict: boolean
}

export interface Dictionary {
  service: string
  code: string
  name: string
  extensible: boolean
  items: DictionaryItem[]
}

/** 租户覆盖快照不包含 Manifest 默认项，null 表示当时未覆盖该字段。 */
export interface DictionaryHistoryItem {
  code: string
  custom: boolean
  name: string | null
  sort: number | null
  enabled: boolean
}
export interface DictionaryHistoryMetadata {
  version: number
  summary: string
  changedBy: string | null
  changedByName: string | null
  changedAt: string
}
export interface DictionaryHistoryDetail extends DictionaryHistoryMetadata {
  snapshot: DictionaryHistoryItem[]
  current: Dictionary | null
  manifest: { code: string; name: string; items: { code: string; name: string }[] } | null
}
export interface DictionaryHistoryDiff {
  from: number
  to: number
  added: DictionaryHistoryItem[]
  removed: DictionaryHistoryItem[]
  changed: { before: DictionaryHistoryItem; after: DictionaryHistoryItem }[]
}

export type ParameterType = 'string' | 'integer' | 'decimal' | 'duration' | 'boolean' | 'dictionary'
/** 取值的 JSON 形态与 Manifest 一致：integer、decimal 为数字，boolean 为布尔，其余为字符串。 */
export type ParameterValue = string | number | boolean

export interface Parameter {
  service: string
  code: string
  name: string
  type: ParameterType
  /** 仅 dictionary 类型有值，指向同一服务的字典编码。 */
  dictionary: string | null
  defaultValue: ParameterValue
  min: ParameterValue | null
  max: ParameterValue | null
  maxLength: number | null
  tenantEditable: boolean
  /** 生效值。 */
  value: ParameterValue
  overridden: boolean
}

/** 自定义项编码由服务端加的前缀，租户只填写其后的部分。 */
export const CUSTOM_ITEM_PREFIX = 'custom.'

/** 参考数据接口；字典读取对所有成员开放；历史需要 iam:dictionary-history:read，修改需要 iam:reference-data:manage。 */
export function createReferenceDataApi(client: Pick<TenantClient, 'api'>) {
  // 经网关的 IAM 路由，与 `/iam/auth` 同源
  const base = '/iam/reference-data'
  const send = <T>(method: string, path: string, body?: unknown) =>
    client.api<T>(`${base}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  const items = (dictionary: Dictionary) =>
    `/dictionaries/${encodeURIComponent(dictionary.service)}/${encodeURIComponent(dictionary.code)}/items`
  const item = (dictionary: Dictionary, code: string) =>
    `${items(dictionary)}/${encodeURIComponent(code)}`
  const parameter = (target: Parameter) =>
    `/parameters/${encodeURIComponent(target.service)}/${encodeURIComponent(target.code)}`

  const history = (dictionary: Dictionary) =>
    `/dictionaries/${encodeURIComponent(dictionary.service)}/${encodeURIComponent(dictionary.code)}/history`
  return {
    history: (dictionary: Dictionary) =>
      send<DictionaryHistoryMetadata[]>('GET', history(dictionary)),
    historyVersion: (dictionary: Dictionary, version: number) =>
      send<DictionaryHistoryDetail>('GET', `${history(dictionary)}/${version}`),
    historyDiff: (dictionary: Dictionary, from: number, to: number) =>
      send<DictionaryHistoryDiff>('GET', `${history(dictionary)}/diff?from=${from}&to=${to}`),
    dictionaries: () => send<Dictionary[]>('GET', '/dictionaries'),
    /** @param code 不含 `custom.` 前缀 */
    createItem: (dictionary: Dictionary, code: string, name: string) =>
      send<DictionaryItem>('POST', items(dictionary), { code, name }),
    updateItem: (dictionary: Dictionary, code: string, name: string, sort: number) =>
      send<DictionaryItem>('PUT', item(dictionary, code), { name, sort }),
    disableItem: (dictionary: Dictionary, code: string) =>
      send<DictionaryItem>('POST', `${item(dictionary, code)}/disable`),
    enableItem: (dictionary: Dictionary, code: string) =>
      send<DictionaryItem>('POST', `${item(dictionary, code)}/enable`),
    parameters: () => send<Parameter[]>('GET', '/parameters'),
    override: (target: Parameter, value: ParameterValue) =>
      send<Parameter>('PUT', parameter(target), { value }),
    reset: (target: Parameter) => send<null>('DELETE', parameter(target)),
  }
}

export type ReferenceDataApi = ReturnType<typeof createReferenceDataApi>
