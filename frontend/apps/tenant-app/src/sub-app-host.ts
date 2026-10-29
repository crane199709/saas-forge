import { subAppContainerName, type MenuNode, type SubAppEntry } from '@forge/sdk'
import { reactive, shallowRef } from 'vue'
import type { RouteRecordRaw, Router } from 'vue-router'
import SubAppStatus from './SubAppStatus.vue'

/** Sub-App 暴露的 `./sub-app` 模块。`sharedReferences` 只是工单 02 的诊断接口，可以不导出。 */
export interface SubAppModule {
  routes: RouteRecordRaw[]
  sharedReferences?: Record<string, unknown>
}

/** 一个 Sub-App 的加载状态；失败原因是文案 key 与参数，由展示处翻译。 */
export type SubAppState =
  | { status: 'loading' }
  | { status: 'ready' }
  | { status: 'failed'; reason: 'timeout' | 'versionTooLow' | 'loadError'; detail?: string }

export interface SubAppHostOptions {
  router: Router
  /** Shell 提供的共享依赖版本，键为包名。 */
  sharedVersions: Record<string, string>
  /** 按容器名与入口地址加载 `./sub-app` 模块；由 Module Federation 运行时实现，测试中替换。 */
  loadModule: (container: string, remoteEntry: string) => Promise<SubAppModule>
  /** 单个 Sub-App 的加载超时（毫秒）。 */
  timeoutMs: number
  /** Shell 中共享依赖的运行时引用，Sub-App 导出 `sharedReferences` 时逐一比较是否为同一实例。 */
  sharedReferences?: Record<string, unknown>
}

const routeName = (app: string) => `app:${app}`

function leaves(nodes: MenuNode[]): MenuNode[] {
  return nodes.flatMap((node) => (node.children?.length ? leaves(node.children) : [node]))
}

/** x.y.z 的数值比较；预发布等后缀不参与比较。 */
function lowerThan(version: string, minimum: string): boolean {
  const parse = (value: string) => value.split(/[.+-]/).slice(0, 3).map(Number)
  const [a, b] = [parse(version), parse(minimum)]
  for (let index = 0; index < 3; index++) {
    if ((a[index] ?? 0) !== (b[index] ?? 0)) return (a[index] ?? 0) < (b[index] ?? 0)
  }
  return false
}

/**
 * Sub-App 声明的最低共享依赖版本中，Shell 未提供或提供的版本更低的那些，形如 `vue 3.5.44 < 9.0.0`。
 *
 * @param requirements Sub-App 在 App Registry 中声明的最低版本
 * @param provided Shell 提供的版本
 */
export function unmetRequirements(
  requirements: Record<string, string>,
  provided: Record<string, string>,
): string[] {
  return Object.entries(requirements)
    .filter(([name, minimum]) => !provided[name] || lowerThan(provided[name], minimum))
    .map(([name, minimum]) => `${name} ${provided[name] ?? '-'} < ${minimum}`)
}

class TimeoutError extends Error {}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new TimeoutError(`Timed out after ${ms} ms`)), ms)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

/**
 * Shell 中的 Sub-App 宿主：按后端下发的菜单与可加载的 Sub-App 注册路由，各 Sub-App 独立加载。
 *
 * 可见的 Sub-App 先以占位路由挂到 `/apps/<code>`（显示加载中或错误），不阻塞 Shell 首页与导航；
 * 加载完成后换成 Sub-App 的路由，正停留在该 Sub-App 下时重新解析当前地址。单个 Sub-App 超时、失败或共享依赖版本不满足时，
 * 只有它的区域提示错误，其他 Sub-App 照常可用。已加载的模块在本页内复用；入口地址变化在整页刷新后生效（工单 02 结论）。
 */
