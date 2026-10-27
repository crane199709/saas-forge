import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient, tenantClientKey } from '@forge/sdk'
import { i18n } from '../i18n'
import OrganizationPage from './OrganizationPage.vue'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

it('没有管理权限时展示服务端的 userTip，不渲染管理界面', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            code: 'auth.forbidden',
            httpStatus: 403,
            userTip: '没有权限执行该操作',
            traceId: 'trace-403',
          }),
          { status: 403 },
        ),
      ),
    ),
  )
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'm' }),
  )
  const wrapper = mount(OrganizationPage, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
  })
  await flushPromises()

  expect(wrapper.get('[role="alert"]').text()).toContain('没有权限执行该操作')
  expect(wrapper.find('[data-testid="dept-name"]').exists()).toBe(false)
})
