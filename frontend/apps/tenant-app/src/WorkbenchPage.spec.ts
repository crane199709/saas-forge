import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createAccessControl, createTenantClient, tenantClientKey } from '@crane199709/sdk'
import WorkbenchPage from './WorkbenchPage.vue'
import { i18n } from './i18n'

enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function ok(data: unknown, page?: { number: number; totalPages: number; totalElements: number }) {
  return new Response(
    JSON.stringify({
      code: 'ok',
      httpStatus: 200,
      data,
      ...(page ? { meta: { page: { size: 20, ...page } } } : {}),
    }),
  )
}

function items(prefix: string, count: number) {
  return Array.from({ length: count }, (_, index) => ({
    id: `${prefix}-${index + 1}`,
    title: `${prefix} ${index + 1}`,
    body: `${prefix}内容`,
    read: false,
    occurredAt: '2026-11-13T08:00:00Z',
    publishedAt: '2026-11-13T08:00:00Z',
    link: prefix === '待办' ? { appCode: 'orders', route: '/pending' } : null,
  }))
}

async function workbench(
  respond: (url: URL) => Response | Promise<Response>,
  permissions = ['todo:read', 'notification:read', 'announcement:read'],
) {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({
      accessToken: 'access-a',
      refreshToken: 'refresh-a',
      identityName: '陈晓',
      tenantId: 'tenant-a',
      tenantName: '青禾科技',
      membershipId: 'membership-a',
    }),
  )
  vi.stubGlobal('fetch', (input: string) => {
    const url = new URL(input, location.origin)
    url.pathname = url.pathname.replace(/^\/api(?=\/)/, '')
    if (url.pathname === '/iam/auth/access')
      return Promise.resolve(ok({ permissions, menus: [], subApps: [] }))
    return Promise.resolve(respond(url))
  })
  const client = createTenantClient(sessionStorage)
  const access = createAccessControl(client)
  await access.load()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<p />' } },
      { path: '/notifications', component: { template: '<p>通知中心</p>' } },
      { path: '/apps/orders/pending', component: { template: '<p>订单待办</p>' } },
    ],
  })
  await router.push('/')
  const wrapper = mount(WorkbenchPage, {
    global: {
      plugins: [i18n, ElementPlus, router, access],
      provide: { [tenantClientKey]: client },
    },
  })
  await flushPromises()
  return { wrapper, router, client, access }
}

it('工作台显示待办前五条、未读通知数量与前五条、最新三条公告，并提供对应入口', async () => {
  const { wrapper, router } = await workbench((url) => {
    if (url.pathname === '/iam/todos') {
      const todos = items('待办', 6)
      todos[4]!.link = null
      return ok(todos, { number: 1, totalPages: 1, totalElements: 6 })
    }
    if (url.pathname === '/iam/notifications')
      return ok(items('通知', 6), { number: 1, totalPages: 1, totalElements: 6 })
    if (url.pathname === '/iam/notifications/unread-count') return ok({ count: 7 })
    return ok(items('公告', 4))
  })

  expect(wrapper.get('[data-testid="workbench-greeting"]').text()).toBe('陈晓 · 青禾科技')
  const todos = wrapper.get('[data-testid="workbench-todos"]')
  const notifications = wrapper.get('[data-testid="workbench-notifications"]')
  const announcements = wrapper.get('[data-testid="workbench-announcements"]')
  expect(todos.findAll('.workbench-item')).toHaveLength(5)
  expect(todos.text()).toContain('待办 5')
  expect(todos.text()).not.toContain('待办 6')
  expect(todos.find('.workbench-item:last-child a').exists()).toBe(false)
  expect(todos.get('.workbench-item:last-child .workbench-item-title').text()).toBe('待办 5')
  expect(notifications.get('[aria-label="7 条未读通知"]').text()).toContain('7')
  expect(notifications.findAll('.workbench-item')).toHaveLength(5)
  expect(notifications.text()).not.toContain('通知 6')
  expect(announcements.findAll('.workbench-item')).toHaveLength(3)
  expect(announcements.text()).not.toContain('公告 4')

  await todos.get('a.workbench-item-title').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/apps/orders/pending')
  await notifications.get('a.workbench-item-title').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/notifications')
  await announcements.get('a.workbench-item-title').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.path).toBe('/notifications')
  expect(router.currentRoute.value.query).toEqual({ tab: 'announcements', item: '公告-1' })
})

it('缺少读取权限时隐藏整张卡片，也不发出该类数据请求', async () => {
  const requested: string[] = []
  const { wrapper, access } = await workbench(
    (url) => {
      requested.push(url.pathname)
      return ok(items('待办', 1), { number: 1, totalPages: 1, totalElements: 1 })
    },
    ['todo:read'],
  )

  expect(wrapper.get('[data-testid="workbench-todos"]').text()).toContain('待办 1')
  expect(wrapper.find('[data-testid="workbench-notifications"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="workbench-announcements"]').exists()).toBe(false)
  expect(requested).toEqual(['/iam/todos'])

  access.clear()
  await flushPromises()
  expect(wrapper.find('.workbench-card').exists()).toBe(false)
})

