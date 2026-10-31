import type { Page, TenantClient } from '@crane199709/sdk'

/** iam 审计查询接口返回的一条 Audit Event，由 tenant-app 维护，不属于通用 SDK。 */
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

/** 本租户审计查询，需要 `iam:audit:read`；只返回当前租户的事件。 */
export function createAuditApi(client: Pick<TenantClient, 'page'>) {
  return {
    events: (query: AuditQuery): Promise<Page<AuditEvent>> => {
      const params = new URLSearchParams()
      for (const [key, value] of Object.entries(query)) {
        if (value !== undefined && value !== '') params.set(key, String(value))
      }
      // 经网关的 IAM 路由，与 `/iam/auth` 同源
      return client.page<AuditEvent>(`/iam/audit/events?${params}`)
    },
  }
}

export type AuditApi = ReturnType<typeof createAuditApi>
