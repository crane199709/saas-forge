import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import {
  createAccessControl,
  createTenantClient,
  tenantClientKey,
  type TenantAccess,
} from '@forge/sdk'
import { createMemoryHistory } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { createShellRouter, installGuard } from './router'
import { subAppHostKey } from './shell'
import type { SubAppHost } from './sub-app-host'

/** 按请求路径应答的 fetch 桩；同一路径给出数组时依次应答，最后一个重复使用。未列出的路径返回 404。 */
export function fakeFetch(
  routes: Record<string, Response | ((input: string) => Response) | Response[]>,
) {
  const pending = new Map(Object.entries(routes).map(([path, value]) => [path, value]))
  return (input: string) => {
    const path = new URL(input, location.origin).pathname
    const value = pending.get(path)
    if (Array.isArray(value)) {
      const next = value.length > 1 ? value.shift()! : value[0]!.clone()
      return Promise.resolve(next)
    }
    if (typeof value === 'function') return Promise.resolve(value(input))
    if (value) return Promise.resolve(value.clone())
    return Promise.resolve(new Response('', { status: 404 }))
  }
}

/**
 * 与 main.ts 相同地组装 Shell：路由、守卫与权限上下文。会话变化时重新读取权限，守卫等待读取完成。
 *
 * @param access 权限读取，默认无任何权限与菜单
 */
export async function mountShell(
  path: string,
  options: {
    access?: () => Promise<Partial<TenantAccess>>
    host?: SubAppHost
  } = {},
) {
  const client = createTenantClient(sessionStorage)
  const read = options.access ?? (() => Promise.resolve({}))
  const access = createAccessControl({
    api: async <T>() => ({ permissions: [], menus: [], subApps: [], ...(await read()) }) as T,
  })
  const router = createShellRouter(createMemoryHistory())
  const load = () => (client.session() ? access.load().catch(() => access.clear()) : access.clear())
  let ready: Promise<unknown> = Promise.resolve(load())
  client.onSessionChange(() => {
    ready = Promise.resolve(load())
  })
  installGuard(router, client, access, () => ready)
  await router.push(path)
  const wrapper = mount(App, {
    global: {
      plugins: [i18n, ElementPlus, access, router],
      provide: {
        [tenantClientKey]: client,
        ...(options.host ? { [subAppHostKey]: options.host } : {}),
      },
    },
  })
  await flushPromises()
  return { wrapper, router, client, access }
}
