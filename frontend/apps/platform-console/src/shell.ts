import { inject, ref, type InjectionKey } from 'vue'
import { platformClientKey, type PlatformClient } from '@forge/sdk'
import { createPlatformAccess } from './access'

/**
 * Shell 持有的当前 Operator 平台权限：随会话读取，菜单、首页与路由守卫共用。
 * 读取失败时保留上一次的权限并记下 `failed`，避免一次故障就收起正在编辑的页面。
 */
export function createConsoleAccess(client: Pick<PlatformClient, 'api' | 'session'>) {
  const access = createPlatformAccess(client)
  const failed = ref(false)
  let current: Promise<void> = Promise.resolve()

  async function read() {
    if (!client.session()) {
      access.clear()
      failed.value = false
      return
    }
    try {
      await access.load()
      failed.value = false
    } catch (error) {
      console.error(error)
      failed.value = true
    }
  }

  return {
    ...access,
    failed,
    /** 按当前会话重新读取权限。 */
    reload() {
      current = read()
      return current
    },
    /** 最近一次读取完成。 */
    ready: () => current,
  }
}

export type ConsoleAccess = ReturnType<typeof createConsoleAccess>

export const consoleAccessKey: InjectionKey<ConsoleAccess> = Symbol('consoleAccess')

export function useConsoleAccess() {
  const access = inject(consoleAccessKey)
  if (!access) throw new Error('Shell must provide ConsoleAccess')
  return access
}

export function usePlatformClient() {
  const client = inject(platformClientKey)
  if (!client) throw new Error('Shell must provide a PlatformClient')
  return client
}
