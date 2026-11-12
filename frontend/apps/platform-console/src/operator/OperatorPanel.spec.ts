import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPlatformClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import { createPlatformRoleApi } from '../role/platform-role-api'
import OperatorPanel from './OperatorPanel.vue'
import { createOperatorApi } from './operator-api'

afterEach(() => {
  localStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})

const superAdmin = {
  id: 'r0',
  code: 'super-admin',
  name: '超级管理员',
  builtin: true,
  operatorCount: 1,
  permissions: [],
  dataPolicies: [],
}
const viewer = { ...superAdmin, id: 'r1', code: 'east-viewer', name: '华东查看', builtin: false }
const admin = {
  id: 'o1',
  account: 'operator',
  displayName: '演示运营',
  region: null,
  status: 'active',
  roleIds: ['r0'],
  createdAt: 'x',
}

function ok(data: unknown, httpStatus = 200) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus, data, traceId: 't' }), {
    status: httpStatus,
  })
}

function route(url: string, init?: RequestInit) {
  return `${init?.method ?? 'GET'} ${new URL(url, location.origin).pathname.replace(/^\/api(?=\/)/, '')}`
}

function mountPanel(canResetMfa = false) {
  localStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '运营' }),
  )
  const client = createPlatformClient(localStorage)
  return mount(OperatorPanel, {
    props: { api: createOperatorApi(client), roleApi: createPlatformRoleApi(client), canResetMfa },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
}

function input(id: string) {
  return document.body.querySelector(`#${id}`) as HTMLInputElement
}

async function type(element: HTMLInputElement, value: string) {
  element.value = value
  element.dispatchEvent(new Event('input'))
  await flushPromises()
}

it('列出运营账号及其平台角色，创建时填写负责区域并分配角色', async () => {
  const created = { ...admin, id: 'o2', account: 'east-ops', region: 'east', roleIds: ['r1'] }
  const fetcher = vi.fn((url: string, init?: RequestInit) => {
    const key = route(url, init)
    if (key === 'GET /iam/platform/roles') return Promise.resolve(ok([superAdmin, viewer]))
    if (key === 'POST /iam/platform/operators') return Promise.resolve(ok(created, 201))
    const listed = fetcher.mock.calls.filter((call) => route(...call) === key).length
    return Promise.resolve(ok(listed > 1 ? [admin, created] : [admin]))
  })
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()

  expect(wrapper.text()).toContain('演示运营')
  expect(wrapper.text()).toContain('超级管理员')
  expect(wrapper.text()).toContain('未分配')

  await wrapper.get('[data-testid="operator-create"]').trigger('click')
  await flushPromises()
  await type(input('operator-account'), 'east-ops')
  await type(input('operator-display-name'), '华东运营')
  await type(input('operator-password'), 'East-password-123!')
  await type(input('operator-region'), ' east ')
  const checkbox = [...document.body.querySelectorAll('.el-dialog .el-checkbox')].find((item) =>
    item.textContent?.includes('华东查看'),
  ) as HTMLElement
  checkbox.querySelector('input')!.click()
  await flushPromises()
  ;(document.body.querySelector('.el-dialog form') as HTMLFormElement).dispatchEvent(
    new Event('submit'),
  )
  await flushPromises()

  const create = fetcher.mock.calls.find(
    (call) => route(...call) === 'POST /iam/platform/operators',
  )
  expect(JSON.parse(create![1]!.body as string)).toEqual({
    account: 'east-ops',
    displayName: '华东运营',
    password: 'East-password-123!',
    region: 'east',
    roleIds: ['r1'],
  })
  expect(wrapper.text()).toContain('east-ops')
})

it('停用运营账号，最后一位超级管理员被拒时显示服务端提示', async () => {
  const fetcher = vi.fn((url: string, init?: RequestInit) => {
    const key = route(url, init)
    if (key === 'GET /iam/platform/roles') return Promise.resolve(ok([superAdmin]))
    if (key === 'POST /iam/platform/operators/o1/disable') {
      return Promise.resolve(
        new Response(
          JSON.stringify({
            code: 'platformRole.lastSuperAdmin',
            httpStatus: 409,
            userTip: '至少需要保留一位状态正常的超级管理员',
            traceId: 't',
          }),
          { status: 409 },
        ),
      )
    }
    return Promise.resolve(ok([admin]))
  })
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await flushPromises()

  await wrapper.get('[data-testid="operator-toggle-operator"]').trigger('click')
  await flushPromises()

  expect(fetcher.mock.calls.map((call) => route(...call))).toContain(
    'POST /iam/platform/operators/o1/disable',
  )
  expect(wrapper.get('[role="alert"]').text()).toContain('至少需要保留一位状态正常的超级管理员')
})

it('停用失败后重试成功，清除旧错误并显示最新状态', async () => {
  let attempts = 0
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const key = route(url, init)
      if (key === 'GET /iam/platform/roles') return Promise.resolve(ok([superAdmin]))
      if (key === 'POST /iam/platform/operators/o1/disable') {
        if (++attempts === 1)
          return Promise.resolve(
            new Response(
              JSON.stringify({
                code: 'system.unavailable',
                httpStatus: 503,
                userTip: '账号服务暂不可用',
                traceId: 'operator-failed',
              }),
              { status: 503 },
            ),
          )
        return Promise.resolve(ok(null))
      }
      return Promise.resolve(ok([{ ...admin, status: attempts > 1 ? 'disabled' : 'active' }]))
    }),
  )
  const wrapper = mountPanel()
  await flushPromises()
  await wrapper.get('[data-testid="operator-toggle-operator"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('账号服务暂不可用')
  await wrapper.get('[data-testid="operator-toggle-operator"]').trigger('click')
  await flushPromises()
  expect(attempts).toBe(2)
  expect(wrapper.text()).not.toContain('operator-failed')
  expect(wrapper.text()).not.toContain('账号服务暂不可用')
  expect(wrapper.get('[data-testid="operator-toggle-operator"]').text()).toContain('恢复')
})

