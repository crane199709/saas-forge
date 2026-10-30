import ElementPlus, { ElDialog } from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 内置文案默认是英文，与第一期只支持简体中文保持一致
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createAccessControl, createTenantClient, sdkRuntime, tenantClientKey } from '@forge/sdk'
import { themeTokens } from '@forge/ui'
import { loadRemote, registerRemotes } from '@module-federation/runtime'
import { createPinia, defineStore } from 'pinia'
import { createApp, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createRouter, createWebHistory, useRouter } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { createShellReload, reloadShellKey, subAppHostKey } from './shell'
import { createSubAppHost, type SubAppModule } from './sub-app-host'
import AuditPage from './audit/AuditPage.vue'
import AcceptInvitationPage from './invitation/AcceptInvitationPage.vue'
import MemberPage from './member/MemberPage.vue'
import OrganizationPage from './organization/OrganizationPage.vue'
import RolePage from './role/RolePage.vue'

document.title = i18n.global.t('app.title')

const client = createTenantClient(sessionStorage)
const access = createAccessControl(client)
const pinia = createPinia()
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: { render: () => null } },
    // Shell 自带的租户管理页，不来自 Sub-App 菜单；是否拥有 iam 的管理权限码由 IAM 判断。
    { path: '/org', component: OrganizationPage },
    { path: '/roles', component: RolePage },
    { path: '/members', component: MemberPage },
    { path: '/audit', component: AuditPage },
    // 邀请链接的落地页，iam 的 forge.iam.invitation.accept-url 指向这里；无需登录。
    { path: '/invitations/accept', component: AcceptInvitationPage },
    // 菜单中没有的 Sub-App 路由不存在，深链接落到这里而不是空白报错。
    { path: '/:pathMatch(.*)*', component: { render: () => null } },
  ],
})

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
    '@forge/sdk': sdkRuntime,
    '@forge/ui': themeTokens,
    'vue-i18n': useI18n,
  },
})

// 路由由后端下发的菜单与可加载的 Sub-App 生成：会话结束时移除全部 Sub-App 路由，进入新的 Membership 后重新注册。
async function refreshAccess() {
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
  // 新的菜单不再包含当前所在的 Sub-App 时，已渲染的页面属于失去权限的路由，回到首页。
  const current = router.currentRoute.value.fullPath
  if (
    current.startsWith('/apps/') &&
    !router.resolve(current).matched.some((route) => route.name)
  ) {
    await router.replace('/')
  }
}
// 首次导航前完成注册，刷新页面时 Sub-App 深链接可直接进入；Sub-App 本身在后台加载，不阻塞 Shell。
await refreshAccess()
let accessToken = client.session()?.accessToken
// 除 Membership 变化（登录、选择租户、退出）外，Token 刷新也要重新读取：角色或授权变更使旧 Token 失效，
// 刷新换到新 Token 后菜单与按钮随之按新权限变化。
client.onSessionChange((session) => {
  if (session?.accessToken === accessToken) return
  accessToken = session?.accessToken
  void refreshAccess()
})

createApp(App)
  .provide(tenantClientKey, client)
  .provide(subAppHostKey, host)
  .provide(reloadShellKey, createShellReload(host, pinia))
  .use(access)
  .use(pinia)
  .use(i18n)
  .use(router)
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')
