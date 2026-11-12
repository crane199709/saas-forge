import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import { ok, signedIn } from '../test-console'
import DeadEventPanel from './DeadEventPanel.vue'
import { createDeadEventApi } from './dead-event-api'

const event = {
  id: 'd1',
  consumerGroup: 'orders-group',
  eventId: 'event-1',
  tenantId: 'tenant-1',
  topic: 'order-created',
  tag: 'created',
  deliveryAttempt: 16,
  lastError: '库存服务超时',
  status: 'PENDING',
  createdAt: '2026-11-07T03:00:00Z',
  resolvedAt: null,
  resolutionReason: null,
}

function events(items: unknown[], number = 1, totalElements = items.length) {
  return new Response(
    JSON.stringify({
      code: 'ok',
      httpStatus: 200,
      data: items,
      traceId: 't',
      meta: {
        page: { number, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) },
      },
    }),
  )
}

function mountPanel(canManage = true) {
  signedIn()
  return mount(DeadEventPanel, {
    props: { api: createDeadEventApi(createPlatformClient(localStorage)), canManage },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}

enableAutoUnmount(afterEach)
afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

it('按服务读取待处置分页元数据，展示错误与次数但不展示消息体', async () => {
  const fetcher = vi.fn((url: string) =>
    Promise.resolve(
      new URL(url, location.origin).pathname.replace(/^\/api(?=\/)/, '') ===
        '/platform/ops/services'
        ? ok([{ service: 'orders', available: true }])
        : events([{ ...event, payload: 'secret-message-body' }], 1, 21),
    ),
  )
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()

  expect(wrapper.text()).toContain('order-created')
  expect(wrapper.text()).toContain('created')
  expect(wrapper.text()).toContain('tenant-1')
  expect(wrapper.text()).toContain('库存服务超时')
  expect(wrapper.text()).toContain('16')
  expect(wrapper.text()).toMatch(/2026\/11\/\d+ \d+:\d{2}:\d{2}/)
  expect(wrapper.text()).not.toContain('secret-message-body')
  expect(wrapper.get('[data-testid="list-page-total"]').text()).toContain('21')
  const query = new URL(fetcher.mock.calls[1]![0], location.origin)
  query.pathname = query.pathname.replace(/^\/api(?=\/)/, '')
  expect(query.pathname).toBe('/platform/ops/services/orders/dead-events')
  expect(Object.fromEntries(query.searchParams)).toEqual({
    status: 'PENDING',
    page: '1',
    size: '20',
  })
})

async function choose(wrapper: ReturnType<typeof mountPanel>, id: string, text: string) {
  await wrapper.get(`#${id}`).trigger('click')
  await flushPromises()
  const option = Array.from(
    document.querySelectorAll<HTMLElement>('.el-select-dropdown__item'),
  ).find((item) => item.textContent?.trim() === text)
  expect(option, `option ${text}`).toBeDefined()
  option!.click()
  await flushPromises()
}

it('切换服务或状态重置分页，服务不可用仍可切回其他服务', async () => {
  const fetcher = vi.fn((url: string) => {
    const request = new URL(url, location.origin)
    request.pathname = request.pathname.replace(/^\/api(?=\/)/, '')
    return Promise.resolve(
      request.pathname === '/platform/ops/services'
        ? ok([
            { service: 'orders', available: true },
            { service: 'billing', available: false },
          ])
        : events(
            [
              {
                ...event,
                topic: `${request.searchParams.get('status')}-${request.searchParams.get('page')}`,
              },
            ],
            Number(request.searchParams.get('page')),
            21,
          ),
    )
  })
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('button.btn-next').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('PENDING-2')

  await choose(wrapper, 'dead-event-status', '已重放')
  expect(wrapper.text()).toContain('REPLAYED-1')
  await choose(wrapper, 'dead-event-service', 'billing（不可用）')
  expect(wrapper.get('[role="alert"]').text()).toContain('billing')
  expect(wrapper.text()).not.toContain('REPLAYED-1')
  await choose(wrapper, 'dead-event-service', 'orders')
  expect(wrapper.text()).toContain('REPLAYED-1')
  expect(wrapper.find('[role="alert"]').exists()).toBe(false)
})

function button(text: string, root: ParentNode = document.body) {
  const result = Array.from(root.querySelectorAll<HTMLButtonElement>('button')).find(
    (item) => item.textContent?.trim() === text,
  )
  expect(result, `button ${text}`).toBeDefined()
  return result!
}

function fail(code: string, userTip: string, status = 409) {
  return new Response(
    JSON.stringify({ code, httpStatus: status, userTip, traceId: 'dead-trace' }),
    { status },
  )
}

it('重放必填原因，业务失败保留原因并允许重试，成功后刷新待处置列表', async () => {
  let replayed = false
  let attempts = 0
  const fetcher = vi.fn((url: string, init?: RequestInit) => {
    const path = new URL(url, location.origin).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/platform/ops/services')
      return Promise.resolve(ok([{ service: 'orders', available: true }]))
    if (path.endsWith('/replay')) {
      expect(init?.method).toBe('POST')
      expect(JSON.parse(init?.body as string)).toEqual({ reason: '已修复库存服务' })
      attempts++
      if (attempts === 1)
        return Promise.resolve(fail('ops.deadEventReplayFailed', '重放仍失败，请检查服务'))
      replayed = true
      return Promise.resolve(
        ok({
          ...event,
          status: 'REPLAYED',
          resolutionReason: '已修复库存服务',
          resolvedAt: '2026-11-07T04:00:00Z',
        }),
      )
    }
    return Promise.resolve(events(replayed ? [] : [event]))
  })
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  button('重放', wrapper.element).click()
  await flushPromises()
  const dialog = Array.from(document.querySelectorAll('[role="dialog"]')).find((item) =>
    item.textContent?.includes('确认重放'),
  )!
  expect(button('确认重放', dialog).disabled).toBe(true)
  const reason = dialog.querySelector<HTMLTextAreaElement>('textarea')!
  reason.value = '   '
  reason.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  expect(button('确认重放', dialog).disabled).toBe(true)
  reason.value = ' 已修复库存服务 '
  reason.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  button('确认重放', dialog).click()
  await flushPromises()
  expect(dialog.textContent).toContain('重放仍失败，请检查服务')
  expect(dialog.textContent).toContain('dead-trace')
  expect(reason.value).toBe(' 已修复库存服务 ')
  button('确认重放', dialog).click()
  await flushPromises()
  expect(wrapper.text()).toContain('已重放该死信事件')
  expect(wrapper.text()).not.toContain('order-created')
})

it('查看最新元数据，再填写原因放弃，已处置记录只保留查看', async () => {
  let abandoned = false
  const fetcher = vi.fn((url: string, init?: RequestInit) => {
    const path = new URL(url, location.origin).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/platform/ops/services')
      return Promise.resolve(ok([{ service: 'orders', available: true }]))
    if (path.endsWith('/abandon')) {
      expect(init?.method).toBe('POST')
      expect(JSON.parse(init?.body as string)).toEqual({ reason: '该事件已人工补偿' })
      abandoned = true
      return Promise.resolve(ok({ ...event, status: 'ABANDONED' }))
    }
    if (path.endsWith('/d1'))
      return Promise.resolve(
        ok({ ...event, lastError: '最新消费错误', payload: 'private-payload' }),
      )
    return Promise.resolve(events([{ ...event, status: abandoned ? 'ABANDONED' : 'PENDING' }]))
  })
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  button('查看', wrapper.element).click()
  await flushPromises()
  let dialog = document.querySelector('[role="dialog"]')!
  expect(dialog.textContent).toContain('orders-group')
  expect(dialog.textContent).toContain('event-1')
  expect(dialog.textContent).toContain('最新消费错误')
  expect(dialog.textContent).not.toContain('private-payload')
  button('关闭', dialog).click()
  await flushPromises()
  button('放弃', wrapper.element).click()
  await flushPromises()
  // Element Plus 的已关闭详情对话框在过渡结束前可能仍留在 DOM；按标题定位处置框。
  dialog = Array.from(document.querySelectorAll('[role="dialog"]')).find((item) =>
    item.textContent?.includes('确认放弃'),
  )!
  expect(button('确认放弃', dialog).disabled).toBe(true)
  const reason = dialog.querySelector<HTMLTextAreaElement>('textarea')!
  reason.value = ' 该事件已人工补偿 '
  reason.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
  button('确认放弃', dialog).click()
  await flushPromises()
  expect(wrapper.text()).toContain('已放弃该死信事件')
  expect(wrapper.text()).not.toContain('重放')
  expect(wrapper.findAll('button').some((item) => item.text() === '放弃')).toBe(false)
})

it('切换服务后忽略旧请求，当前服务完成前持续显示加载状态', async () => {
  let finishOrders!: (value: Response) => void
  let finishShipping!: (value: Response) => void
  vi.stubGlobal('fetch', (url: string) => {
    const path = new URL(url, location.origin).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/platform/ops/services')
      return Promise.resolve(
        ok([
          { service: 'orders', available: true },
          { service: 'shipping', available: true },
        ]),
      )
    return new Promise<Response>((resolve) => {
      if (path.includes('/orders/')) finishOrders = resolve
      else finishShipping = resolve
    })
  })
  const wrapper = mountPanel()
  await flushPromises()
  await choose(wrapper, 'dead-event-service', 'shipping')
  finishOrders(events([{ ...event, topic: 'stale-orders-event' }]))
  await flushPromises()
  expect(wrapper.text()).not.toContain('stale-orders-event')
  expect(wrapper.get('[aria-busy]').attributes('aria-busy')).toBe('true')
  finishShipping(events([{ ...event, topic: 'current-shipping-event' }]))
  await flushPromises()
  expect(wrapper.text()).toContain('current-shipping-event')
  expect(wrapper.get('[aria-busy]').attributes('aria-busy')).toBe('false')
})