describe('重置 MFA', () => {
  function backend(reset: () => Response) {
    const fetcher = vi.fn((url: string, init?: RequestInit) => {
      const key = route(url, init)
      if (key === 'GET /iam/platform/roles') return Promise.resolve(ok([superAdmin]))
      if (key === 'POST /iam/platform/operators/o1/mfa/reset') return Promise.resolve(reset())
      return Promise.resolve(ok([admin]))
    })
    vi.stubGlobal('fetch', fetcher)
    return fetcher
  }

  const confirmButton = () =>
    document.body.querySelector('[data-testid="operator-mfa-reset-confirm"]') as HTMLElement

  it('非超级管理员看不到入口', async () => {
    backend(() => ok(null))
    const wrapper = mountPanel(false)
    await flushPromises()
    expect(wrapper.find('[data-testid="operator-mfa-reset-operator"]').exists()).toBe(false)
  })

  it('超级管理员确认后重置，取消确认前不发请求', async () => {
    const fetcher = backend(() => ok(null))
    const wrapper = mountPanel(true)
    await flushPromises()
    await wrapper.get('[data-testid="operator-mfa-reset-operator"]').trigger('click')
    await flushPromises()
    const resets = () =>
      fetcher.mock.calls.filter(
        (call) => route(...call) === 'POST /iam/platform/operators/o1/mfa/reset',
      )
    expect(resets()).toHaveLength(0)

    confirmButton().click()
    await flushPromises()
    expect(resets()).toHaveLength(1)
    expect(wrapper.text()).toContain('已重置 演示运营 的 MFA')
  })

  it('对方未绑定时展示服务端提示', async () => {
    backend(
      () =>
        new Response(
          JSON.stringify({
            code: 'mfa.notBound',
            httpStatus: 409,
            userTip: '尚未绑定验证器',
            traceId: 't',
          }),
          { status: 409 },
        ),
    )
    const wrapper = mountPanel(true)
    await flushPromises()
    await wrapper.get('[data-testid="operator-mfa-reset-operator"]').trigger('click')
    await flushPromises()
    confirmButton().click()
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('尚未绑定验证器')
    expect(wrapper.text()).not.toContain('已重置')
  })
})
