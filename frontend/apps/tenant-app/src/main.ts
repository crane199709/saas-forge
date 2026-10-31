// 全量引入 Element Plus，不按需引入：Sub-App 以 import: false 共享 Shell 的 Element Plus，自身不打包组件与样式，
// Shell 少带任何组件都会让用到它的 Sub-App 运行时报错。
import ElementPlus, { ElDialog } from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 内置文案默认是英文，与第一期只支持简体中文保持一致
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {
  createAccessControl,
  createTenantClient,
  sdkRuntime,
  tenantClientKey,
} from '@crane199709/sdk'
import { themeTokens } from '@crane199709/ui'
import { loadRemote, registerRemotes } from '@module-federation/runtime'
import { createPinia, defineStore } from 'pinia'
import { createApp, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createWebHistory, useRouter } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { createShellRouter, installGuard, notFoundRoute } from './router'
import { createShellReload, reloadShellKey, subAppHostKey } from './shell'
import { createSubAppHost, type SubAppModule } from './sub-app-host'

document.title = i18n.global.t('app.title')

// 会话存于 localStorage，同一浏览器的标签页共享登录并始终处于同一租户（ADR-0017）。
const client = createTenantClient(localStorage)
const access = createAccessControl(client)
const pinia = createPinia()
const router = createShellRouter(createWebHistory())

// 入口地址来自 IAM 下发的 App Registry 副本，不编译进 Shell；修改注册表中的地址后刷新即加载新版本。
const host = createSubAppHost({
  router,
  sharedVersions: __SHARED_VERSIONS__,
  timeoutMs: 15_000,
  async loadModule(container, remoteEntry) {
    const entry = new URL(remoteEntry)
    if (!['http:', 'https:'].includes(entry.protocol))
      throw new Error('Invalid remoteEntry protocol')
    registerRemotes([{ name: container, entry: entry.href, type: 'module' }])
    const module = await loadRemote<SubAppModule>(`${container}/sub-app`)
    if (!module) throw new Error(`Sub-App ${container} exposes no ./sub-app`)
    return module
  },
  sharedReferences: {
    vue: ref,
    'vue-router': useRouter,
    pinia: defineStore,
    'element-plus': ElDialog,
    '@crane199709/sdk': sdkRuntime,
    '@crane199709/ui': themeTokens,
    'vue-i18n': useI18n,
  },
})

// 路由由后端下发的菜单与可加载的 Sub-App 生成：会话结束时移除全部 Sub-App 路由，进入新的 Membership 后重新注册。
async function loadAccess() {
  if (!client.session()) {
    access.clear()
  } else {
    try {
      await access.load()
    } catch (error) {
      console.error(error)
      access.clear()
    }
  }
  host.apply(access.menus(), access.subApps())
}
// 路由守卫等待的是读取与注册本身，不含其后的导航，否则这次导航会等待自己。
let accessReady: Promise<void>
async function refreshAccess() {
  accessReady = loadAccess()
  await accessReady
  // 新的菜单不再包含当前所在的 Sub-App 时，已渲染的页面属于失去权限的路由，回到首页。
  const current = router.currentRoute.value.fullPath
  if (current.startsWith('/apps/') && router.resolve(current).name === notFoundRoute) {
    await router.replace('/')
  }
}
// 首次导航前完成注册，刷新页面时 Sub-App 深链接可直接进入；Sub-App 本身在后台加载，不阻塞 Shell。
await refreshAccess()
installGuard(router, client, access, () => accessReady)
const reloadShell = createShellReload(host, pinia)
let accessToken = client.session()?.accessToken
let membershipId = client.session()?.membershipId
// 除 Membership 变化（登录、选择租户、退出）外，Token 刷新也要重新读取：角色或授权变更使旧 Token 失效，
// 刷新换到新 Token 后菜单与按钮随之按新权限变化。
client.onSessionChange((session, change) => {
  const membershipChanged = session?.membershipId !== membershipId
  membershipId = session?.membershipId
  // 其他标签页切换租户、登录或退出：与本标签页切换相同，丢弃旧租户的 Sub-App 与共享状态后回首页，
  // 首页按新会话显示登录或租户首页。
  if (change.crossTab && membershipChanged) {
    void reloadShell()
    return
  }
  if (session?.accessToken === accessToken) return
  accessToken = session?.accessToken
  void refreshAccess()
})

createApp(App)
  .provide(tenantClientKey, client)
  .provide(subAppHostKey, host)
  .provide(reloadShellKey, reloadShell)
  .use(access)
  .use(pinia)
  .use(i18n)
  .use(router)
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')
