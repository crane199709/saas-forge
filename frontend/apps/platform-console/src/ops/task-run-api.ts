import type { PlatformClient } from '@crane199709/sdk'
import type { OpsServiceStatus } from './dead-event-api'

export interface TaskRun {
  id: string
  name: string
  type: 'GLOBAL' | 'TENANT'
  trigger: 'SCHEDULED' | 'MANUAL'
  operatorId: string | null
  targetTenantId: string | null
  status: 'RUNNING' | 'SUCCEEDED' | 'PARTIALLY_FAILED' | 'FAILED' | 'INTERRUPTED'
  startedAt: string
  finishedAt: string | null
  successCount: number
  failureCount: number
  failedTenants: { tenantId: string; errorSummary: string }[]
  errorSummary: string | null
}

export interface TaskDefinition {
  name: string
  type: TaskRun['type']
  cron: string | null
  fixedDelay: string | null
  zone: string | null
  latestRun: TaskRun | null
}

export type RunView = 'recent' | 'problems'

export function createTaskRunApi(client: Pick<PlatformClient, 'api'>) {
  const base = '/platform/ops/services'
  const tasks = (service: string) => `${base}/${encodeURIComponent(service)}/tasks`
  return {
    trigger: (service: string, name: string, targetTenantId?: string) =>
      client.api<{ runId: string }>(`${tasks(service)}/${encodeURIComponent(name)}/trigger`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(targetTenantId ? { targetTenantId } : {}),
      }),
    services: () => client.api<OpsServiceStatus[]>(base),
    list: (service: string) => client.api<TaskDefinition[]>(tasks(service)),
    runs: (service: string, name: string, view: RunView) =>
      client.api<TaskRun[]>(`${tasks(service)}/${encodeURIComponent(name)}/runs?view=${view}`),
  }
}

export type TaskRunApi = ReturnType<typeof createTaskRunApi>
