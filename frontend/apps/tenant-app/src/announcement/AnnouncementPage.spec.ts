import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { mountShell } from '../test-shell'
enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  localStorage.clear()
  vi.unstubAllGlobals()
})
function ok(data: unknown) {
  return new Response(
    JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 'announcement' }),
  )
}
it('有权限时发布并撤回公告，管理历史保留正文和状态', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', membershipId: 'm' }),
  )
  let published = false,
    revoked = false
  const commands: unknown[] = []
  vi.stubGlobal('fetch', async (input: string, init?: RequestInit) => {
    const path = new URL(input, location.origin).pathname

    if (path.endsWith('/revoke')) {
      revoked = true
      return ok(null)
    }
    if (path === '/iam/announcements/manage') {
      if (init?.method === 'POST') {
        commands.push(JSON.parse(String(init.body)))
        published = true
        return ok({ id: 'a1' })
      }
      return ok(
        published
          ? [
              {
                id: 'a1',
                title: '维护窗口',
                body: '请提前保存',
                revoked,
                publishedAt: '2026-11-07T00:00:00Z',
                allTenants: true,
                tenantIds: [],
              },
            ]
          : [],
      )
    }
    if (path.endsWith('/unread-count')) return ok({ count: 0 })
    return ok([])
  })
  const { wrapper } = await mountShell('/announcements', {
    access: async () => ({ permissions: ['announcement:manage'] }),
  })
  await wrapper.get('[data-testid="announcement-create"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="announcement-title"]').setValue('维护窗口')
  await wrapper.get('[data-testid="announcement-body"]').setValue('请提前保存')
  await wrapper.get('[data-testid="announcement-publish"]').trigger('click')
  await flushPromises()
  expect(commands).toEqual([expect.objectContaining({ title: '维护窗口', body: '请提前保存' })])
  expect(wrapper.text()).toContain('维护窗口')
  await wrapper.get('[data-testid="announcement-revoke-a1"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="announcement-confirm-revoke"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('已撤回')
})
