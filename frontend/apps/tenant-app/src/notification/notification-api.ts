import type { TenantClient } from '@crane199709/sdk'
export interface InboxItem {
  id: string
  title: string
  body: string
  read?: boolean
  occurredAt?: string
  publishedAt?: string
  link?: { appCode: string; route: string } | null
  subApp?: string | null
  route?: string | null
}
export type InboxTab = 'notifications' | 'announcements' | 'todos'
export function createNotificationApi(client: Pick<TenantClient, 'api' | 'page'>) {
  return {
    notifications: (page: number) =>
      client.page<InboxItem>(`/iam/notifications?page=${page}&size=20`),
    todos: (page: number) => client.page<InboxItem>(`/iam/todos?page=${page}&size=20`),
    announcements: () => client.api<InboxItem[]>('/iam/announcements'),
    read: (kind: 'notifications' | 'announcements', id: string) =>
      client.api<null>(`/iam/${kind}/${id}/read`, { method: 'POST' }),
    readAll: () => client.api<null>('/iam/notifications/read-all', { method: 'POST' }),
  }
}
