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

/** 当前 Membership 的 Permission Code 与已按权限裁剪的菜单树。 */
export interface TenantAccess {
  permissions: string[]
  menus: MenuNode[]
}

/** Shell 创建并以插件安装的权限上下文类型，Sub-App 经 {@link useAccessControl} 共享同一实例。 */
export type AccessControl = ReturnType<typeof createAccessControl>

/** 权限上下文的注入 key。 */
export const accessControlKey: InjectionKey<AccessControl> = Symbol('forge.access')

const empty: TenantAccess = Object.freeze({ permissions: [], menus: [] })

/**
 * 创建当前 Membership 的权限上下文。以 `app.use()` 安装后提供注入并注册全局指令 `v-perm`：
 * `v-perm="'order:create'"` 在缺少该权限时隐藏元素，传数组时拥有任一权限即显示。权限变化时已渲染的元素随之更新。
 *
 * 权限只用于界面展示，接口仍由后端按 `@RequiresPermission` 校验。
 */
export function createAccessControl(client: Pick<TenantClient, 'api'>) {
  const state = shallowRef<TenantAccess>(empty)
  let generation = 0

  /** 从 IAM 读取当前 Token 所属 Membership 的权限与菜单；被更晚的 `load` 或 `clear` 取代时不覆盖结果。 */
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

  const control = {
    load,
    clear,
    has,
    menus,
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
