import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { mountShell } from '../test-shell'

enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
const notice = {
  id: 'n1',
  title: '订单已创建',
  body: '<script>纯文本</script>',
  read: false,
  link: { appCode: 'demo', route: '/overview' },
}
function ok(data: unknown, page?: unknown) {
  return new Response(
    JSON.stringify({
      code: 'ok',
      httpStatus: 200,
      data,
      meta: page ? { page } : undefined,
      traceId: 'center',
    }),
  )
}
it('成员可切换通知、公告和待办，按公开分页读取、标记已读并进入业务路由', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', membershipId: 'm' }),
  )
  let read = false
  const requests: string[] = []
  vi.stubGlobal('fetch', async (input: string, init?: RequestInit) => {
    const url = new URL(input, location.origin)
    url.pathname = url.pathname.replace(/^\/api(?=\/)/, '')
    requests.push(`${init?.method ?? 'GET'} ${url.pathname}${url.search}`)
    if (url.pathname.endsWith('/unread-count')) return ok({ count: read ? 0 : 1 })
    if (url.pathname === '/iam/notifications/n1/read') {
      read = true
      return ok(null)
    }
    if (url.pathname === '/iam/notifications')
      return ok([{ ...notice, read }], {
        number: Number(url.searchParams.get('page')),
        size: 20,
        totalElements: 21,
        totalPages: 2,
      })
    if (url.pathname === '/iam/announcements')
      return ok([
        { id: 'a1', title: '租户公告', body: '公告正文', read: false, subApp: null, route: null },
      ])
    if (url.pathname === '/iam/todos')
      return ok([{ ...notice, id: 't1', title: '处理订单' }], {
        number: 1,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      })
    return ok([])
  })
  const { wrapper, router } = await mountShell('/notifications')
  router.addRoute({ path: '/apps/demo/overview', component: { template: '<p>业务订单页</p>' } })
  expect(wrapper.text()).toContain('通知中心')
  expect(wrapper.text()).toContain('<script>纯文本</script>')
  expect(wrapper.find('script').exists()).toBe(false)
  await wrapper.get('[data-testid="mark-read-n1"]').trigger('click')
  await flushPromises()
  expect(requests).toContain('POST /iam/notifications/n1/read')
  expect(wrapper.text()).toContain('已读')
  await wrapper.get('.el-pagination button.btn-next').trigger('click')
  await flushPromises()
  expect(requests).toContain('GET /iam/notifications?page=2&size=20')
  await wrapper.get('[data-testid="tab-announcements"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('租户公告')
  await wrapper.get('[data-testid="tab-todos"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('处理订单')
  await wrapper.get('[data-testid="open-t1"]').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/apps/demo/overview')
})

it('切换列表后旧通知慢响应不能覆盖当前待办', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', membershipId: 'm' }),
  )
  let release: (value: Response) => void = () => {}
  const pending = new Promise<Response>((resolve) => {
    release = resolve
  })
  vi.stubGlobal('fetch', async (input: string) => {
    const path = new URL(input, location.origin).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/iam/notifications') return pending
    if (path === '/iam/todos')
      return ok([{ id: 'todo-current', title: '当前待办', body: '业务正文' }], {
        number: 1,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      })
    if (path.endsWith('/unread-count')) return ok({ count: 0 })
    return ok([])
  })
  const { wrapper } = await mountShell('/notifications')
  await wrapper.get('[data-testid="tab-todos"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('当前待办')
  release(
    ok([{ ...notice, title: '旧通知' }], { number: 1, size: 20, totalElements: 1, totalPages: 1 }),
  )
  await flushPromises()
  expect(wrapper.text()).toContain('当前待办')
  expect(wrapper.text()).not.toContain('旧通知')
})

it('点击整行展开全文，操作按钮不触发展开', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', membershipId: 'm' }),
  )
  vi.stubGlobal('fetch', async (input: string) => {
    const path = new URL(input, location.origin).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/iam/notifications')
      return ok([notice], { number: 1, size: 20, totalElements: 1, totalPages: 1 })
    if (path.endsWith('/unread-count')) return ok({ count: 1 })
    return ok([])
  })
  const { wrapper } = await mountShell('/notifications')
  const row = () => wrapper.get('[data-testid="inbox-n1"]')
  const title = () => row().get('button.title').attributes('aria-expanded')
  expect(row().text()).toContain('未读')
  expect(title()).toBe('false')

  await row().trigger('click')
  expect(title()).toBe('true')

  await wrapper.get('[data-testid="mark-read-n1"]').trigger('click')
  await flushPromises()
  expect(title()).toBe('true')

  await row().trigger('click')
  expect(title()).toBe('false')
})
