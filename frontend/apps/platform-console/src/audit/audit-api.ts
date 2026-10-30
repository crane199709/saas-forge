import type { Page, PlatformClient } from '@forge/sdk'

/** iam 平台审计接口返回的一条 Platform Realm Audit Event，由 platform-console 维护，不属于通用 SDK。 */
export interface AuditEvent {
  id: string
  occurredAt: string
  category: 'login' | 'permission' | 'operation'
  action: string
  actorId: string | null
  /** 操作人未知或已删除时为 null。 */
  actorAccount: string | null
  actorName: string | null
  resourceType: string | null
  resourceId: string | null
  outcome: 'success' | 'failure'
  errorCode: string | null
  detail: Record<string, unknown>
  service: string
  traceId: string | null
}

export interface AuditQuery {
  page: number
  size: number
  /** ISO 8601 时间，含。 */
  from?: string
  /** ISO 8601 时间，不含。 */
  to?: string
  /** 操作人账号或显示名中包含的文字。 */
  actor?: string
  action?: string
  resourceType?: string
  resourceId?: string
}

/** Platform Realm 审计查询，需要 `platform:audit:read`；不含任何租户的审计。 */
export function createAuditApi(client: Pick<PlatformClient, 'page'>) {
  return {
    events: (query: AuditQuery): Promise<Page<AuditEvent>> => {
      const params = new URLSearchParams()
      for (const [key, value] of Object.entries(query)) {
        if (value !== undefined && value !== '') params.set(key, String(value))
      }
      return client.page<AuditEvent>(`/iam/platform/audit/events?${params}`)
    },
  }
}

export type AuditApi = ReturnType<typeof createAuditApi>
