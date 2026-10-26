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
    .mockResolvedValueOnce(new Response(JSON.stringify(memberships)))
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          accessToken: 'access-b',
          refreshToken: 'refresh-b',
          tenantId: 'tenant-b',
          tenantName: '租户 B',
          membershipId: 'membership-b',
        }),
      ),
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

it('切换被拒绝时提示并停留在原租户', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(memberships)))
      .mockResolvedValueOnce(new Response('{}', { status: 403 })),
  )
  const { wrapper, reload } = mountSwitcher()
  await flushPromises()
  wrapper.findComponent(ElSelect).vm.$emit('change', 'membership-b')
  await flushPromises()
  expect(wrapper.text()).toContain('无法进入该租户')
  expect(reload).not.toHaveBeenCalled()
  expect(JSON.parse(sessionStorage.getItem('forge.tenant.session')!).tenantId).toBe('tenant-a')
})
