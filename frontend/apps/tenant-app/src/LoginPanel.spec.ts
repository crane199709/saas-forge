/// <reference types="node" />
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { webcrypto } from 'node:crypto'
import { createTenantClient, tenantClientKey } from '@forge/sdk'
beforeEach(() => vi.stubGlobal('crypto', webcrypto))
import LoginPanel from './LoginPanel.vue'
import { i18n } from './i18n'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

it('账号登录后展示下游返回的身份、租户与成员关系', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            clientId: 'tenant-app',
            redirectUri: location.origin + '/',
            memberships: [
              { membershipId: 'membership-1', tenantId: 'tenant-1', tenantName: '演示租户' },
            ],
          }),
        ),
      )
      .mockImplementationOnce((path: string) => {
        const state = new URL(path, location.origin).searchParams.get('state')
        const response = new Response('')
        Object.defineProperty(response, 'url', {
          value: location.origin + '/?code=authorization-code&state=' + state,
        })
        return Promise.resolve(response)
      })
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            accessToken: 'a',
            refreshToken: 'r',
            tokenType: 'Bearer',
            expiresIn: 900,
            identityName: '演示身份',
            tenantName: '演示租户',
          }),
        ),
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            identityId: 'identity-1',
            tenantId: 'tenant-1',
            membershipId: 'membership-1',
            version: 1,
          }),
        ),
      ),
  )
  const wrapper = mount(LoginPanel, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
  })
  await wrapper.get('input[name="account"]').setValue('demo')
  await wrapper.get('input[name="password"]').setValue('Demo-password-123!')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.text()).toContain('演示身份'))
  expect(wrapper.text()).toContain('演示租户')
  expect(wrapper.text()).toContain('membership-1')
  expect(wrapper.find('input[name="password"]').exists()).toBe(false)
})

it('退出失败后，较晚返回的初始化请求不能覆盖退出警告和重试入口', async () => {
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'a', refreshToken: 'r', identityName: '演示身份' }),
  )
  let finishContext!: (response: Response) => void
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockImplementationOnce(
        () =>
          new Promise<Response>((resolve) => {
            finishContext = resolve
          }),
      )
      .mockResolvedValueOnce(new Response(null, { status: 500 })),
  )
  const wrapper = mount(LoginPanel, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
  })
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('浏览器仍可能保持登录')
  finishContext(new Response('{}'))
  await flushPromises()
  expect(wrapper.text()).toContain('浏览器仍可能保持登录')
  expect(wrapper.text()).toContain('重试退出')
  wrapper.unmount()
})

function mountPanel() {
  return mount(LoginPanel, {
    global: {
      plugins: [i18n, ElementPlus],
      provide: { [tenantClientKey]: createTenantClient(sessionStorage) },
    },
  })
}

async function submit(wrapper: ReturnType<typeof mountPanel>) {
  await wrapper.get('input[name="account"]').setValue('multi')
  await wrapper.get('input[name="password"]').setValue('Demo-password-123!')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
}

it('多个 Membership 时先展示租户选择页，选择后进入该租户', async () => {
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          clientId: 'tenant-app',
          redirectUri: location.origin + '/',
          memberships: [
            { membershipId: 'membership-a', tenantId: 'tenant-a', tenantName: '租户 A' },
            { membershipId: 'membership-b', tenantId: 'tenant-b', tenantName: '租户 B' },
          ],
        }),
      ),
    )
    .mockImplementationOnce((path: string) => {
      const state = new URL(path, location.origin).searchParams.get('state')
      const response = new Response('')
      Object.defineProperty(response, 'url', {
        value: location.origin + '/?code=authorization-code&state=' + state,
      })
      return Promise.resolve(response)
    })
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          accessToken: 'a',
          refreshToken: 'r',
          identityName: '多成员身份',
          tenantId: 'tenant-b',
          tenantName: '租户 B',
          membershipId: 'membership-b',
        }),
      ),
    )
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({ identityId: 'i', tenantId: 'tenant-b', membershipId: 'membership-b' }),
      ),
    )
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mountPanel()
  await submit(wrapper)
  expect(wrapper.text()).toContain('选择租户')
  expect(fetcher).toHaveBeenCalledTimes(1)
  await wrapper.get('[data-testid="tenant-option-membership-b"]').trigger('click')
  await flushPromises()
  await vi.waitFor(() => expect(wrapper.text()).toContain('当前租户：租户 B'))
  expect(JSON.parse(fetcher.mock.calls[2]?.[1]?.body).membershipId).toBe('membership-b')
})

it('没有有效 Membership 时展示无租户页', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          clientId: 'tenant-app',
          redirectUri: location.origin + '/',
          memberships: [],
        }),
      ),
    ),
  )
  const wrapper = mountPanel()
  await submit(wrapper)
  expect(wrapper.text()).toContain('暂无可进入的租户')
  expect(wrapper.find('form').exists()).toBe(false)
})
