import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient, tenantClientKey } from '@crane199709/sdk'
import { i18n } from '../i18n'
import { reloadShellKey } from '../shell'
import LeaveTenantSection from './LeaveTenantSection.vue'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

const respond = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify({ httpStatus: status, traceId: 't', ...body }), { status })

it('被拒绝时展示原因并留在页面，确认离开后结束会话并重载', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({
      accessToken: 'a',
      refreshToken: 'r',
      membershipId: 'm',
      tenantName: '租户 A',
    }),
  )
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      respond(409, { code: 'member.ownerProtected', userTip: '所有者不能退出租户，请先转让' }),
    )
    .mockResolvedValueOnce(respond(200, { code: 'ok', data: null }))
    .mockResolvedValueOnce(respond(200, { code: 'ok', data: null }))
  vi.stubGlobal('fetch', fetcher)
  const reload = vi.fn()
  const wrapper = mount(LeaveTenantSection, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: {
        [tenantClientKey as symbol]: createTenantClient(sessionStorage),
        [reloadShellKey as symbol]: reload,
      },
    },
    attachTo: document.body,
  })

  for (const expected of ['rejected', 'left']) {
    await wrapper.get('[data-testid="tenant-leave"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('租户 A')
    document.querySelector<HTMLElement>('[data-testid="tenant-leave-confirm"]')!.click()
    await flushPromises()
    if (expected === 'rejected') {
      expect(wrapper.get('[role="alert"]').text()).toContain('所有者不能退出租户')
      expect(reload).not.toHaveBeenCalled()
    }
  }

  expect(fetcher.mock.calls[1]![0]).toBe(`${location.origin}/iam/members/me/leave`)
  expect(sessionStorage.getItem('forge.tenant.session')).toBeNull()
  expect(reload).toHaveBeenCalledOnce()
})