export function createSubAppHost(options: SubAppHostOptions) {
  const { router } = options
  const states = reactive<Record<string, SubAppState>>({})
  /** 各 Sub-App 共享依赖的运行时引用检查结果，仅在 Sub-App 导出 `sharedReferences` 时存在。 */
  const checks = reactive<Record<string, Record<string, boolean>>>({})
  const modules = new Map<string, Promise<SubAppModule>>()
  const loaded = new Map<string, SubAppModule>()
  // 导航随之变化，须是响应式的
  const registered = shallowRef<ReadonlySet<string>>(new Set())

  function register(code: string) {
    if (router.hasRoute(routeName(code))) router.removeRoute(routeName(code))
    const module = loaded.get(code)
    router.addRoute(
      module
        ? { path: `/apps/${code}`, name: routeName(code), children: module.routes }
        : {
            path: `/apps/${code}`,
            name: routeName(code),
            children: [{ path: ':rest(.*)*', component: SubAppStatus, props: { app: code } }],
          },
    )
  }

  async function fetchModule(entry: SubAppEntry): Promise<SubAppModule> {
    const unmet = unmetRequirements(entry.sharedRequirements, options.sharedVersions)
    // 不满足时不请求 remoteEntry：Sub-App 在低版本依赖上运行的行为未经验证
    if (unmet.length > 0) throw Object.assign(new Error('versionTooLow'), { unmet })
    const module = await withTimeout(
      options.loadModule(subAppContainerName(entry.code), entry.remoteEntry),
      options.timeoutMs,
    )
    if (!module || !Array.isArray(module.routes)) throw new Error('Invalid Sub-App module')
    if (module.sharedReferences && options.sharedReferences) {
      const result = Object.fromEntries(
        Object.entries(options.sharedReferences).map(([name, value]) => [
          name,
          value === module.sharedReferences![name],
        ]),
      )
      checks[entry.code] = result
      if (Object.values(result).some((same) => !same)) throw new Error('Sub-App singleton mismatch')
    }
    return module
  }

  function load(entry: SubAppEntry) {
    if (modules.has(entry.code)) return
    states[entry.code] = { status: 'loading' }
    const pending = fetchModule(entry)
    modules.set(entry.code, pending)
    pending.then(
      async (module) => {
        loaded.set(entry.code, module)
        states[entry.code] = { status: 'ready' }
        if (!registered.value.has(entry.code)) return
        register(entry.code)
        // 占位路由已渲染时换成 Sub-App 的路由，重新解析当前地址，深链接直接进入目标页面
        const current = router.currentRoute.value.fullPath
        if (current === `/apps/${entry.code}` || current.startsWith(`/apps/${entry.code}/`)) {
          await router.replace(current)
        }
      },
      (error: unknown) => {
        console.error(`Failed to load Sub-App ${entry.code}`, error)
        const unmet = (error as { unmet?: string[] }).unmet
        states[entry.code] = unmet
          ? { status: 'failed', reason: 'versionTooLow', detail: unmet.join('，') }
          : { status: 'failed', reason: error instanceof TimeoutError ? 'timeout' : 'loadError' }
      },
    )
  }

  /**
   * 按菜单与可加载的 Sub-App 重新注册路由：只有菜单中可见、且在可加载列表中的 Sub-App 才挂到 `/apps/<code>` 并开始加载，
   * 其余 Sub-App 的路由不存在，也不请求其入口。再次调用时先移除上一次注册的路由，供登录、退出、Token 刷新后重新生成。
   *
   * 粒度是 Sub-App：同一 Sub-App 中不在菜单里的路由（如详情页）随该 Sub-App 一起注册，页面内按钮由 `v-perm` 控制。
   */
  function apply(menus: MenuNode[], subApps: SubAppEntry[]) {
    for (const code of registered.value) {
      if (router.hasRoute(routeName(code))) router.removeRoute(routeName(code))
    }
    const visible = new Set(leaves(menus).map((node) => node.app))
    const entries = subApps.filter((entry) => visible.has(entry.code))
    registered.value = new Set(entries.map((entry) => entry.code))
    for (const entry of entries) {
      register(entry.code)
      load(entry)
    }
  }

  /** 已注册（菜单可见且可加载）的 Sub-App 编码。 */
  function codes(): ReadonlySet<string> {
    return registered.value
  }

  /** 卸载全部 Sub-App：回到首页使其组件卸载，再移除其路由。切换租户时在重载前调用。 */
  async function unloadAll() {
    await router.replace('/')
    apply([], [])
  }

  return { apply, codes, unloadAll, states, checks }
}

export type SubAppHost = ReturnType<typeof createSubAppHost>
