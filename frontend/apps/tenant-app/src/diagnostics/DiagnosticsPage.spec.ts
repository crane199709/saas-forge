import { enableAutoUnmount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import type { SubAppHost } from '../sub-app-host'
import { fakeFetch, mountShell } from '../test-shell'

enableAutoUnmount(afterEach)
afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
})

describe('技术验证诊断页', () => {
  it('开发构建中 /__diagnostics 显示当前路由、主题切换、scope 探针与共享依赖检查', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'a', refreshToken: 'r', identityName: '演示身份' }),
    )
    vi.stubGlobal('fetch', fakeFetch({}))
    const host = {
      codes: () => new Set<string>(),
      states: reactive({}),
      checks: reactive({ demo: { vue: true, pinia: false } }),
    } as unknown as SubAppHost
    const { wrapper } = await mountShell('/__diagnostics', { host })

    expect(wrapper.get('[data-testid="shell-route"]').text()).toBe('/__diagnostics')
    expect(wrapper.get('[data-testid="shell-scope"]').text()).toBe('Shell 局部样式')
    expect(wrapper.get('[data-testid="shared-demo-vue"]').text()).toBe('vue: singleton')
    expect(wrapper.get('[data-testid="shared-demo-pinia"]').text()).toBe('pinia: duplicate')

    const primary = () => document.documentElement.style.getPropertyValue('--forge-color-primary')
    expect(primary()).toBe('#409eff')
    await wrapper.get('[data-testid="shell-theme"]').trigger('click')
    expect(primary()).toBe('#8b5cf6')
  })

  it('首页不再显示诊断元素', async () => {
    sessionStorage.setItem(
      'forge.tenant.session',
      JSON.stringify({ accessToken: 'a', refreshToken: 'r', identityName: '演示身份' }),
    )
    vi.stubGlobal('fetch', fakeFetch({}))
    const { wrapper } = await mountShell('/')
    expect(wrapper.find('[data-testid="shell-route"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shell-theme"]').exists()).toBe(false)
  })
})
