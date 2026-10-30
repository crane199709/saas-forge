import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@forge/sdk'
import { i18n } from '../i18n'
import PlatformRolePanel from './PlatformRolePanel.vue'
import { createPlatformRoleApi } from './platform-role-api'

afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

const regionCondition = {
  attr: 'resource.region',
  op: 'eq',
  value: { ref: 'subject.attributes.region' },
}
const superAdmin = {
  id: 'r0',
  code: 'super-admin',
  name: '超级管理员',
  builtin: true,
  operatorCount: 1,
  permissions: [],
  dataPolicies: [],
}
const viewer = {
  id: 'r1',
  code: 'east-viewer',
  name: '华东查看',
  builtin: false,
  operatorCount: 2,
  permissions: ['platform:tenant:read'],
  dataPolicies: [{ resource: 'tenant', scope: 'custom', condition: regionCondition }],
}
const permissions = [
  { code: 'platform:tenant:read', name: '查看租户', service: 'forge-platform', group: '租户' },
  { code: 'platform:tenant:manage', name: '管理租户', service: 'forge-platform', group: '租户' },
]
const tenantResource = {
  code: 'tenant',
  name: '租户',
  service: 'forge-platform',
  scopes: ['self', 'all'],
  attributes: [{ name: 'resource.region', label: '区域', type: 'string', multiValued: false }],
}

function ok(data: unknown, httpStatus = 200) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus, data, traceId: 't' }), {
    status: httpStatus,
  })
}

function route(url: string, init?: RequestInit) {
  return `${init?.method ?? 'GET'} ${new URL(url, location.origin).pathname}`
}

function mountPanel(fetcher: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetcher)
  localStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '运营' }),
  )
  return mount(PlatformRolePanel, {
    props: { api: createPlatformRoleApi(createPlatformClient(localStorage)) },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}

function respond(url: string, init?: RequestInit) {
  const key = route(url, init)
  if (key === 'GET /iam/platform/roles/permissions') return Promise.resolve(ok(permissions))
  if (key === 'GET /iam/platform/roles/data-resources') return Promise.resolve(ok([tenantResource]))
  if (key === 'POST /iam/platform/roles') return Promise.resolve(ok(viewer, 201))
  if (key === 'PUT /iam/platform/roles/r1') return Promise.resolve(ok(null))
  return Promise.resolve(ok([superAdmin, viewer]))
}

function radio(label: string) {
  return [...document.body.querySelectorAll('.el-dialog .el-radio')].find((item) =>
    item.textContent?.includes(label),
  ) as HTMLElement
}

it('超级管理员不可编辑，数据范围按预设与负责区域展示', async () => {
  const wrapper = mountPanel(vi.fn(respond))
  await flushPromises()

  expect(wrapper.text()).toContain('全部平台权限')
  expect(wrapper.text()).toContain('全部数据')
  expect(wrapper.text()).toContain('租户：负责区域')
  expect(wrapper.find('[data-testid="platform-role-edit-super-admin"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="platform-role-edit-east-viewer"]').exists()).toBe(true)
})

it('新建平台角色时勾选权限码并按负责区域限定租户', async () => {
  const fetcher = vi.fn(respond)
  const wrapper = mountPanel(fetcher)
  await flushPromises()

  await wrapper.get('[data-testid="platform-role-create"]').trigger('click')
  await flushPromises()
  const code = document.body.querySelector('#platform-role-code') as HTMLInputElement
  code.value = 'east-viewer'
  code.dispatchEvent(new Event('input'))
  const name = document.body.querySelector('#platform-role-name') as HTMLInputElement
  name.value = '华东查看'
  name.dispatchEvent(new Event('input'))
  ;(
    document.body.querySelector(
      '[data-testid="platform-permission-platform:tenant:read"] input',
    ) as HTMLInputElement
  ).click()
  radio('负责区域').querySelector('input')!.click()
  await flushPromises()
  ;(document.body.querySelector('.el-dialog form') as HTMLFormElement).dispatchEvent(
    new Event('submit'),
  )
  await flushPromises()

  const create = fetcher.mock.calls.find((call) => route(...call) === 'POST /iam/platform/roles')
  expect(JSON.parse(create![1]!.body as string)).toEqual({
    code: 'east-viewer',
    name: '华东查看',
    permissions: ['platform:tenant:read'],
    dataPolicies: [{ resource: 'tenant', scope: 'custom', condition: regionCondition }],
  })
})

it('自定义条件不是合法 JSON 时不提交', async () => {
  const fetcher = vi.fn(respond)
  const wrapper = mountPanel(fetcher)
  await flushPromises()

  await wrapper.get('[data-testid="platform-role-edit-east-viewer"]').trigger('click')
  await flushPromises()
  radio('自定义条件').querySelector('input')!.click()
  await flushPromises()
  const condition = document.body.querySelector('.el-dialog textarea') as HTMLTextAreaElement
  condition.value = '{"attr":'
  condition.dispatchEvent(new Event('input'))
  ;(document.body.querySelector('.el-dialog form') as HTMLFormElement).dispatchEvent(
    new Event('submit'),
  )
  await flushPromises()

  expect(document.body.querySelector('.el-dialog [role="alert"]')?.textContent).toContain(
    '自定义条件不是合法的 JSON',
  )
  expect(fetcher.mock.calls.map((call) => route(...call))).not.toContain(
    'PUT /iam/platform/roles/r1',
  )
})
