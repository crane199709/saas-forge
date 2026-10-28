import type { Page, TenantClient } from '@forge/sdk'

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
}

export interface GrantablePermission {
  code: string
  name: string
  service: string
  group: string | null
}

export interface PermissionGroup {
  key: string
  service: string
  group: string | null
  permissions: GrantablePermission[]
}

export interface MemberRoles {
  membershipId: string
  account: string
  displayName: string
  status: string
  roleIds: string[]
}

/** 接口已按服务、分组排序，这里按相邻的服务与分组归并，供权限勾选界面分块展示。 */
export function groupPermissions(permissions: GrantablePermission[]): PermissionGroup[] {
  const groups: PermissionGroup[] = []
  for (const permission of permissions) {
    const key = `${permission.service}\u0000${permission.group ?? ''}`
    const last = groups.at(-1)
    if (last?.key === key) last.permissions.push(permission)
    else
      groups.push({
        key,
        service: permission.service,
        group: permission.group,
        permissions: [permission],
      })
  }
  return groups
}

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
    roles: () => send<Role[]>('GET', ''),
    permissions: () => send<GrantablePermission[]>('GET', '/permissions'),
    createRole: (code: string, name: string, permissions: string[]) =>
      send<Role>('POST', '', { code, name, permissions }),
    renameRole: (id: string, name: string) => send<null>('PUT', `/${id}`, { name }),
    replacePermissions: (id: string, permissions: string[]) =>
      send<null>('PUT', `/${id}/permissions`, { permissions }),
    deleteRole: (id: string) => send<null>('DELETE', `/${id}`),
    members: (page: number, size: number): Promise<Page<MemberRoles>> =>
      client.page<MemberRoles>(`${base}/memberships?page=${page}&size=${size}`),
    assign: (membershipId: string, roleIds: string[]) =>
      send<null>('PUT', `/memberships/${membershipId}`, { roleIds }),
  }
}

export type RoleApi = ReturnType<typeof createRoleApi>
