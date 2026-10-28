import type { Page, TenantClient } from '@forge/sdk'

export type MemberStatus = 'invited' | 'active' | 'suspended' | 'left'

/** IAM 成员管理接口的模型，由 tenant-app 维护，不属于通用 SDK。 */
export interface Member {
  membershipId: string
  /** 被邀请人尚未设置账号时为 null。 */
  account: string | null
  displayName: string
  /** Identity 的联系方式只读展示：租户管理员不能修改全局凭证。 */
  email: string | null
  phone: string | null
  status: MemberStatus
  owner: boolean
}

export interface MemberQuery {
  page: number
  size: number
  keyword?: string
  status?: MemberStatus
}

export interface InviteInput {
  /** 邮箱或手机号。 */
  contact: string
  /** 预设角色，需要 `iam:role:manage`。 */
  roleIds: string[]
  /** 预设主部门，需要 `iam:org:manage`。 */
  primaryDepartmentId: string | null
}

/** 成员管理接口；除主动退出外需要 `iam:member:manage`，转让需要当前成员是 Owner。 */
export function createMemberApi(client: Pick<TenantClient, 'api' | 'page'>) {
  // 经网关的 IAM 路由，与 `/iam/auth` 同源
  const base = '/iam/members'
  const send = <T>(method: string, path: string, body?: unknown) =>
    client.api<T>(`${base}${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })

  return {
    members: (query: MemberQuery): Promise<Page<Member>> => {
      const params = new URLSearchParams({ page: String(query.page), size: String(query.size) })
      if (query.keyword) params.set('keyword', query.keyword)
      if (query.status) params.set('status', query.status)
      return client.page<Member>(`${base}?${params}`)
    },
    invite: (input: InviteInput) =>
      send<{ membershipId: string; status: MemberStatus }>('POST', '/invitations', input),
    suspend: (membershipId: string) => send<null>('POST', `/${membershipId}/suspend`),
    resume: (membershipId: string) => send<null>('POST', `/${membershipId}/resume`),
    remove: (membershipId: string) => send<null>('DELETE', `/${membershipId}`),
    leave: () => send<null>('POST', '/me/leave'),
    transferOwner: (membershipId: string) => send<null>('PUT', '/owner', { membershipId }),
  }
}

export type MemberApi = ReturnType<typeof createMemberApi>
