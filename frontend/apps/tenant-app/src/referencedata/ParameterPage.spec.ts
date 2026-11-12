import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient, tenantClientKey } from '@crane199709/sdk'
import { i18n } from '../i18n'
import ParameterPage from './ParameterPage.vue'
import { tableRow } from '../test-table'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

const parameters = [
  {
    service: 'demo-service',
    code: 'order.max-lines',
    name: '订单最大行数',
    type: 'integer',
    dictionary: null,
    defaultValue: 50,
    min: 1,
    max: 200,
    maxLength: null,
    tenantEditable: true,
    value: 80,
    overridden: true,
  },
  {
    service: 'demo-service',
    code: 'order.auto-confirm',
    name: '自动确认',
    type: 'boolean',
    dictionary: null,
    defaultValue: false,
    min: null,
    max: null,
    maxLength: null,
    tenantEditable: true,
    value: false,
    overridden: false,
  },
]

function respond(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify({ httpStatus: status, traceId: 'trace-param', ...body }), {
    status,
  })
}

/** 参数与字典列表按 GET 应答，其余请求依次取 writes 中的应答。 */
async function mountPage(...writes: Response[]) {
  const fetcher = vi.fn((input: string, init: RequestInit) =>
    Promise.resolve(
      init.method === 'GET'
        ? respond(200, { code: 'ok', data: input.endsWith('/parameters') ? parameters : [] })
        : (writes.shift() ?? respond(200, { code: 'ok', data: null })),
    ),
  )
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'm' }),
  )
  const wrapper = mount(ParameterPage, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
  })
  await flushPromises()
  const writeCalls = () => fetcher.mock.calls.filter(([, init]) => init.method !== 'GET')
  return { wrapper, fetcher, writes: writeCalls }
}

it('列出参数的当前值、默认值与约束', async () => {
  const { wrapper } = await mountPage()

  const row = tableRow(wrapper, 'parameter-demo-service/order.max-lines')
  expect(row.text()).toContain('订单最大行数')
  expect(row.text()).toContain('80')
  expect(row.text()).toContain('50')
  expect(row.text()).toContain('最小 1')
  expect(row.text()).toContain('最大 200')
  expect(row.text()).toContain('已覆盖')
  expect(tableRow(wrapper, 'parameter-demo-service/order.auto-confirm').text()).toContain('否')
})

it('覆盖整数参数时按数字提交，完成后重读', async () => {
  const { wrapper, fetcher, writes } = await mountPage(
    respond(200, { code: 'ok', data: { ...parameters[0], value: 120 } }),
  )

  await wrapper.get('input[data-testid="value-demo-service/order.max-lines"]').setValue('120')
  await wrapper.get('[data-testid="override-demo-service/order.max-lines"]').trigger('click')
  await flushPromises()

  const [url, init] = writes()[0]!
  expect(url).toBe(
    `${location.origin}/api/iam/reference-data/parameters/demo-service/order.max-lines`,
  )
  expect(init.method).toBe('PUT')
  expect(JSON.parse(init.body as string)).toEqual({ value: 120 })
  expect(fetcher.mock.calls.filter(([input]) => input.endsWith('/parameters'))).toHaveLength(2)
})

it('覆盖布尔参数时按布尔提交', async () => {
  const { wrapper, writes } = await mountPage()

  await wrapper.get('[data-testid="value-demo-service/order.auto-confirm"] input').setValue(true)
  await wrapper.get('[data-testid="override-demo-service/order.auto-confirm"]').trigger('click')
  await flushPromises()

  expect(JSON.parse(writes()[0]![1].body as string)).toEqual({ value: true })
})

it('越界时展示服务端的 userTip 与 traceId', async () => {
  const { wrapper } = await mountPage(
    respond(400, {
      code: 'parameter.invalidValue',
      errorMessage: 'invalid',
      userTip: '参数 order.max-lines 的取值不合法',
    }),
  )

  await wrapper.get('input[data-testid="value-demo-service/order.max-lines"]').setValue('999')
  await wrapper.get('[data-testid="override-demo-service/order.max-lines"]').trigger('click')
  await flushPromises()

  const alert = wrapper.get('[role="alert"]')
  expect(alert.text()).toContain('参数 order.max-lines 的取值不合法')
  expect(alert.text()).toContain('trace-param')
})

it('已覆盖的参数可恢复默认，未覆盖的不提供', async () => {
  const { wrapper, writes } = await mountPage()

  expect(wrapper.find('[data-testid="reset-demo-service/order.auto-confirm"]').exists()).toBe(false)
  await wrapper.get('[data-testid="reset-demo-service/order.max-lines"]').trigger('click')
  await flushPromises()

  const [url, init] = writes()[0]!
  expect(url).toBe(
    `${location.origin}/api/iam/reference-data/parameters/demo-service/order.max-lines`,
  )
  expect(init.method).toBe('DELETE')
})
