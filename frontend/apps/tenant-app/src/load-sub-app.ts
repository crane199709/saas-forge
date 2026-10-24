import { sdkRuntime } from '@forge/sdk'
import { themeTokens } from '@forge/ui'
import { loadRemote, registerRemotes } from '@module-federation/runtime'
import { ElDialog } from 'element-plus'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter, type RouteRecordRaw } from 'vue-router'

interface SubAppModule {
  routes: RouteRecordRaw[]
  sharedReferences: Record<string, unknown>
}

export async function loadSubApp() {
  const response = await fetch('/sub-apps.json', { cache: 'no-store' })
  if (!response.ok) throw new Error(`Sub-App configuration: HTTP ${response.status}`)
  const config: unknown = await response.json()
  if (
    !config ||
    typeof config !== 'object' ||
    !('remoteEntry' in config) ||
    typeof config.remoteEntry !== 'string'
  ) {
    throw new Error('Invalid Sub-App configuration')
  }
  const entry = new URL(config.remoteEntry)
  if (!['http:', 'https:'].includes(entry.protocol)) throw new Error('Invalid remoteEntry protocol')
  registerRemotes([{ name: 'demo_sub_app', entry: entry.href, type: 'module' }])
  const remote = await loadRemote<SubAppModule>('demo_sub_app/sub-app')
  if (!remote || !Array.isArray(remote.routes) || !remote.sharedReferences)
    throw new Error('Invalid Sub-App module')
  const references = {
    vue: ref,
    'vue-router': useRouter,
    pinia: defineStore,
    'element-plus': ElDialog,
    '@forge/sdk': sdkRuntime,
    '@forge/ui': themeTokens,
    'vue-i18n': useI18n,
  }
  const checks = Object.fromEntries(
    Object.entries(references).map(([name, value]) => [
      name,
      value === remote.sharedReferences[name],
    ]),
  )
  if (Object.values(checks).some((same) => !same)) throw new Error('Sub-App singleton mismatch')
  return { routes: remote.routes, checks }
}
