import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { mountShell } from '../test-shell'

enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
function response(data: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 'bell' }))
}
function setup() {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'refresh', membershipId: 'a' }),
  )
  let notifications = 2
  let announcements = 3
  const reads: string[] = []
  vi.stubGlobal('fetch', async (input: string) => {
    const path = new URL(input, location.origin).pathname
    reads.push(path)
    if (path === '/iam/notifications/unread-count') return response({ count: notifications })
    if (path === '/iam/announcements/unread-count') return response({ count: announcements })
    return response([])
  })
  return {
    reads,
    change: () => {
      notifications = 1
      announcements = 0
    },
  }
}
it('铃铛合计通知和可见公告，每60秒刷新，隐藏暂停并在恢复可见时立即读取', async () => {
  vi.useFakeTimers()
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  const fixture = setup()
  const { wrapper } = await mountShell('/')
  expect(wrapper.get('[data-testid="notification-bell"]').text()).toContain('5')
  fixture.change()
  await vi.advanceTimersByTimeAsync(59_999)
  await flushPromises()
  expect(wrapper.get('[data-testid="notification-bell"]').text()).toContain('5')
  await vi.advanceTimersByTimeAsync(1)
  await flushPromises()
  expect(wrapper.get('[data-testid="notification-bell"]').text()).toContain('1')
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
  document.dispatchEvent(new Event('visibilitychange'))
  const count = fixture.reads.length
  await vi.advanceTimersByTimeAsync(180_000)
  await flushPromises()
  expect(fixture.reads).toHaveLength(count)
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  document.dispatchEvent(new Event('visibilitychange'))
  await flushPromises()
  expect(fixture.reads.length).toBeGreaterThan(count)
  wrapper.unmount()
  const stopped = fixture.reads.length
  await vi.advanceTimersByTimeAsync(120_000)
  document.dispatchEvent(new Event('visibilitychange'))
  await flushPromises()
  expect(fixture.reads).toHaveLength(stopped)
})

it('更晚的可见页面读取生效，隐藏前慢响应和退出后的结果不能覆盖当前铃铛', async () => {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  setup()
  let release: (value: Response) => void = () => {}
  const pending = new Promise<Response>((resolve) => {
    release = resolve
  })
  let first = true
  vi.stubGlobal('fetch', async (input: string) => {
    const path = new URL(input, location.origin).pathname
    if (path === '/iam/notifications/unread-count') {
      if (first) {
        first = false
        return pending
      }
      return response({ count: 2 })
    }
    if (path === '/iam/announcements/unread-count') return response({ count: 0 })
    return response([])
  })
  const { wrapper, client } = await mountShell('/')
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
  document.dispatchEvent(new Event('visibilitychange'))
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  document.dispatchEvent(new Event('visibilitychange'))
  await flushPromises()
  expect(wrapper.get('[data-testid="notification-bell"]').text()).toContain('2')
  release(response({ count: 99 }))
  await flushPromises()
  expect(wrapper.get('[data-testid="notification-bell"]').text()).toContain('2')
  client.clear()
  await flushPromises()
  expect(wrapper.find('[data-testid="notification-bell"] .unread-count').exists()).toBe(false)
})
