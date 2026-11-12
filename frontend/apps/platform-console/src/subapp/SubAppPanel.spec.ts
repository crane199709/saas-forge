import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import SubAppPanel from './SubAppPanel.vue'
import { createSubAppApi } from './sub-app-api'

afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

const demo = {
  id: 'a1',
  code: 'demo',
  name: '示例',
  version: '1.0.0',
  remoteEntries: { local: 'http://127.0.0.1:5175/remoteEntry.js' },
  sharedRequirements: { vue: '3.5.0' },
  updatedAt: 'x',
}

function path(url: string) {
  return new URL(url, location.origin).pathname.replace(/^\/api(?=\/)/, '')
}

function ok(data: unknown, httpStatus = 200) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus, data, traceId: 't' }), {
    status: httpStatus,
  })
}

function mountPanel() {
  localStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '运营' }),
  )
  return mount(SubAppPanel, {
    props: { api: createSubAppApi(createPlatformClient(localStorage)) },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}

// el-input 把 id 与 data-testid 透传到内部的 input 元素
function input(id: string) {
  return document.body.querySelector(`[data-testid="${id}"], #${id}`) as HTMLInputElement
}

async function type(element: HTMLInputElement, value: string) {
  element.value = value
  element.dispatchEvent(new Event('input'))
  await flushPromises()
}

it('列出 Sub-App，登记时填写各环境入口地址与最低共享依赖版本', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([demo]))
    .mockResolvedValueOnce(ok({ ...demo, id: 'a2', code: 'billing' }, 201))
    .mockResolvedValueOnce(ok([demo]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()

  expect(path(fetcher.mock.calls[0][0])).toBe('/platform/sub-apps')
  expect(wrapper.text()).toContain('local：http://127.0.0.1:5175/remoteEntry.js')
  expect(wrapper.text()).toContain('vue ≥ 3.5.0')
  expect(wrapper.emitted('loaded')?.[0]).toEqual([[demo]])

  await wrapper.get('[data-testid="sub-app-create"]').trigger('click')
  await flushPromises()
  await type(input('sub-app-code'), 'billing')
  await type(input('sub-app-name'), '账单')
  await type(input('sub-app-version'), '2.0.0')
  await type(input('sub-app-entry-0'), 'https://cdn.example.com/billing/remoteEntry.js')
  ;(document.body.querySelector('.el-drawer form') as HTMLFormElement).dispatchEvent(
    new Event('submit'),
  )
  await flushPromises()

  const [url, init] = fetcher.mock.calls[1]
  expect(path(url)).toBe('/platform/sub-apps')
  expect(init.method).toBe('POST')
  expect(JSON.parse(init.body)).toEqual({
    code: 'billing',
    name: '账单',
    version: '2.0.0',
    remoteEntries: { local: 'https://cdn.example.com/billing/remoteEntry.js' },
    sharedRequirements: {},
  })
})

it('编辑时整体替换入口地址，修改地址即切换版本', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok([demo]))
    .mockResolvedValueOnce(ok(demo))
    .mockResolvedValueOnce(ok([demo]))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()

  await wrapper.get('[data-testid="sub-app-edit-demo"]').trigger('click')
  await flushPromises()
  expect(input('sub-app-code').disabled).toBe(true)
  await type(input('sub-app-entry-0'), 'http://127.0.0.1:5176/remoteEntry.js')
  ;(document.body.querySelector('.el-drawer form') as HTMLFormElement).dispatchEvent(
    new Event('submit'),
  )
  await flushPromises()

  const [url, init] = fetcher.mock.calls[1]
  expect(path(url)).toBe('/platform/sub-apps/a1')
  expect(init.method).toBe('PUT')
  expect(JSON.parse(init.body)).toEqual({
    name: '示例',
    version: '1.0.0',
    remoteEntries: { local: 'http://127.0.0.1:5176/remoteEntry.js' },
    sharedRequirements: { vue: '3.5.0' },
  })
})
