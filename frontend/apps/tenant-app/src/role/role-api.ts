import type { Page, TenantClient } from '@crane199709/sdk'
import type { ErrorTip } from '../error-tip'
import type { GrantablePermission, PermissionTree } from '@crane199709/ui'

// 授权树的模型与勾选组件共用 `@crane199709/ui`，平台侧 Plan 也使用同一套。
export type {
  GrantablePermission,
  PermissionMenu,
  PermissionTree,
  TreePermission,
} from '@crane199709/ui'
export { groupPermissions } from '@crane199709/ui'

/** IAM 角色接口的模型，由 tenant-app 维护，不属于通用 SDK。 */
export interface Role {
  id: string
  code: string
  name: string
  /** 预置角色（Owner、Tenant Admin）不可修改或删除，拥有全部有效权限码。 */
  builtin: boolean
  /** Owner 只能经转让变更，不在角色分配范围内。 */
  owner: boolean
  memberCount: number
  /** 授予的有效权限码；预置角色为空列表。 */
  permissions: string[]
  /** 各数据资源上的数据范围；预置角色对全部数据为「全部」，为空列表。 */
  dataPolicies: DataPolicy[]
  updatedAt?: string | null
  updatedBy?: string | null
}

/** Data Scope 预设；未配置的数据资源对持有者不可见。 */
export type DataScopeName = 'self' | 'department' | 'department_and_below' | 'all'

/** 预设与自定义条件二选一；`custom` 时 `condition` 为 JSON 表达式树。 */
export type DataPolicy =
  | { resource: string; scope: DataScopeName }
  | { resource: string; scope: 'custom'; condition: ConditionJson }

/** 后端 ABAC 表达式树（ADR-0006）：逻辑节点或比较节点。 */
export type ConditionJson =
  | { and: ConditionJson[] }
  | { or: ConditionJson[] }
  | { not: ConditionJson }
  | { attr: string; op: string; value?: unknown }

export type ConditionAttributeType = 'string' | 'number' | 'boolean' | 'uuid' | 'ltree'

/** 条件可引用的属性；平台提供的主体与环境属性没有 `label`，按全名翻译。 */
export interface ConditionAttribute {
  name: string
  label: string | null
  type: ConditionAttributeType
  multiValued: boolean
}

/** 业务服务在资源清单中声明的数据资源，`scopes` 是它支持的预设，`attributes` 是自定义条件可引用的资源属性。 */
export interface DataResource {
  code: string
  name: string
  service: string
  scopes: DataScopeName[]
  attributes: ConditionAttribute[]
}

export interface ValueOption {
  value: string
  label: string
}

/** 与数据资源无关的条件编辑选项：主体属性（含扩展属性）、环境属性与部门、岗位取值。 */
export interface ConditionOptions {
  subject: ConditionAttribute[]
  environment: ConditionAttribute[]
  departments: ValueOption[]
  positions: ValueOption[]
}

/** 预览结果：`matches` 与样例数据逐条对应，`context` 是求值时该成员的主体与环境属性。 */
export interface PolicyPreview {
  context: Record<string, unknown>
  matches: boolean[]
}

export interface MemberRoles {
  membershipId: string
  account: string
  displayName: string
  status: string
  roleIds: string[]
}

export interface AffectedMember {
  membershipId: string
  account: string
  displayName: string
}

/** 替换权限码前的净影响；`members` 只含前 20 名。 */
export interface RolePermissionImpact {
  /** 净失去至少一个权限码的成员数，0 表示没有影响。 */
  affectedCount: number
  members: AffectedMember[]
  lostPermissions: string[]
}

/** 替换数据范围前的影响：只列出持有该角色的成员，不判断具体数据行的变化。 */
export interface RoleDataPolicyImpact {
  affectedCount: number
  members: AffectedMember[]
}

/** 一次影响预览的结果；预览失败不等于没有影响，必须与 `affectedCount` 为 0 区分开。 */
export type ImpactPreview<T> = { ok: true; value: T } | { ok: false; tip: ErrorTip }

/** 角色管理接口；需要 `iam:role:manage`，其他成员得到 403。 */
export function createRoleApi(client: Pick<TenantClient, 'api' | 'page'>) {
  // 经网关的 IAM 路由，与 `/iam/auth` 同源
  const base = '/iam/roles'
  const send = <T>(method: string, path: string, body?: unknown) =>
    client.api<T>(`${base}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })

  return {
    roles: (deleted = false) => send<Role[]>('GET', deleted ? '?deleted=true' : ''),
    restoreRole: (id: string) => send<null>('POST', `/${id}/restore`),
    permissions: () => send<GrantablePermission[]>('GET', '/permissions'),
    permissionTree: () => send<PermissionTree>('GET', '/permission-tree'),
    dataResources: () => send<DataResource[]>('GET', '/data-resources'),
    conditionOptions: () => send<ConditionOptions>('GET', '/condition-options'),
    preview: (policy: DataPolicy, membershipId: string, samples: Record<string, unknown>[]) =>
      send<PolicyPreview>('POST', '/data-policies/preview', { policy, membershipId, samples }),
    createRole: (code: string, name: string, permissions: string[], dataPolicies: DataPolicy[]) =>
      send<Role>('POST', '', { code, name, permissions, dataPolicies }),
    renameRole: (id: string, name: string) => send<null>('PUT', `/${id}`, { name }),
    replacePermissions: (id: string, permissions: string[]) =>
      send<null>('PUT', `/${id}/permissions`, { permissions }),
    replaceDataPolicies: (id: string, policies: DataPolicy[]) =>
      send<null>('PUT', `/${id}/data-policies`, { policies }),
    permissionImpact: (id: string, permissions: string[]) =>
      send<RolePermissionImpact>('POST', `/${id}/permissions/impact`, { permissions }),
    dataPolicyImpact: (id: string, policies: DataPolicy[]) =>
      send<RoleDataPolicyImpact>('POST', `/${id}/data-policies/impact`, { policies }),
    deleteRole: (id: string) => send<null>('DELETE', `/${id}`),
    members: (page: number, size: number): Promise<Page<MemberRoles>> =>
      client.page<MemberRoles>(`${base}/memberships?page=${page}&size=${size}`),
    assign: (membershipId: string, roleIds: string[]) =>
      send<null>('PUT', `/memberships/${membershipId}`, { roleIds }),
  }
}

export type RoleApi = ReturnType<typeof createRoleApi>
