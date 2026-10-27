import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

describe('App', () => {
  it('按 i18n key 渲染简体中文文案', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
    })
    await router.push('/')
    const wrapper = mount(App, { global: { plugins: [i18n, ElementPlus, router] } })

    expect(wrapper.text()).toContain('欢迎使用 SaaS Forge 租户端')
  })

  it('Token 失效且刷新失败时清除会话并回到登录页', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'revoked', refreshToken: 'revoked', identityName: '演示身份' }),
    )
    // Membership 已停用：业务请求与刷新都被拒绝。
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((path: string) => {
        const code = path === '/iam/auth/refresh' ? 'auth.failed' : 'auth.unauthenticated'
        return Promise.resolve(
          new Response(JSON.stringify({ code, httpStatus: 401, traceId: 'trace-401' }), {
            status: 401,
          }),
        )
      }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
    })
    await router.push('/apps/demo/overview')
    mount(App, { global: { plugins: [i18n, ElementPlus, router] } })

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/'))
    expect(sessionStorage.getItem('forge.tenant.session')).toBeNull()
  })
})
