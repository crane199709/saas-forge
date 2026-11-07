import type { Page, PlatformClient } from '@crane199709/sdk'

export type DeadEventStatus = 'PENDING' | 'REPLAYED' | 'ABANDONED'

export interface DeadEvent {
  id: string
  consumerGroup: string
  eventId: string
  tenantId: string | null
  topic: string
  tag: string
  deliveryAttempt: number
  lastError: string
  status: DeadEventStatus
  createdAt: string
  resolvedAt: string | null
  resolutionReason: string | null
}

export interface OpsServiceStatus {
  service: string
  available: boolean
}

export function createDeadEventApi(client: Pick<PlatformClient, 'api' | 'page'>) {
  const base = '/platform/ops/services'
  const events = (service: string) => `${base}/${encodeURIComponent(service)}/dead-events`
  const post = (service: string, id: string, action: string, reason: string) =>
    client.api<DeadEvent>(`${events(service)}/${encodeURIComponent(id)}/${action}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason }),
    })
  return {
    services: () => client.api<OpsServiceStatus[]>(base),
    get: (service: string, id: string) =>
      client.api<DeadEvent>(`${events(service)}/${encodeURIComponent(id)}`),
    replay: (service: string, id: string, reason: string) => post(service, id, 'replay', reason),
    abandon: (service: string, id: string, reason: string) => post(service, id, 'abandon', reason),
    list: (service: string, status: DeadEventStatus, page: number): Promise<Page<DeadEvent>> =>
      client.page<DeadEvent>(`${events(service)}?status=${status}&page=${page}&size=20`),
  }
}

export type DeadEventApi = ReturnType<typeof createDeadEventApi>
