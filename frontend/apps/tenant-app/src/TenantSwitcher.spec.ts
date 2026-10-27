import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElSelect } from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient, tenantClientKey } from '@forge/sdk'
import TenantSwitcher from './TenantSwitcher.vue'
import { i18n } from './i18n'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

const memberships = [
  { membershipId: 'membership-a', tenantId: 'tenant-a', tenantName: '租户 A' },
  { membershipId: 'membership-b', tenantId: 'tenant-b', tenantName: '租户 B' },
]

function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 'trace-ok' }))
}

function fail(code: string, status: number, userTip: string, traceId: string) {
  return new Response(
    JSON.stringify({ code, httpStatus: status, errorMessage: code, userTip, traceId }),
    { status },
  )
}

function mountSwitcher(reload = vi.fn()) {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({
      accessToken: 'access-a',
      refreshToken: 'refresh-a',
      tenantId: 'tenant-a',
      tenantName: '租户 A',
      membershipId: 'membership-a',
    }),
  )
  const wrapper = mount(TenantSwitcher, {
    props: { reload },
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
  })
  return { wrapper, reload }
}

it('切换租户后保存新会话并重新加载 Shell', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok(memberships))
    .mockResolvedValueOnce(
      ok({
        accessToken: 'access-b',
        refreshToken: 'refresh-b',
        tenantId: 'tenant-b',
        tenantName: '租户 B',
        membershipId: 'membership-b',
      }),
    )
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, reload } = mountSwitcher()
  await flushPromises()
  const select = wrapper.findComponent(ElSelect)
  expect(select.props('modelValue')).toBe('membership-a')
  select.vm.$emit('change', 'membership-b')
  await flushPromises()
  expect(fetcher.mock.calls[1]?.[0]).toBe('/iam/auth/switch')
  expect(JSON.parse(sessionStorage.getItem('forge.tenant.session')!).tenantId).toBe('tenant-b')
  expect(reload).toHaveBeenCalledOnce()
})

it('切换被拒绝时展示 userTip 与 traceId，并停留在原租户', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(ok(memberships))
      .mockResolvedValueOnce(
        fail(
          'auth.tenantAccessDenied',
          403,
          '无法进入该租户：成员关系不存在或已停用',
          'trace-switch-403',
        ),
      ),
  )
  const { wrapper, reload } = mountSwitcher()
  await flushPromises()
  wrapper.findComponent(ElSelect).vm.$emit('change', 'membership-b')
  await flushPromises()
  const alert = wrapper.get('[role="alert"]')
  expect(alert.text()).toContain('无法进入该租户')
  expect(alert.text()).toContain('trace-switch-403')
  expect(reload).not.toHaveBeenCalled()
  expect(JSON.parse(sessionStorage.getItem('forge.tenant.session')!).tenantId).toBe('tenant-a')
})

it('读取可切换租户失败时展示 userTip', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(
        fail('system.unavailable', 503, '服务暂时不可用，请稍后重试', 'trace-503'),
      ),
  )
  const { wrapper } = mountSwitcher()
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('服务暂时不可用，请稍后重试')
})
