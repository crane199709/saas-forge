import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import { signedIn } from '../test-console'
import AiModelPanel from './AiModelPanel.vue'
import { createAiModelApi } from './ai-model-api'

enableAutoUnmount(afterEach)
afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

const model = {
  id: 'm1',
  name: 'DeepSeek Chat',
  provider: 'DEEPSEEK',
  modelId: 'deepseek-chat',
  baseUrl: 'https://api.deepseek.com',
  capabilities: ['CHAT', 'TOOLS'],
  contextWindow: 65536,
  status: 'ENABLED',
  credentialKind: 'API_KEY',
}
function ok(data: unknown, status = 200) {
  return new Response(
    JSON.stringify({ code: 'ok', httpStatus: status, data, traceId: 'trace-ok' }),
    { status },
  )
}
function mountPanel(canManage = true) {
  signedIn()
  return mount(AiModelPanel, {
    props: { api: createAiModelApi(createPlatformClient(localStorage)), canManage },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}
function input(id: string) {
  return document.body.querySelector(`#${id}`) as HTMLInputElement
}
async function type(id: string, value: string) {
  const element = input(id)
  element.value = value
  element.dispatchEvent(new Event('input'))
  await flushPromises()
}

it('加载模型目录，区分直接密钥、部署引用与缺失凭据，并显示用户可读的能力', async () => {
  const fetcher = vi.fn().mockResolvedValue(
    ok([
      model,
      {
        ...model,
        id: 'm2',
        name: 'Vision',
        provider: 'OPENAI_COMPATIBLE',
        credentialKind: 'REFERENCE',
        capabilities: ['VISION', 'REASONING'],
      },
      { ...model, id: 'm3', name: 'Legacy', credentialKind: null },
    ]),
  )
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  expect(new URL(fetcher.mock.calls[0][0]).pathname).toBe('/api/ai-platform/models')
  expect(wrapper.text()).toContain('DeepSeek Chat')
  expect(wrapper.text()).toContain('OpenAI 兼容服务')
  expect(wrapper.text()).toContain('直接密钥')
  expect(wrapper.text()).toContain('部署引用')
  expect(wrapper.text()).toContain('—')
  expect(wrapper.text()).toContain('工具调用')
  expect(wrapper.text()).toContain('视觉')
  expect(wrapper.text()).toContain('推理')
  expect(wrapper.find('code').text()).toBe('deepseek-chat')
})

it('名称与状态筛选只过滤本地目录，并将无结果与空目录区分', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValue(ok([model, { ...model, id: 'm2', name: 'Vision', status: 'DISABLED' }]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await type('ai-model-search', 'vision')
  expect(wrapper.findAll('tbody tr').map((row) => row.text())).toEqual([
    expect.stringContaining('Vision'),
  ])
  await wrapper.get('[data-testid="ai-model-status-filter"]').trigger('click')
  await flushPromises()
  ;(
    Array.from(document.body.querySelectorAll('.el-select-dropdown__item')).find(
      (item) => item.textContent === '已启用',
    ) as HTMLElement
  ).click()
  await flushPromises()
  expect(wrapper.text()).toContain('没有符合条件的模型。')
  expect(wrapper.text()).not.toContain('还没有模型')
  expect(fetcher).toHaveBeenCalledTimes(1)
})

it('目录加载中不显示空态，失败展示服务提示与追踪号，重试成功后才显示空目录', async () => {
  let resolve!: (response: Response) => void
  const fetcher = vi
    .fn()
    .mockReturnValueOnce(
      new Promise<Response>((done) => {
        resolve = done
      }),
    )
    .mockResolvedValueOnce(ok([]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  expect(wrapper.text()).toContain('正在加载模型')
  expect(wrapper.text()).not.toContain('还没有模型')
  resolve(
    new Response(
      JSON.stringify({
        code: 'model.unavailable',
        httpStatus: 503,
        userTip: '模型目录暂不可用',
        traceId: 'trace-list',
      }),
      { status: 503 },
    ),
  )
  await flushPromises()
  expect(wrapper.text()).toContain('模型目录暂不可用')
  expect(wrapper.text()).toContain('trace-list')
  expect(wrapper.text()).not.toContain('还没有模型')
  await wrapper.get('[data-testid="ai-model-retry"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('还没有模型。新增后可让租户选择并调用。')
  expect(wrapper.text()).not.toContain('trace-list')
})

async function submitDrawer() {
  document.body
    .querySelector<HTMLFormElement>('.el-drawer form')!
    .dispatchEvent(new Event('submit'))
  await flushPromises()
}
async function fillNewModel(wrapper: ReturnType<typeof mountPanel>) {
  await wrapper.get('[data-testid="ai-model-create"]').trigger('click')
  await flushPromises()
  await type('ai-model-name', '  DeepSeek New  ')
  await type('ai-model-model-id', 'deepseek-new')
  await type('ai-model-base-url', 'https://api.deepseek.com/v1')
  await type('ai-model-context-window', '32768')
  await type('ai-model-api-key', 'test-provider-key')
}
it('新增提交完整元数据与 API Key，201 后关闭抽屉并刷新目录', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([]))
    .mockResolvedValueOnce(ok(model, 201))
    .mockResolvedValueOnce(ok([model]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await fillNewModel(wrapper)
  const key = input('ai-model-api-key')
  expect(key.type).toBe('password')
  expect(key.autocomplete).toBe('off')
  await submitDrawer()
  const [url, init] = fetcher.mock.calls[1]
  expect(new URL(url).pathname).toBe('/api/ai-platform/models')
  expect(init.method).toBe('POST')
  expect(JSON.parse(init.body)).toEqual({
    name: 'DeepSeek New',
    provider: 'DEEPSEEK',
    modelId: 'deepseek-new',
    baseUrl: 'https://api.deepseek.com/v1',
    capabilities: ['CHAT'],
    contextWindow: 32768,
    apiKey: 'test-provider-key',
    status: 'ENABLED',
  })
  expect(
    (document.body.querySelector('.el-drawer')!.closest('.el-overlay') as HTMLElement).style
      .display,
  ).toBe('none')
  expect(input('ai-model-api-key').value).toBe('')
  expect(wrapper.text()).toContain('DeepSeek Chat')
})

it('重复模型保存失败时保留抽屉，用错误码文案和追踪号提示且不刷新目录', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([]))
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({ code: 'model.duplicate', httpStatus: 409, traceId: 'trace-duplicate' }),
        { status: 409 },
      ),
    )
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await fillNewModel(wrapper)
  await submitDrawer()
  const drawer = document.body.querySelector('.el-drawer')!
  expect(drawer.textContent).toContain('该提供方下的模型标识已存在')
  expect(drawer.textContent).toContain('trace-duplicate')
  expect(input('ai-model-name').value).toBe('  DeepSeek New  ')
  expect(fetcher).toHaveBeenCalledTimes(2)
})

it('编辑仅提交完整元数据，留空 API Key 时保持原凭据且不发送 secretRef', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([model]))
    .mockResolvedValueOnce(ok(model))
    .mockResolvedValueOnce(ok([{ ...model, name: 'Renamed' }]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('[data-testid="ai-model-edit-m1"]').trigger('click')
  await flushPromises()
  expect(input('ai-model-api-key').value).toBe('')
  expect(input('ai-model-api-key').required).toBe(false)
  expect(input('ai-model-api-key').placeholder).toBe('留空保持现有密钥，填写新密钥即替换')
  await type('ai-model-name', 'Renamed')
  await submitDrawer()
  const [url, init] = fetcher.mock.calls[1]
  expect(new URL(url).pathname).toBe('/api/ai-platform/models/m1')
  expect(init.method).toBe('PUT')
  expect(JSON.parse(init.body)).toEqual({
    name: 'Renamed',
    provider: 'DEEPSEEK',
    modelId: 'deepseek-chat',
    baseUrl: 'https://api.deepseek.com',
    capabilities: ['CHAT', 'TOOLS'],
    contextWindow: 65536,
    status: 'ENABLED',
  })
  expect(wrapper.text()).toContain('Renamed')
})

it('编辑部署引用模型时提示一次性切换，填写新 Key 后只传 apiKey 并刷新凭据来源', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([{ ...model, credentialKind: 'REFERENCE' }]))
    .mockResolvedValueOnce(ok(model))
    .mockResolvedValueOnce(ok([model]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('[data-testid="ai-model-edit-m1"]').trigger('click')
  await flushPromises()
  expect(document.body.querySelector('.el-drawer')!.textContent).toContain(
    '当前密钥由部署环境注入，填写新密钥将改为平台加密保存，原引用不再使用。',
  )
  await type('ai-model-api-key', 'new-provider-key')
  await submitDrawer()
  const body = JSON.parse(fetcher.mock.calls[1][1].body)
  expect(body.apiKey).toBe('new-provider-key')
  expect(body).not.toHaveProperty('secretRef')
  expect(wrapper.text()).toContain('直接密钥')
})

it.each([
  ['name', '   ', '名称必填且不能超过 120 个字符'],
  ['name', 'n'.repeat(121), '名称必填且不能超过 120 个字符'],
  ['model-id', '-invalid', '模型标识须以字母或数字开头'],
  ['base-url', 'http://api.example.com', '接入地址须为 HTTPS，且不包含账号、查询参数或片段'],
  [
    'base-url',
    'https://user:pass@api.example.com',
    '接入地址须为 HTTPS，且不包含账号、查询参数或片段',
  ],
  ['base-url', 'https://api.example.com?key=x', '接入地址须为 HTTPS，且不包含账号、查询参数或片段'],
  [
    'base-url',
    'https://api.example.com#fragment',
    '接入地址须为 HTTPS，且不包含账号、查询参数或片段',
  ],
  ['context-window', '0', '上下文窗口须为大于等于 1 的整数'],
  ['context-window', '1.5', '上下文窗口须为大于等于 1 的整数'],
  ['api-key', '', '请输入有效的 API Key'],
  ['api-key', 'invalid key', '请输入有效的 API Key'],
  ['capabilities', '', '请至少选择一项能力'],
])('新增时阻止无效字段 %s=%s 发送到服务端', async (field, value, message) => {
  const fetcher = vi.fn().mockResolvedValue(ok([]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await fillNewModel(wrapper)
  if (field === 'capabilities') {
    const checkbox = document.body.querySelector<HTMLInputElement>(
      '.el-drawer input[type="checkbox"]',
    )!
    checkbox.click()
    await flushPromises()
  } else await type(`ai-model-${field}`, value)
  await submitDrawer()
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(document.body.querySelector('.el-drawer')!.textContent).toContain(message)
})

it('禁用前说明租户影响，取消不发请求，确认以完整元数据保留凭据并刷新状态', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([model]))
    .mockResolvedValueOnce(ok({ ...model, status: 'DISABLED' }))
    .mockResolvedValueOnce(ok([{ ...model, status: 'DISABLED' }]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('[data-testid="ai-model-status-m1"]').trigger('click')
  await flushPromises()
  expect(document.body.querySelector('.el-dialog')!.textContent).toContain(
    '禁用后所有租户的后续模型调用都会失效，已经打开的输出流可能继续。',
  )
  ;(document.body.querySelector('[data-testid="ai-model-status-cancel"]') as HTMLElement).click()
  await flushPromises()
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(wrapper.get('[data-testid="ai-model-status-m1"]').classes()).toContain('is-checked')
  await wrapper.get('[data-testid="ai-model-status-m1"]').trigger('click')
  await flushPromises()
  ;(document.body.querySelector('[data-testid="ai-model-status-confirm"]') as HTMLElement).click()
  await flushPromises()
  expect(fetcher.mock.calls[1][1].method).toBe('PUT')
  expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({
    name: 'DeepSeek Chat',
    provider: 'DEEPSEEK',
    modelId: 'deepseek-chat',
    baseUrl: 'https://api.deepseek.com',
    capabilities: ['CHAT', 'TOOLS'],
    contextWindow: 65536,
    status: 'DISABLED',
  })
  expect(wrapper.get('[data-testid="ai-model-status-m1"]').classes()).not.toContain('is-checked')
})

it('启用也先确认，失败保留原状态与错误，重试成功后启用', async () => {
  const disabled = { ...model, status: 'DISABLED' }
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([disabled]))
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: 'model.unavailable',
          httpStatus: 503,
          userTip: '暂时无法更新模型',
          traceId: 'trace-update',
        }),
        { status: 503 },
      ),
    )
    .mockResolvedValueOnce(ok(model))
    .mockResolvedValueOnce(ok([model]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('[data-testid="ai-model-status-m1"]').trigger('click')
  await flushPromises()
  expect(document.body.querySelector('.el-dialog')!.textContent).toContain(
    '启用后租户可重新选择并调用该模型。',
  )
  expect(fetcher).toHaveBeenCalledTimes(1)
  ;(document.body.querySelector('[data-testid="ai-model-status-confirm"]') as HTMLElement).click()
  await flushPromises()
  expect(wrapper.get('[data-testid="ai-model-status-m1"]').classes()).not.toContain('is-checked')
  expect(document.body.querySelector('.el-dialog')!.textContent).toContain('暂时无法更新模型')
  expect(document.body.querySelector('.el-dialog')!.textContent).toContain('trace-update')
  ;(document.body.querySelector('[data-testid="ai-model-status-confirm"]') as HTMLElement).click()
  await flushPromises()
  expect(JSON.parse(fetcher.mock.calls[2][1].body).status).toBe('ENABLED')
  expect(wrapper.get('[data-testid="ai-model-status-m1"]').classes()).toContain('is-checked')
})

it('没有 manage 权限时隐藏新增、编辑和启停控件', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok([model])))
  const wrapper = mountPanel(false)
  await flushPromises()
  expect(wrapper.text()).toContain('DeepSeek Chat')
  expect(wrapper.find('[data-testid="ai-model-create"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="ai-model-edit-m1"]').exists()).toBe(false)
  expect(wrapper.find('.el-switch').exists()).toBe(false)
})
