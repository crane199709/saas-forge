import { flushPromises, mount } from '@vue/test-utils'
import { createPlatformClient, platformClientKey } from '@crane199709/sdk'
import { createMemoryHistory } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { createConsoleRouter, installGuard } from './router'
import { consoleAccessKey, createConsoleAccess } from './shell'

type FakeRoute = unknown[] | ((input: string) => Response)

/**
 * 按请求路径应答的 fetch 桩；同一路径给出数组时依次应答，最后一个重复使用。
 * 租户分页列表默认为空页，其他未列出的路径返回空列表。
 */
export function fakeFetch(routes: Record<string, FakeRoute>) {
  const pending = new Map<string, FakeRoute>(
    Object.entries({ '/platform/tenants': () => page([]), ...routes }),
  )
  return (input: string) => {
    const path = new URL(input, location.origin).pathname
    const value = pending.get(path)
    if (typeof value === 'function') return Promise.resolve(value(input))
    const data = Array.isArray(value) ? (value.length > 1 ? value.shift() : value[0]) : []
    return Promise.resolve(ok(data))
  }
}

export function ok(data?: unknown) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data, traceId: 't' }))
}

export function page(items: unknown[]) {
  return new Response(
    JSON.stringify({
      code: 'ok',
      httpStatus: 200,
      data: items,
      meta: { page: { number: 1, size: 20, totalElements: items.length, totalPages: 1 } },
      traceId: 't',
    }),
  )
}

/** 平台权限读取的应答。 */
export function grants(...permissions: string[]) {
  return { roles: [], permissions }
}

export function signedIn() {
  localStorage.setItem(
    'forge.platform.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', operatorName: '华东运营' }),
  )
}

/** 与 main.ts 相同地组装 Shell：客户端、权限、路由与守卫。 */
export async function mountConsole(path: string) {
  const client = createPlatformClient(localStorage)
  const access = createConsoleAccess(client)
  const router = createConsoleRouter(createMemoryHistory())
  void access.reload()
  installGuard(router, client, access)
  await router.push(path)
  const wrapper = mount(App, {
    global: {
      plugins: [i18n, router],
      provide: { [platformClientKey]: client, [consoleAccessKey]: access },
    },
  })
  await flushPromises()
  return { wrapper, router, client, access }
}
