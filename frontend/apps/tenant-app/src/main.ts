import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 内置文案默认是英文，与第一期只支持简体中文保持一致
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createAccessControl, createTenantClient, tenantClientKey } from '@forge/sdk'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { loadSubApp } from './load-sub-app'
import { applyMenuRoutes } from './menu-routes'
import AcceptInvitationPage from './invitation/AcceptInvitationPage.vue'
import MemberPage from './member/MemberPage.vue'
import OrganizationPage from './organization/OrganizationPage.vue'
import RolePage from './role/RolePage.vue'

document.title = i18n.global.t('app.title')

const client = createTenantClient(sessionStorage)
const access = createAccessControl(client)
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: { render: () => null } },
    // Shell 自带的租户管理页，不来自 Sub-App 菜单；是否拥有 iam 的管理权限码由 IAM 判断。
    { path: '/org', component: OrganizationPage },
    { path: '/roles', component: RolePage },
    { path: '/members', component: MemberPage },
    // 邀请链接的落地页，iam 的 forge.iam.invitation.accept-url 指向这里；无需登录。
    { path: '/invitations/accept', component: AcceptInvitationPage },
    // 菜单中没有的 Sub-App 路由不存在，深链接落到这里而不是空白报错。
    { path: '/:pathMatch(.*)*', component: { render: () => null } },
  ],
})
let subApps: Record<string, RouteRecordRaw[]> = {}
let sharedChecks: Record<string, boolean> = {}
let loadError = ''
try {
  const subApp = await loadSubApp()
  subApps = { demo: subApp.routes }
  sharedChecks = subApp.checks
} catch (error) {
  console.error(error)
  loadError = i18n.global.t('spike.loadError')
}

// 路由由后端下发的菜单生成：会话结束时移除全部 Sub-App 路由，进入新的 Membership 后按其菜单重新注册。
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
  applyMenuRoutes(router, access.menus(), subApps)
  // 新的菜单不再包含当前所在的 Sub-App 时，已渲染的页面属于失去权限的路由，回到首页。
  const current = router.currentRoute.value.fullPath
  if (
    current.startsWith('/apps/') &&
    !router.resolve(current).matched.some((route) => route.name)
  ) {
    await router.replace('/')
  }
}
// 首次导航前完成注册，刷新页面时 Sub-App 深链接可直接进入。
await refreshAccess()
let accessToken = client.session()?.accessToken
// 除 Membership 变化（登录、选择租户、退出）外，Token 刷新也要重新读取：角色或授权变更使旧 Token 失效，
// 刷新换到新 Token 后菜单与按钮随之按新权限变化。
client.onSessionChange((session) => {
  if (session?.accessToken === accessToken) return
  accessToken = session?.accessToken
  void refreshAccess()
})

createApp(App, { sharedChecks, loadError })
  .provide(tenantClientKey, client)
  .use(access)
  .use(createPinia())
  .use(i18n)
  .use(router)
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')
