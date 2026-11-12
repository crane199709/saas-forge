import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fakeFetch, mountShell } from '../test-shell'
import type { SensitiveField } from './data-security-api'

enableAutoUnmount(afterEach)
beforeEach(() => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', identityName: '管理员' }),
  )
})
afterEach(() => {
  sessionStorage.clear()
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
})
const ok = (data: unknown) =>
  new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 'data-security' }))
const field: SensitiveField = {
  service: 'demo-service',
  path: 'order.customer.phone',
  grade: 'L3',
  defaultRule: 'phone',
  overrideRule: null,
  effectiveRule: 'phone',
  status: 'active',
  exportAllowed: true,
}
const rule = {
  code: 'phone',
  name: '手机号脱敏',
  rule: { strategy: 'phone', keepFirst: null, replacement: null },
}
function backend(fields: unknown[] = [field]) {
  return fakeFetch({
    '/iam/auth/memberships': ok([]),
    '/iam/data-security/fields': ok(fields),
    '/iam/data-security/rules': ok([rule]),
  })
}
const open = (permissions = ['iam:data-security:read']) =>
  mountShell('/data-security', { access: async () => ({ permissions }) })

it('只读成员可从菜单查看登记与中文状态，无修改入口', async () => {
  vi.stubGlobal('fetch', backend())
  const { wrapper, router } = await open()
  expect(router.currentRoute.value.path).toBe('/data-security')
  expect(wrapper.get('[data-testid="menu-data-security"]').text()).toBe('数据安全')
  expect(wrapper.text()).toContain('order.customer.phone')
  expect(wrapper.text()).toContain('L3 敏感')
  expect(wrapper.text()).toContain('手机号脱敏')
  expect(wrapper.text()).toContain('生效中')
  expect(wrapper.find('[data-testid="field-edit"]').exists()).toBe(false)
  expect(wrapper.find('[data-testid="field-preview"]').exists()).toBe(true)
})

async function choose(text: string) {
  const option = Array.from(
    document.querySelectorAll<HTMLElement>('.el-select-dropdown__item'),
  ).find((item) => item.textContent?.trim() === text)
  expect(option).toBeDefined()
  option!.click()
  await flushPromises()
}
it('管理员设置字段规则后显示租户覆盖，并可恢复默认', async () => {
  let current = { ...field }
  const email = { ...rule, code: 'email', name: '邮箱脱敏' }
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    if (url.endsWith('/fields/demo-service/order.customer.phone/rule')) {
      if (init?.method === 'PUT') {
        expect(JSON.parse(init.body as string)).toEqual({ ruleCode: 'email' })
        current = { ...field, overrideRule: 'email', effectiveRule: 'email' }
      } else current = { ...field }
      return Promise.resolve(ok(current))
    }
    if (url.endsWith('/data-security/fields')) return Promise.resolve(ok([current]))
    if (url.endsWith('/data-security/rules')) return Promise.resolve(ok([rule, email]))
    return Promise.resolve(ok([]))
  })
  const { wrapper } = await open(['iam:data-security:read', 'iam:data-security:manage'])
  await wrapper.get('[data-testid="field-edit"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="field-rule"]').trigger('click')
  await flushPromises()
  await choose('邮箱脱敏（email）')
  await wrapper.get('[data-testid="field-save"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('邮箱脱敏')
  expect(wrapper.text()).toContain('租户覆盖')
  await wrapper.get('[data-testid="field-reset"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('租户覆盖')
  expect(wrapper.text()).toContain('手机号脱敏')
})

it('只读成员可预览样例，预览失败清除旧结果并可重试', async () => {
  let attempt = 0
  const read = backend()
  vi.stubGlobal('fetch', (url: string, init?: RequestInit) => {
    if (!url.endsWith('/preview')) return read(url)
    expect(JSON.parse(init?.body as string)).toEqual({ ruleCode: 'phone', value: '13812345678' })
    attempt++
    return Promise.resolve(
      ok(
        attempt === 2
          ? {
              success: false,
              value: '13812345678',
              errorCode: 'field-masking.unchanged',
              errorMessage: 'Raw sample 13812345678 is unchanged',
            }
          : { success: true, value: '138****5678', errorCode: null, errorMessage: null },
      ),
    )
  })
  const { wrapper } = await open()
  await wrapper.get('[data-testid="field-preview"]').trigger('click')
  await flushPromises()
  await wrapper.get('input[data-testid="preview-value"]').setValue('13812345678')
  await wrapper.get('[data-testid="preview-run"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-testid="preview-result"]').text()).toBe('138****5678')
  await wrapper.get('[data-testid="preview-run"]').trigger('click')
  await flushPromises()
  expect(wrapper.find('[data-testid="preview-result"]').exists()).toBe(false)
  expect(wrapper.get('[data-testid="preview-error"]').text()).toContain(
    '规则未改变样例值，请调整样例或规则',
  )
  expect(wrapper.get('[data-testid="preview-error"]').text()).not.toContain('13812345678')
  await wrapper.get('[data-testid="preview-run"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[data-testid="preview-result"]').text()).toBe('138****5678')
})

it('保存失败保留选择，重新保存后更新列表', async () => {
  let attempts = 0
  const read = backend()
  vi.stubGlobal('fetch', (url: string) => {
    if (url.endsWith('/rule')) {
      attempts++
      return Promise.resolve(
        attempts === 1
          ? new Response(
              JSON.stringify({
                code: 'field-masking.invalidRule',
                httpStatus: 400,
                userTip: '该规则暂不可用',
                traceId: 'failed-save',
              }),
              { status: 400 },
            )
          : ok({ ...field, overrideRule: 'phone' }),
      )
    }
    return read(url)
  })
  const { wrapper } = await open(['iam:data-security:read', 'iam:data-security:manage'])
  await wrapper.get('[data-testid="field-edit"]').trigger('click')
  await flushPromises()
  await wrapper.get('[data-testid="field-save"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('该规则暂不可用')
  expect(wrapper.get('[data-testid="field-rule"]').text()).toContain('手机号脱敏')
  await wrapper.get('[data-testid="field-save"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('租户覆盖')
})
it('登记加载失败提供重试，成功空列表显示专属空态', async () => {
  vi.stubGlobal(
    'fetch',
    fakeFetch({
      '/iam/auth/memberships': ok([]),
      '/iam/data-security/fields': [
        new Response(
          JSON.stringify({
            code: 'unavailable',
            userTip: '登记暂不可用',
            traceId: 'failed-load',
            httpStatus: 503,
          }),
          { status: 503 },
        ),
        ok([]),
      ],
      '/iam/data-security/rules': ok([rule]),
    }),
  )
  const { wrapper } = await open()
  expect(wrapper.text()).toContain('登记暂不可用')
  expect(wrapper.text()).not.toContain('暂无已登记的敏感字段')
  await wrapper.get('[data-testid="data-security-reload"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('暂无已登记的敏感字段')
  expect(wrapper.find('[role="alert"]').exists()).toBe(false)
})
it('没有read权限时不显示导航，直接进入返回403', async () => {
  vi.stubGlobal('fetch', backend())
  const { wrapper, router } = await open(['iam:data-security:manage'])
  expect(router.currentRoute.value.path).toBe('/403')
  expect(wrapper.find('[data-testid="menu-data-security"]').exists()).toBe(false)
})
