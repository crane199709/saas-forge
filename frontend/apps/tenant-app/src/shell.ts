import type { Pinia } from 'pinia'
import { ref, type InjectionKey } from 'vue'
import type { SubAppHost } from './sub-app-host'

/** Shell 中的 Sub-App 宿主，供导航与占位区域读取加载状态。 */
export const subAppHostKey: InjectionKey<SubAppHost> = Symbol('forge.shell.subApps')

/** 切换或退出租户后重载 Shell：卸载全部 Sub-App、清空共享 Pinia 状态，再整页回到首页。 */
export const reloadShellKey: InjectionKey<() => void | Promise<void>> = Symbol('forge.shell.reload')

/**
 * 创建切换租户时的重载：Sub-App 与共享 Pinia 中都可能有属于旧租户的状态，先卸载全部 Sub-App、清空共享状态，再整页重载，
 * 以新 Token 重新加载菜单与 Sub-App。
 *
 * @param navigate 整页跳转，默认回到首页
 */
export function createShellReload(
  host: Pick<SubAppHost, 'unloadAll'>,
  pinia: Pinia,
  navigate: () => void = () => location.assign('/'),
) {
  return async () => {
    await host.unloadAll()
    pinia.state.value = {}
    navigate()
  }
}

/** 技术验证用的主题切换：验证 Shell 与 Sub-App 共用主题 token。 */
export const alternateTheme = ref(false)
