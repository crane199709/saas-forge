import type { PlatformClient } from '@crane199709/sdk'
export interface Announcement {
  id: string
  title: string
  body: string
  subApp: string | null
  route: string | null
  expiresAt: string | null
  publishedAt: string
  revoked: boolean
  allTenants: boolean
  tenantIds: string[]
}
export interface PublishAnnouncement {
  title: string
  body: string
  subApp: string | null
  route: string | null
  expiresAt: string | null
  allTenants: boolean
  tenantIds: string[]
}
export function createAnnouncementApi(client: Pick<PlatformClient, 'api'>) {
  const base = '/iam/platform/announcements'
  return {
    list: () => client.api<Announcement[]>(base),
    publish: (input: PublishAnnouncement) =>
      client.api<Announcement>(base, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
      }),
    revoke: (id: string) => client.api<null>(`${base}/${id}/revoke`, { method: 'POST' }),
  }
}
