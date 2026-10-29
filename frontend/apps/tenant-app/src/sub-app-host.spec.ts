import type { MenuNode, SubAppEntry } from '@forge/sdk'
import { flushPromises } from '@vue/test-utils'
import { createPinia, defineStore, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createShellReload } from './shell'
import { createSubAppHost, unmetRequirements, type SubAppModule } from './sub-app-host'

const page = { render: () => null }

function menu(...apps: string[]): MenuNode[] {
  return apps.map((app) => ({
    code: app,
    name: app,
    children: [{ code: `${app}.home`, name: app, app, path: 'home' }],
  }))
}

function entry(code: string, sharedRequirements: Record<string, string> = {}): SubAppEntry {
  return {
    code,
    name: code,
    version: '1.0.0',
    remoteEntry: `http://127.0.0.1:5175/${code}/remoteEntry.js`,
    sharedRequirements,
  }
}

function module(container: string): SubAppModule {
  const app = container.replace('forge_app_', '')
  return {
    routes: [
      { path: 'home', name: `${app}:home`, component: page },
      { path: 'details/:id', name: `${app}:details`, component: page },
    ],
  }
}

function setup(
  loadModule = vi.fn(async (container: string) => module(container)),
  timeoutMs = 1000,
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: page }],
  })
  const host = createSubAppHost({
    router,
    sharedVersions: { vue: '3.5.44', '@forge/sdk': '0.1.0' },
    loadModule,
    timeoutMs,
  })
  return { router, host, loadModule }
}

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('createSubAppHost', () => {
  it('只加载菜单可见且可加载的 Sub-App，按容器名与入口地址加载', async () => {
    const { router, host, loadModule } = setup()
    // report 未在可加载列表（套餐外或未登记），billing 不在菜单中
    host.apply(menu('demo', 'report'), [entry('demo'), entry('billing')])
    await flushPromises()

    expect(loadModule).toHaveBeenCalledOnce()
    expect(loadModule).toHaveBeenCalledWith(
      'forge_app_demo',
      'http://127.0.0.1:5175/demo/remoteEntry.js',
    )
    expect([...host.codes()]).toEqual(['demo'])
    expect(host.states.demo).toEqual({ status: 'ready' })
    expect(router.resolve('/apps/demo/details/42').name).toBe('demo:details')
    expect(router.resolve('/apps/report/home').matched).toHaveLength(0)
    expect(router.resolve('/apps/billing/home').matched).toHaveLength(0)
  })

  it('加载期间以占位路由接住深链接，加载完成后进入目标页面', async () => {
    let resolve!: (value: SubAppModule) => void
    const { router, host } = setup(vi.fn(() => new Promise<SubAppModule>((r) => (resolve = r))))
    host.apply(menu('demo'), [entry('demo')])
    await router.push('/apps/demo/details/42')

    expect(router.currentRoute.value.matched.at(-1)?.props).toEqual({ default: { app: 'demo' } })
    expect(host.states.demo).toEqual({ status: 'loading' })

    resolve(module('demo'))
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('demo:details')
    expect(router.currentRoute.value.fullPath).toBe('/apps/demo/details/42')
  })

  it('单个 Sub-App 超时或失败只影响它自己', async () => {
    vi.useFakeTimers()
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const { router, host } = setup(
      vi.fn(async (container: string) => {
        if (container === 'forge_app_slow') return new Promise<SubAppModule>(() => {})
        if (container === 'forge_app_broken') throw new Error('ERR_CONNECTION_REFUSED')
        return module(container)
      }),
    )
    host.apply(menu('slow', 'broken', 'demo'), [entry('slow'), entry('broken'), entry('demo')])
    await vi.advanceTimersByTimeAsync(1000)

    expect(host.states.slow).toEqual({ status: 'failed', reason: 'timeout' })
    expect(host.states.broken).toEqual({ status: 'failed', reason: 'loadError' })
    expect(host.states.demo).toEqual({ status: 'ready' })
    expect(router.resolve('/apps/demo/home').name).toBe('demo:home')
    // 失败的 Sub-App 仍由占位路由接住，在其区域提示错误
    expect(router.resolve('/apps/slow/home').matched.at(-1)?.props).toEqual({
      default: { app: 'slow' },
    })
  })

  it('Shell 的共享依赖低于 Sub-App 声明的最低版本时拒绝加载，不请求入口', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const { host, loadModule } = setup()
    host.apply(menu('demo'), [entry('demo', { vue: '3.6.0', '@forge/sdk': '0.1.0' })])
    await flushPromises()

    expect(loadModule).not.toHaveBeenCalled()
    expect(host.states.demo).toEqual({
      status: 'failed',
      reason: 'versionTooLow',
      detail: 'vue 3.5.44 < 3.6.0',
    })
  })

  it('菜单变化后移除不再可见的 Sub-App 路由', async () => {
    const { router, host } = setup()
    host.apply(menu('demo'), [entry('demo')])
    await flushPromises()
    host.apply([], [entry('demo')])

    expect(router.resolve('/apps/demo/home').matched).toHaveLength(0)
    expect(host.codes().size).toBe(0)
  })
})

describe('unmetRequirements', () => {
  it('按 x.y.z 数值比较，Shell 未提供的依赖也不满足', () => {
    const provided = { vue: '3.5.44', pinia: '4.1.0' }
    expect(unmetRequirements({ vue: '3.5.9', pinia: '4.1.0' }, provided)).toEqual([])
    expect(unmetRequirements({ vue: '3.10.0', 'x-lib': '1.0.0' }, provided)).toEqual([
      'vue 3.5.44 < 3.10.0',
      'x-lib - < 1.0.0',
    ])
  })
})

describe('createShellReload', () => {
  it('切换租户时先卸载全部 Sub-App、清空共享 Pinia 状态，再整页重载', async () => {
    const { router, host } = setup()
    host.apply(menu('demo'), [entry('demo')])
    await flushPromises()
    await router.push('/apps/demo/home')
    const pinia = createPinia()
    setActivePinia(pinia)
    const useCart = defineStore('cart', { state: () => ({ items: ['旧租户的数据'] }) })
    useCart()
    const navigate = vi.fn(() => {
      expect(router.currentRoute.value.fullPath).toBe('/')
      expect(router.resolve('/apps/demo/home').matched).toHaveLength(0)
      expect(pinia.state.value).toEqual({})
    })

    await createShellReload(host, pinia, navigate)()

    expect(navigate).toHaveBeenCalledOnce()
  })
})
