import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createTenantClient, tenantClientKey } from '@forge/sdk'
import { i18n } from '../i18n'
import AcceptInvitationPage from './AcceptInvitationPage.vue'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 't' }))
}

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/invitations/accept', component: AcceptInvitationPage }],
  })
  await router.push('/invitations/accept?invitation=invite-token')
  const wrapper = mount(AcceptInvitationPage, {
    global: {
      plugins: [i18n, ElementPlus, router],
      provide: { [tenantClientKey as symbol]: createTenantClient(sessionStorage) },
    },
  })
  await flushPromises()
  return wrapper
}

it('待激活的被邀请人设置账号与密码后接受邀请', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok({ tenantName: '示例租户', requiresCredentials: true }))
    .mockResolvedValueOnce(ok({ tenantId: 't1', tenantName: '示例租户' }))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = await mountPage()

  expect(fetcher.mock.calls[0][0]).toBe('/iam/auth/invitations/preview')
  expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ token: 'invite-token' })
  expect(wrapper.text()).toContain('示例租户')

  await wrapper.get('input[name="invitation-account"]').setValue('invitee')
  await wrapper.get('input[name="invitation-password"]').setValue('Invitee-password-123!')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(fetcher.mock.calls[1][0]).toBe('/iam/auth/invitations/accept')
  expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({
    token: 'invite-token',
    account: 'invitee',
    password: 'Invitee-password-123!',
  })
  expect(wrapper.get('[data-testid="invitation-accepted"]').text()).toContain('示例租户')
})

it('已有凭证的被邀请人直接接受，不需要填写账号', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(ok({ tenantName: '示例租户', requiresCredentials: false }))
    .mockResolvedValueOnce(ok({ tenantId: 't1', tenantName: '示例租户' }))
  vi.stubGlobal('fetch', fetcher)
  const wrapper = await mountPage()

  expect(wrapper.find('input[name="invitation-account"]').exists()).toBe(false)
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ token: 'invite-token' })
  expect(wrapper.find('[data-testid="invitation-accepted"]').exists()).toBe(true)
})

it('邀请失效时展示服务端提示', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          code: 'invitation.invalid',
          httpStatus: 404,
          userTip: '邀请不存在、已过期或已被接受',
          traceId: 'trace-1',
        }),
        { status: 404 },
      ),
    ),
  )
  const wrapper = await mountPage()

  expect(wrapper.get('[role="alert"]').text()).toContain('邀请不存在、已过期或已被接受')
  expect(wrapper.find('form').exists()).toBe(false)
})
