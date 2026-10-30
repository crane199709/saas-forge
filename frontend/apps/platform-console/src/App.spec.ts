import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { i18n } from './i18n'

afterEach(() => {
  localStorage.clear()
  vi.unstubAllGlobals()
})

function ok(data: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 't' }), {
    status: 200,
  })
}

function page(items: unknown[]) {
  return new Response(
    JSON.stringify({
      code: 'ok',
      httpStatus: 200,
      data: items,
      meta: { page: { number: 1, size: 20, totalElements: items.length, totalPages: 1 } },
      traceId: 't',
    }),
    { status: 200 },
  )
}

describe('App', () => {
  it('按 i18n key 渲染简体中文文案', () => {
    const wrapper = mount(App, { global: { plugins: [i18n, ElementPlus] } })

    expect(wrapper.text()).toContain('欢迎使用 SaaS Forge 平台控制台')
  })

  it('按当前运营人员的平台权限显示面板：只读租户时不显示管理功能', async () => {
    localStorage.setItem(
      'forge.platform.session',
      JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '华东运营' }),
    )
    const fetcher = vi.fn((url: string) => {
      const path = new URL(url, location.origin).pathname
      if (path === '/iam/platform/access') {
        return Promise.resolve(
          ok({ roles: ['east-viewer'], permissions: ['platform:tenant:read'] }),
        )
      }
      if (path === '/platform/tenants') return Promise.resolve(page([]))
      return Promise.resolve(ok([]))
    })
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(App, { global: { plugins: [i18n, ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[aria-label="租户"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('创建租户')
    expect(wrapper.find('[aria-label="套餐"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="应用注册表"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="运营账号"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="平台角色"]').exists()).toBe(false)
  })
})
