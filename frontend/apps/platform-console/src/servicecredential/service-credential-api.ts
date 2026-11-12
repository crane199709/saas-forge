import type { PlatformClient } from '@crane199709/sdk'
export interface ServiceCredential {
  id: string
  service: string
  status: 'active' | 'disabled'
  fingerprint: string
  createdAt: string
  lastUsedAt: string | null
}
export interface IssuedServiceCredential {
  credential: ServiceCredential
  token: string
  manifestReported: boolean
}
export function createServiceCredentialApi(client: Pick<PlatformClient, 'api'>) {
  const base = '/platform/service-credentials'
  return {
    list: () =>
      client.api<{ service: string; available: boolean; credentials: ServiceCredential[] }>(base),
    create: (service: string) =>
      client.api<IssuedServiceCredential>(base, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ service }),
      }),
    disable: (id: string) =>
      client.api<void>(`${base}/${encodeURIComponent(id)}/disable`, { method: 'POST' }),
  }
}
export type ServiceCredentialApi = ReturnType<typeof createServiceCredentialApi>
