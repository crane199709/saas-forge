import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElSelect } from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient, tenantClientKey } from '@crane199709/sdk'
import TenantSwitcher from './TenantSwitcher.vue'
import { i18n } from './i18n'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
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
    // 退出确认对话框传送到 body
    attachTo: document.body,
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

it('退出当前租户后结束会话并重新加载；Owner 被拒绝时展示原因', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok(memberships))
    .mockResolvedValueOnce(
      fail('member.ownerProtected', 409, '所有者不能退出租户，请先转让', 'trace-owner'),
    )
    .mockResolvedValueOnce(ok(null))
    .mockResolvedValueOnce(ok(null))
  vi.stubGlobal('fetch', fetcher)
  const { wrapper, reload } = mountSwitcher()
  await flushPromises()

  for (const expected of ['rejected', 'left']) {
    await wrapper.get('[data-testid="tenant-leave"]').trigger('click')
    await flushPromises()
    ;(document.querySelector('[data-testid="tenant-leave-confirm"]') as HTMLElement).click()
    await flushPromises()
    if (expected === 'rejected') {
      expect(wrapper.get('[role="alert"]').text()).toContain('所有者不能退出租户')
      expect(reload).not.toHaveBeenCalled()
    }
  }

  expect(fetcher.mock.calls[2]![0]).toBe(`${location.origin}/iam/members/me/leave`)
  expect(fetcher.mock.calls[3]![0]).toBe('/iam/auth/logout')
  expect(sessionStorage.getItem('forge.tenant.session')).toBeNull()
  expect(reload).toHaveBeenCalledOnce()
})