it('三张卡独立展示加载、失败与空状态，完成后可手动刷新恢复失败的卡片', async () => {
  let releaseTodo: (response: Response) => void = () => {}
  const pendingTodo = new Promise<Response>((resolve) => {
    releaseTodo = resolve
  })
  let recovered = false
  const { wrapper } = await workbench((url) => {
    if (url.pathname === '/iam/todos') return pendingTodo
    if (url.pathname === '/iam/notifications/unread-count') return ok({ count: 1 })
    if (url.pathname === '/iam/notifications')
      return recovered
        ? ok(items('恢复通知', 1), { number: 1, totalPages: 1, totalElements: 1 })
        : new Response(
            JSON.stringify({
              code: 'auth.forbidden',
              httpStatus: 403,
              userTip: '通知读取权限暂不可用',
            }),
            { status: 403 },
          )
    return ok([])
  })

  expect(wrapper.get('[data-testid="workbench-todos"] [role="status"]').text()).toBe('加载中…')
  expect(wrapper.get('[data-testid="workbench-notifications"]').text()).toContain(
    '通知读取权限暂不可用',
  )
  expect(wrapper.get('[data-testid="workbench-announcements"]').text()).toContain('暂无公告')
  expect(wrapper.get('[data-testid="workbench-refresh"]').attributes('disabled')).toBeDefined()

  releaseTodo(ok(items('待办', 1), { number: 1, totalPages: 1, totalElements: 1 }))
  await flushPromises()
  expect(wrapper.get('[data-testid="workbench-todos"]').text()).toContain('待办 1')
  recovered = true
  await wrapper.get('[data-testid="workbench-refresh"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-testid="workbench-notifications"]').text()).toContain('恢复通知 1')
  expect(wrapper.get('[data-testid="workbench-notifications"]').text()).not.toContain(
    '通知读取权限暂不可用',
  )
})

it('前页通知全部已读时继续读取后页，按现有列表顺序取到五条未读通知', async () => {
  const { wrapper } = await workbench(
    (url) => {
      if (url.pathname.endsWith('/unread-count')) return ok({ count: 6 })
      const page = Number(url.searchParams.get('page'))
      if (page === 1)
        return ok(
          items('已读通知', 20).map((item) => ({ ...item, read: true })),
          { number: 1, totalPages: 3, totalElements: 26 },
        )
      if (page === 2)
        return ok(items('第二页通知', 2), { number: 2, totalPages: 3, totalElements: 26 })
      return ok(items('第三页通知', 4), { number: 3, totalPages: 3, totalElements: 26 })
    },
    ['notification:read'],
  )

  expect(wrapper.get('[aria-label="6 条未读通知"]').text()).toBe('6')
  expect(wrapper.findAll('.workbench-item-title').map((item) => item.text())).toEqual([
    '第二页通知 1',
    '第二页通知 2',
    '第三页通知 1',
    '第三页通知 2',
    '第三页通知 3',
  ])
  expect(wrapper.text()).not.toContain('已读通知 1')
})

it('切换租户立即清空旧卡片，加载新租户并忽略旧租户的慢响应', async () => {
  let releaseOldTodo: (response: Response) => void = () => {}
  const oldTodo = new Promise<Response>((resolve) => {
    releaseOldTodo = resolve
  })
  let releaseNewAnnouncement: (response: Response) => void = () => {}
  const newAnnouncement = new Promise<Response>((resolve) => {
    releaseNewAnnouncement = resolve
  })
  let tenant = 'a'
  const { wrapper, client } = await workbench((url) => {
    if (url.pathname === '/iam/auth/switch') {
      tenant = 'b'
      return ok({
        accessToken: 'access-b',
        refreshToken: 'refresh-b',
        identityName: '陈晓',
        tenantId: 'tenant-b',
        tenantName: '远山工作室',
        membershipId: 'membership-b',
      })
    }
    if (url.pathname === '/iam/todos')
      return tenant === 'a'
        ? oldTodo
        : ok(items('新租户待办', 1), { number: 1, totalPages: 1, totalElements: 1 })
    if (url.pathname === '/iam/announcements')
      return tenant === 'a' ? ok(items('旧租户公告', 1)) : newAnnouncement
    if (url.pathname.endsWith('/unread-count')) return ok({ count: 0 })
    return ok([], { number: 1, totalPages: 0, totalElements: 0 })
  })
  expect(wrapper.text()).toContain('旧租户公告 1')

  await client.switchTenant('membership-b')
  await flushPromises()
  expect(wrapper.get('[data-testid="workbench-greeting"]').text()).toBe('陈晓 · 远山工作室')
  expect(wrapper.text()).not.toContain('旧租户公告 1')
  expect(wrapper.text()).toContain('新租户待办 1')

  releaseOldTodo(ok(items('旧租户待办', 1), { number: 1, totalPages: 1, totalElements: 1 }))
  releaseNewAnnouncement(ok(items('新租户公告', 1)))
  await flushPromises()
  expect(wrapper.text()).toContain('新租户待办 1')
  expect(wrapper.text()).toContain('新租户公告 1')
  expect(wrapper.text()).not.toContain('旧租户待办 1')
  expect(wrapper.find('.el-alert').exists()).toBe(false)
})
