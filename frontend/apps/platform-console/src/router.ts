import type { PlatformClient } from '@forge/sdk'
import {
  createRouter,
  type RouteLocationNormalizedLoaded,
  type Router,
  type RouterHistory,
} from 'vue-router'
import { permissions } from './access'
import AuditPage from './audit/AuditPage.vue'
import HomePage from './HomePage.vue'
import LoginPage from './LoginPage.vue'
import { safeRedirect } from './login-flow'
import OperatorPage from './operator/OperatorPage.vue'
import PlanPage from './plan/PlanPage.vue'
import PlatformRolePage from './role/PlatformRolePage.vue'
import type { ConsoleAccess } from './shell'
import StatusPage from './StatusPage.vue'
import SubAppPage from './subapp/SubAppPage.vue'
import TenantPage from './tenant/TenantPage.vue'

declare module 'vue-router' {
  interface RouteMeta {
    /** 无需登录即可进入。 */
    public?: boolean
    /** 不带顶栏与侧边菜单的整页。 */
    standalone?: boolean
    /** 进入页面所需的平台权限码；页内操作仍按各自权限码控制，接口仍由服务端校验。 */
    permission?: string
  }
}

/** 平台管理页，按此顺序列在侧边菜单中；`/` 跳到其中第一个有权限的页面。 */
export const consolePages = [
  {
    path: '/tenants',
    title: 'tenant.title',
    permission: permissions.tenantRead,
    component: TenantPage,
  },
  { path: '/plans', title: 'plan.title', permission: permissions.planManage, component: PlanPage },
  {
    path: '/sub-apps',
    title: 'subApp.title',
    permission: permissions.subAppManage,
    component: SubAppPage,
  },
  {
    path: '/operators',
    title: 'operator.title',
    permission: permissions.operatorManage,
    component: OperatorPage,
  },
  {
    path: '/roles',
    title: 'platformRole.title',
    permission: permissions.roleManage,
    component: PlatformRolePage,
  },
  { path: '/audit', title: 'audit.title', permission: permissions.auditRead, component: AuditPage },
] as const

/** 兜底路由名：不存在的路径。 */
const notFoundRoute = 'not-found'

export function createConsoleRouter(history: RouterHistory) {
  return createRouter({
    history,
    routes: [
      { path: '/', component: HomePage },
      { path: '/login', component: LoginPage, meta: { public: true, standalone: true } },
      ...consolePages.map((page) => ({
        path: page.path,
        component: page.component,
        meta: { permission: page.permission },
      })),
      { path: '/403', component: StatusPage, props: { status: '403' }, meta: { standalone: true } },
      {
        path: '/:pathMatch(.*)*',
        name: notFoundRoute,
        component: StatusPage,
        props: { status: '404' },
        meta: { public: true, standalone: true },
      },
    ],
  })
}

function loginLocation(path: string) {
  return path === '/' ? { path: '/login' } : { path: '/login', query: { redirect: path } }
}

/**
 * 安装路由守卫：未登录进入需登录页面时跳转登录并记下原路径，已登录访问登录页回首页，`/` 跳到第一个有权限的页面，
 * 缺少页面权限码时进入 403。同时跟随会话变化：Token 换发后重新读取权限，当前页面失去权限时进入 403；
 * 会话结束时（退出、刷新失败、其他标签页退出）回到登录页；其他标签页登录时，停在登录页的标签页随之进入。
 */
export function installGuard(
  router: Router,
  client: Pick<PlatformClient, 'session' | 'onSessionChange'>,
  access: Pick<ConsoleAccess, 'has' | 'failed' | 'reload' | 'ready'>,
) {
  router.beforeEach(async (to) => {
    if (!client.session()) return to.meta.public ? true : loginLocation(to.fullPath)
    if (to.path === '/login') return '/'
    // 登录或换发 Token 后的首个导航须等权限读取完成，否则按旧权限判定。
    await access.ready()
    // 权限读取失败时无从判定，回首页提示失败，而不是误报无权限。
    const permission = to.meta.permission
    if (permission && !access.has(permission)) return access.failed.value ? '/' : '/403'
    if (to.path === '/') {
      const first = consolePages.find((page) => access.has(page.permission))
      if (first) return first.path
    }
    return true
  })

  let token = client.session()?.accessToken ?? null
  client.onSessionChange((session, change) => {
    const current = router.currentRoute.value
    if (!session) {
      token = null
      void access.reload()
      if (!current.meta.public) void router.replace(loginLocation(current.fullPath))
      return
    }
    if (session.accessToken === token) return
    token = session.accessToken
    // 登录导航由登录页发起；守卫等待的读取须在导航之前开始。
    const reading = access.reload()
    if (change.crossTab && current.path === '/login') {
      void router.replace(safeRedirect(current.query.redirect))
    } else {
      void reading.then(() => leaveIfForbidden(router, access, current))
    }
  })
}

// 权限变化后当前页面已不可进入时离开；导航期间路由已变化则交给那次导航的守卫判定。
function leaveIfForbidden(
  router: Router,
  access: Pick<ConsoleAccess, 'has' | 'failed'>,
  route: RouteLocationNormalizedLoaded,
) {
  if (router.currentRoute.value.fullPath !== route.fullPath || access.failed.value) return
  const permission = route.meta.permission
  if (permission && !access.has(permission)) void router.replace('/403')
  else if (route.path === '/' && consolePages.some((page) => access.has(page.permission))) {
    void router.replace('/')
  }
}
