import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient, tenantClientKey } from '@crane199709/sdk'
import { i18n } from '../i18n'
import DictionaryPage from './DictionaryPage.vue'
import { tableRow } from '../test-table'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

const dictionaries = [
  {
    service: 'crm-service',
    code: 'customer-source',
    name: '客户来源',
    extensible: true,
    items: [
      { code: 'web', name: '官网', sort: 1, custom: false, status: 'enabled', nameConflict: true },
      { code: 'custom.expo', name: '展会', sort: 2, custom: true, status: 'disabled' },
      { code: 'fax', name: '传真', sort: 3, custom: false, status: 'retired' },
      {
        code: 'custom.site',
        name: '官网',
        sort: 4,
        custom: true,
        status: 'enabled',
        nameConflict: true,
      },
    ],
  },
  {
    service: 'crm-service',
    code: 'gender',
    name: '性别',
    extensible: false,
    items: [{ code: 'male', name: '男', sort: 1, custom: false, status: 'enabled' }],
  },
  {
    service: 'demo-service',
    code: 'order-channel',
    name: '订单渠道',
    extensible: true,
    items: [],
  },
]

function respond(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify({ httpStatus: status, traceId: 'trace-dict', ...body }), {
    status,
  })
}

/** 字典列表按 GET 应答，其余请求依次取 writes 中的应答。 */
async function mountPage(...writes: Response[]) {
  const fetcher = vi.fn((_input: string, init: RequestInit) =>
    Promise.resolve(
      init.method === 'GET'
        ? respond(200, { code: 'ok', data: dictionaries })
        : (writes.shift() ?? respond(200, { code: 'ok', data: null })),
    ),
  )
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'm' }),
  )
  const wrapper = mount(DictionaryPage, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
  })
  await flushPromises()
  return {
    wrapper,
    fetcher,
    writes: () => fetcher.mock.calls.filter(([, init]) => init.method !== 'GET'),
  }
}

it('按服务列出字典，默认隐藏已停用项，可切换显示', async () => {
  const { wrapper } = await mountPage()

  expect(wrapper.get('[data-testid="service-crm-service"]').text()).toContain('客户来源')
  expect(wrapper.get('[data-testid="service-crm-service"]').text()).toContain('性别')
  expect(wrapper.get('[data-testid="service-demo-service"]').text()).toContain('订单渠道')
  expect(wrapper.find('[data-testid="item-web"]').exists()).toBe(true)
  expect(wrapper.find('[data-testid="item-custom.expo"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="item-fax"]').exists()).toBe(false)

  await wrapper.get('[data-testid="show-disabled"] input').setValue(true)
  expect(tableRow(wrapper, 'item-custom.expo').text()).toContain('已停用')
  expect(tableRow(wrapper, 'item-fax').text()).toContain('已下线')
})

it('可扩展字典新增自定义项：编码提示 custom. 前缀，提交不含前缀，完成后重读', async () => {
  const { wrapper, fetcher, writes } = await mountPage(
    respond(201, {
      code: 'ok',
      data: { code: 'custom.expo2', name: '展会', sort: 0, custom: true, status: 'enabled' },
    }),
  )

  expect(wrapper.find('input[data-testid="item-code"]').exists()).toBe(false)
  await wrapper.get('[data-testid="new-item-create"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-testid="item-code-field"]').text()).toContain('custom.')
  await wrapper.get('input[data-testid="item-code"]').setValue('expo2')
  await wrapper.get('input[data-testid="item-name"]').setValue(' 展会 ')
  await wrapper.get('[data-testid="item-save"]').trigger('click')
  await flushPromises()

  const [url, init] = writes()[0]!
  expect(url).toBe(
    `${location.origin}/api/iam/reference-data/dictionaries/crm-service/customer-source/items`,
  )
  expect(init.method).toBe('POST')
  expect(JSON.parse(init.body as string)).toEqual({ code: 'expo2', name: '展会' })
  expect(fetcher.mock.calls.filter(([, init]) => init.method === 'GET')).toHaveLength(2)
})

it('编码重复时展示服务端的 userTip 与 traceId', async () => {
  const { wrapper } = await mountPage(
    respond(409, {
      code: 'dictionary.itemCodeTaken',
      errorMessage: 'taken',
      userTip: '字典项编码 custom.expo 已被使用',
    }),
  )

  await wrapper.get('[data-testid="new-item-create"]').trigger('click')
  await flushPromises()
  await wrapper.get('input[data-testid="item-code"]').setValue('expo')
  await wrapper.get('input[data-testid="item-name"]').setValue('展会2')
  await wrapper.get('[data-testid="item-save"]').trigger('click')
  await flushPromises()

  const alert = wrapper.get('[role="alert"]')
  expect(alert.text()).toContain('字典项编码 custom.expo 已被使用')
  expect(alert.text()).toContain('trace-dict')
})

it('固定字典只读，不提供新增与修改', async () => {
  const { wrapper } = await mountPage()

  await wrapper.get('[data-testid="dictionary-crm-service/gender"]').trigger('click')

  expect(wrapper.text()).toContain('固定字典')
  expect(wrapper.find('[data-testid="new-item-create"]').exists()).toBe(false)
  expect(tableRow(wrapper, 'item-male').find('button').exists()).toBe(false)
})

it('修改内置项的显示名与排序', async () => {
  const { wrapper, writes } = await mountPage()

  expect(wrapper.find('input[data-testid="item-name"]').exists()).toBe(false)
  await wrapper.get('[data-testid="item-edit-web"]').trigger('click')
  await flushPromises()
  const name = wrapper.get('input[data-testid="item-name"]')
  expect((name.element as HTMLInputElement).value).toBe('官网')
  await name.setValue(' 公司官网 ')
  await wrapper.get('[data-testid="item-sort"] input').setValue('5')
  await wrapper.get('[data-testid="item-save"]').trigger('click')
  await flushPromises()

  const [url, init] = writes()[0]!
  expect(url).toBe(
    `${location.origin}/api/iam/reference-data/dictionaries/crm-service/customer-source/items/web`,
  )
  expect(init.method).toBe('PUT')
  expect(JSON.parse(init.body as string)).toEqual({ name: '公司官网', sort: 5 })
})

it('停用启用中的项，启用已停用的项；已下线的项不可操作', async () => {
  const { wrapper, writes } = await mountPage()
  await wrapper.get('[data-testid="show-disabled"] input').setValue(true)

  await wrapper.get('[data-testid="item-disable-web"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="item-enable-custom.expo"]').trigger('click')
  await flushPromises()

  const base = `${location.origin}/api/iam/reference-data/dictionaries/crm-service/customer-source/items`
  expect(writes().map(([url, init]) => [init.method, url])).toEqual([
    ['POST', `${base}/web/disable`],
    ['POST', `${base}/custom.expo/enable`],
  ])
  expect(tableRow(wrapper, 'item-fax').find('button').exists()).toBe(false)
})

it('与其他可选项重名的项带标记，提示改名其中之一', async () => {
  const { wrapper } = await mountPage()

  for (const code of ['web', 'custom.site']) {
    expect(wrapper.get(`[data-testid="item-conflict-${code}"]`).text()).toContain('重名')
  }
  expect(wrapper.text()).toContain('请修改其中一项的显示名')
  await wrapper.get('[data-testid="show-disabled"] input').setValue(true)
  expect(wrapper.find('[data-testid="item-conflict-custom.expo"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="item-conflict-fax"]').exists()).toBe(false)
})
