import {
  inject,
  onMounted,
  onUnmounted,
  readonly,
  ref,
  computed,
  watch,
  type InjectionKey,
} from 'vue'
import { useAccessControl, type TenantClient } from '@crane199709/sdk'

export function useUnreadCount(client: TenantClient) {
  const access = useAccessControl()
  const visible = computed(() =>
    access.has(['notification:read', 'announcement:read', 'todo:read']),
  )
  let permissionsReady = true
  const count = ref<number | null>(null)
  const failed = ref(false)
  let generation = 0
  let mounted = false
  let timer: ReturnType<typeof setInterval> | undefined
  let unsubscribeIp: (() => void) | undefined
  let unsubscribe: (() => void) | undefined
  let membership = client.session()?.membershipId
  async function refresh() {
    if (
      !mounted ||
      !permissionsReady ||
      !visible.value ||
      !client.session() ||
      client.ipDenial() ||
      document.visibilityState === 'hidden'
    )
      return
    const started = ++generation
    try {
      const [notifications, announcements] = await Promise.all([
        access.has('notification:read')
          ? client.api<{ count: number }>('/iam/notifications/unread-count')
          : Promise.resolve({ count: 0 }),
        access.has('announcement:read')
          ? client.api<{ count: number }>('/iam/announcements/unread-count')
          : Promise.resolve({ count: 0 }),
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
    if (
      !mounted ||
      !permissionsReady ||
      !visible.value ||
      !client.session() ||
      client.ipDenial() ||
      document.visibilityState === 'hidden'
    )
      return
    void refresh()
    timer = setInterval(() => void refresh(), 60_000)
  }
  watch(
    () => [
      access.has('notification:read'),
      access.has('announcement:read'),
      access.has('todo:read'),
    ],
    () => {
      permissionsReady = true
      count.value = null
      failed.value = false
      resume()
    },
  )
  onMounted(() => {
    mounted = true
    unsubscribeIp = client.onIpNotAllowed(stop)
    document.addEventListener('visibilitychange', resume)
    unsubscribe = client.onSessionChange((session) => {
      if (membership === session?.membershipId) return
      membership = session?.membershipId
      permissionsReady = false
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
    unsubscribeIp?.()
    document.removeEventListener('visibilitychange', resume)
  })
  return { count: readonly(count), failed: readonly(failed), visible, refresh }
}
export const unreadCountKey: InjectionKey<ReturnType<typeof useUnreadCount>> =
  Symbol('inbox.unread')
export function useInboxUnread() {
  const unread = inject(unreadCountKey)
  if (!unread) throw new Error('Shell must provide unread count')
  return unread
}
