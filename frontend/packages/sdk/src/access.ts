import {
  inject,
  shallowRef,
  watchEffect,
  type App,
  type Directive,
  type InjectionKey,
  type ShallowRef,
} from 'vue'
import { tenantRealm, type TenantClient } from './auth'

/** 后端下发的菜单节点：叶子节点指向某个 Sub-App 的路由，分组节点只有子节点。 */
export interface MenuNode {
  code: string
  name: string
  icon?: string
  /** 叶子节点所属 Sub-App 的应用编码，Shell 将其路由挂在 `/apps/<app>` 下。 */
  app?: string
  /** 叶子节点在 Sub-App 内的相对路由。 */
  path?: string
  permission?: string
  children?: MenuNode[]
}

/** Shell 可加载的一个 Sub-App：App Registry 中当前环境的入口地址与所需的最低共享依赖版本。 */
export interface SubAppEntry {
  /** 应用编码，与菜单节点的 `app` 一致；Module Federation 容器名由 {@link subAppContainerName} 得出。 */
  code: string
  name: string
  /** 当前登记的版本，仅用于展示与排查。 */
  version: string
  /** 当前环境的 `remoteEntry.js` 地址。 */
  remoteEntry: string
  /** 所需的最低共享依赖版本（x.y.z），键为包名。 */
  sharedRequirements: Record<string, string>
}

/**
 * 当前 Membership 的 Permission Code、已按权限与套餐裁剪的菜单树，以及可加载的 Sub-App
 * （菜单引用、套餐允许且在 App Registry 中登记了当前环境入口地址的那些）。
 */
export interface TenantAccess {
  permissions: string[]
  menus: MenuNode[]
  subApps: SubAppEntry[]
}

/**
 * Sub-App 的 Module Federation 容器名：Shell 注册远程模块与 Sub-App 构建时都按应用编码由此得出，两端无需另行约定。
 * 编码为小写字母、数字与连字符，连字符换成下划线后加前缀，得到合法的标识符。
 */
export function subAppContainerName(code: string): string {
  return `forge_app_${code.replaceAll('-', '_')}`
}

/** Shell 创建并以插件安装的权限上下文类型，Sub-App 经 {@link useAccessControl} 共享同一实例。 */
export type AccessControl = ReturnType<typeof createAccessControl>

/** 权限上下文的注入 key。 */
export const accessControlKey: InjectionKey<AccessControl> = Symbol('forge.access')

const empty: TenantAccess = Object.freeze({ permissions: [], menus: [], subApps: [] })

/**
 * 创建当前 Membership 的权限上下文。以 `app.use()` 安装后提供注入并注册全局指令 `v-perm`：
 * `v-perm="'order:create'"` 在缺少该权限时隐藏元素，传数组时拥有任一权限即显示。权限变化时已渲染的元素随之更新。
 *
 * 权限只用于界面展示，接口仍由后端按 `@RequiresPermission` 校验。
 */
export function createAccessControl(client: Pick<TenantClient, 'api'>) {
  const state = shallowRef<TenantAccess>(empty)
  let generation = 0

  /** 从 IAM 读取当前 Token 所属 Membership 的权限、菜单与可加载的 Sub-App；被更晚的 `load` 或 `clear` 取代时不覆盖结果。 */
  async function load(): Promise<TenantAccess> {
    const started = ++generation
    const value = await client.api<TenantAccess>(`${tenantRealm.authPath}/access`)
    if (started === generation) state.value = value
    return value
  }

  /** 会话结束时清空，界面随即隐藏全部受控元素。 */
  function clear() {
    generation += 1
    state.value = empty
  }

  /** 是否拥有权限码；传数组时拥有任一即为真。 */
  function has(code: string | string[]): boolean {
    const permissions = state.value.permissions
    return Array.isArray(code)
      ? code.some((item) => permissions.includes(item))
      : permissions.includes(code)
  }

  /** 当前可见菜单树。 */
  function menus(): MenuNode[] {
    return state.value.menus
  }

  /** 当前可加载的 Sub-App。 */
  function subApps(): SubAppEntry[] {
    return state.value.subApps
  }

  const control = {
    load,
    clear,
    has,
    menus,
    subApps,
    install(app: App) {
      app.provide(accessControlKey, control)
      app.directive('perm', permDirective(control))
    },
  }
  return control
}

/** 在组件中读取 Shell 安装的权限上下文。 */
export function useAccessControl(): AccessControl {
  const control = inject(accessControlKey)
  if (!control) throw new Error('Shell must install an AccessControl')
  return control
}

interface PermBinding {
  value: ShallowRef<string | string[]>
  stop: () => void
}

function permDirective(
  control: Pick<AccessControl, 'has'>,
): Directive<HTMLElement, string | string[]> {
  const bindings = new WeakMap<HTMLElement, PermBinding>()
  return {
    mounted(el, binding) {
      const display = el.style.display
      const value = shallowRef(binding.value)
      // 独立的副作用跟踪权限变化，不依赖宿主组件重新渲染。
      const stop = watchEffect(() => {
        el.style.display = control.has(value.value) ? display : 'none'
      })
      bindings.set(el, { value, stop })
    },
    updated(el, binding) {
      const state = bindings.get(el)
      if (state) state.value.value = binding.value
    },
    unmounted(el) {
      bindings.get(el)?.stop()
      bindings.delete(el)
    },
  }
}
