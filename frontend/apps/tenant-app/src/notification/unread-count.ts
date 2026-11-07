import { inject, onMounted, onUnmounted, readonly, ref, type InjectionKey } from 'vue'
import type { TenantClient } from '@crane199709/sdk'

export function useUnreadCount(client: TenantClient) {
  const count = ref<number | null>(null)
  const failed = ref(false)
  let generation = 0
  let mounted = false
  let timer: ReturnType<typeof setInterval> | undefined
  let unsubscribe: (() => void) | undefined
  let membership = client.session()?.membershipId
  async function refresh() {
    if (!mounted || !client.session() || document.visibilityState === 'hidden') return
    const started = ++generation
    try {
      const [notifications, announcements] = await Promise.all([
        client.api<{ count: number }>('/iam/notifications/unread-count'),
        client.api<{ count: number }>('/iam/announcements/unread-count'),
      ])
      if (started !== generation || !mounted) return
      count.value = notifications.count + announcements.count
      failed.value = false
    } catch {
      if (started === generation && mounted) failed.value = true
    }
  }
  function stop() {
    generation += 1
    clearInterval(timer)
    timer = undefined
  }
  function resume() {
    stop()
    if (!mounted || !client.session() || document.visibilityState === 'hidden') return
    void refresh()
    timer = setInterval(() => void refresh(), 60_000)
  }
  onMounted(() => {
    mounted = true
    document.addEventListener('visibilitychange', resume)
    unsubscribe = client.onSessionChange((session) => {
      if (membership === session?.membershipId) return
      membership = session?.membershipId
      count.value = null
      failed.value = false
      resume()
    })
    resume()
  })
  onUnmounted(() => {
    mounted = false
    stop()
    unsubscribe?.()
    document.removeEventListener('visibilitychange', resume)
  })
  return { count: readonly(count), failed: readonly(failed), refresh }
}
export const unreadCountKey: InjectionKey<ReturnType<typeof useUnreadCount>> =
  Symbol('inbox.unread')
export function useInboxUnread() {
  const unread = inject(unreadCountKey)
  if (!unread) throw new Error('Shell must provide unread count')
  return unread
}
