import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElSelect } from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient, tenantClientKey } from '@crane199709/sdk'
import TenantSwitcher from './TenantSwitcher.vue'
import { i18n } from './i18n'
import { loginFlow } from './login-flow'

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

function bodyEl(selector: string) {
  return document.body.querySelector<HTMLElement>(selector)
}

it('目标租户要求 MFA 时当场绑定，保存恢复码后重试切换', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok(memberships))
    .mockResolvedValueOnce(fail('auth.mfaRequired', 403, '需要先完成多因素认证', 't'))
    .mockResolvedValueOnce(ok({ otpauthUri: 'otpauth://totp/x?secret=ABCDEF' }))
    .mockResolvedValueOnce(ok({ recoveryCodes: ['AAAA-BBBB-CCCC-DDDD'] }))
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
  wrapper.findComponent(ElSelect).vm.$emit('change', 'membership-b')
  await vi.waitFor(() => expect(bodyEl('[data-testid="mfa-qr"]')).not.toBeNull())

  const input = bodyEl('input[name="bind-code"]') as HTMLInputElement
  input.value = '654321'
  input.dispatchEvent(new Event('input'))
  bodyEl('form')!.dispatchEvent(new Event('submit'))
  await vi.waitFor(() => expect(bodyEl('[data-testid="recovery-codes-done"]')).not.toBeNull())
  expect(reload).not.toHaveBeenCalled()

  bodyEl('[data-testid="recovery-codes-done"]')!.click()
  await vi.waitFor(() => expect(reload).toHaveBeenCalledOnce())
  expect(String(fetcher.mock.calls[4]?.[0])).toContain('/iam/auth/switch')
  expect(JSON.parse(sessionStorage.getItem('forge.tenant.session')!).tenantId).toBe('tenant-b')
})

it('身份已在别处绑定时清除会话，交由路由守卫回到登录页走完整流程', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok(memberships))
    .mockResolvedValueOnce(fail('auth.mfaRequired', 403, '需要先完成多因素认证', 't'))
    .mockResolvedValueOnce(fail('mfa.alreadyBound', 409, '已绑定验证器', 't'))
  vi.stubGlobal('fetch', fetcher)
  const { wrapper } = mountSwitcher()
  await flushPromises()
  wrapper.findComponent(ElSelect).vm.$emit('change', 'membership-b')
  await vi.waitFor(() => expect(sessionStorage.getItem('forge.tenant.session')).toBeNull())
  expect(loginFlow.error?.message).toContain('请重新登录')
  loginFlow.error = null
})
