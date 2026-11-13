import type { AccessControl, TenantClient } from '@crane199709/sdk'
import {
  createRouter,
  type RouteLocationNormalized,
  type Router,
  type RouterHistory,
} from 'vue-router'
import { loginFlow } from './login-flow'

declare module 'vue-router' {
  interface RouteMeta {
    /** 无需登录即可进入。 */
    public?: boolean
    /** 不带顶栏与侧边菜单的整页。 */
    standalone?: boolean
    /** 进入页面所需的权限码；按钮级权限仍由 `v-perm` 控制，接口仍由后端校验。 */
    permission?: string | string[]
  }
}

/** 兜底路由名：不存在的路径，以及菜单中没有的 Sub-App 路径。 */
export const notFoundRoute = 'not-found'

/** 创建 Shell 路由。Sub-App 路由由 Sub-App 宿主按菜单动态注册在 `/apps/<appCode>` 下。 */
export function createShellRouter(history: RouterHistory) {
  return createRouter({
    history,
    routes: [
      { path: '/', component: () => import('./WorkbenchPage.vue') },
      {
        path: '/login',
        component: () => import('./LoginPage.vue'),
        meta: { public: true, standalone: true },
      },
      {
        path: '/select-tenant',
        component: () => import('./SelectTenantPage.vue'),
        meta: { public: true, standalone: true },
        // 可选租户只在登录后的内存中，页面刷新后无从选择，须重新登录。
        beforeEnter: (to) => (loginFlow.memberships ? true : { path: '/login', query: to.query }),
      },
      {
        path: '/forgot-password',
        component: () => import('./ForgotPasswordPage.vue'),
        meta: { public: true, standalone: true },
      },
      // 刷新 Token 被 MFA Requirement 拦下时的强制绑定步骤。此时 Token 可能已不可用，不能走需要权限的 Shell 布局。
      {
        path: '/mfa-bind',
        component: () => import('./security/MfaBindPage.vue'),
        meta: { public: true, standalone: true },
      },
      {
        path: '/ip-not-allowed',
        component: () => import('./IpNotAllowedPage.vue'),
        meta: { public: true, standalone: true },
      },
      {
        path: '/no-tenant',
        component: () => import('./NoTenantPage.vue'),
        meta: { public: true, standalone: true },
      },
      // 邀请链接的落地页，iam 的 forge.iam.invitation.accept-url 指向这里。
      {
        path: '/invitations/accept',
        component: () => import('./invitation/AcceptInvitationPage.vue'),
        meta: { public: true, standalone: true },
      },
      // 当前 Identity 的个人安全设置，任何已登录成员都可进入。
      {
        path: '/account/security',
        component: () => import('./security/AccountSecurityPage.vue'),
      },
      { path: '/notifications', component: () => import('./notification/NotificationCenter.vue') },
      {
        path: '/announcements',
        component: () => import('./announcement/AnnouncementPage.vue'),
        meta: { permission: 'announcement:manage' },
      },
      // Shell 自带的租户管理页，不来自 Sub-App 菜单。
      {
        path: '/security-settings',
        component: () => import('./security/SecuritySettingsPage.vue'),
        meta: { permission: 'iam:security:manage' },
      },
      {
        path: '/members',
        component: () => import('./member/MemberPage.vue'),
        meta: { permission: 'iam:member:manage' },
      },
      {
        path: '/org',
        component: () => import('./organization/OrganizationPage.vue'),
        meta: { permission: 'iam:org:manage' },
      },
      {
        path: '/roles',
        component: () => import('./role/RolePage.vue'),
        meta: { permission: 'iam:role:manage' },
      },
      {
        path: '/data-security',
        component: () => import('./data-security/DataSecurityPage.vue'),
        meta: { permission: 'iam:data-security:read' },
      },
      {
        path: '/dictionaries',
        component: () => import('./referencedata/DictionaryPage.vue'),
        meta: { permission: ['iam:reference-data:manage', 'iam:dictionary-history:read'] },
      },
      {
        path: '/parameters',
        component: () => import('./referencedata/ParameterPage.vue'),
        meta: { permission: 'iam:reference-data:manage' },
      },
      {
        path: '/menu-layout',
        component: () => import('./navigation/MenuLayoutPage.vue'),
        meta: { permission: 'iam:menu:manage' },
      },
      {
        path: '/audit',
        component: () => import('./audit/AuditPage.vue'),
        meta: { permission: 'iam:audit:read' },
      },
      // 技术验证诊断只在开发与 E2E 构建中注册，正式构建不打包诊断页。
      ...(import.meta.env.DEV || import.meta.env.VITE_FORGE_DIAGNOSTICS === 'true'
        ? [{ path: '/__diagnostics', component: () => import('./diagnostics/DiagnosticsPage.vue') }]
        : []),
      {
        path: '/403',
        component: () => import('./StatusPage.vue'),
        props: { status: '403' },
        meta: { standalone: true },
      },
      {
        path: '/:pathMatch(.*)*',
        name: notFoundRoute,
        component: () => import('./StatusPage.vue'),
        props: { status: '404' },
        meta: { standalone: true },
      },
    ],
  })
}

// 未登录时 Sub-App 路由尚未注册，`/apps/` 下的深链接落在兜底路由上，仍须先登录。
function requiresAuth(route: RouteLocationNormalized) {
  if (route.meta.public) return false
  return route.name !== notFoundRoute || route.path.startsWith('/apps/')
}

function loginLocation(path: string) {
  return path === '/' ? { path: '/login' } : { path: '/login', query: { redirect: path } }
}

/**
 * 安装路由守卫：未登录进入需登录页面时跳转登录并记下原路径，已登录访问登录页回首页，缺少页面权限码时进入 403；
 * 本标签页的会话结束时（退出、刷新失败）离开需登录页面。
 *
 * @param ready 最近一次权限与菜单读取完成；登录或换发 Token 后的首个导航须等它，否则权限与 Sub-App 路由仍是旧的
 */
export function installGuard(
  router: Router,
  client: Pick<
    TenantClient,
    'session' | 'onSessionChange' | 'onMfaRequired' | 'onIpNotAllowed' | 'ipDenial'
  >,
  access: Pick<AccessControl, 'has'>,
  ready: () => Promise<unknown>,
) {
  router.beforeEach(async (to) => {
    if (client.ipDenial()) return to.path === '/ip-not-allowed' ? true : '/ip-not-allowed'
    if (to.path === '/ip-not-allowed') return client.session() ? '/' : '/login'
    if (!client.session()) {
      if (to.path === '/mfa-bind') return { path: '/login', query: to.query }
      return requiresAuth(to) ? loginLocation(to.fullPath) : true
    }
    if (to.path === '/login') return '/'
    await ready()
    if (client.ipDenial()) return '/ip-not-allowed'
    // 登录前解析的 Sub-App 地址落在兜底路由上；权限读取完成、路由已注册后重新解析。
    if (to.name === notFoundRoute && router.resolve(to.fullPath).name !== notFoundRoute) {
      return to.fullPath
    }
    if (to.meta.permission && !access.has(to.meta.permission)) return '/403'
    return true
  })
  client.onIpNotAllowed(() => {
    if (router.currentRoute.value.path !== '/ip-not-allowed') void router.replace('/ip-not-allowed')
  })
  // 刷新被 MFA Requirement 拒绝：进入强制绑定步骤，绑定后回到原页面（账号安全规格 2.4）。
  client.onMfaRequired(() => {
    const current = router.currentRoute.value
    if (current.path === '/mfa-bind') return
    void router.replace({ path: '/mfa-bind', query: { redirect: current.fullPath } })
  })
  // 其他标签页引起的变化由 Shell 整页重载处理。
  client.onSessionChange((session, change) => {
    if (session || change.crossTab) return
    const current = router.currentRoute.value
    if (requiresAuth(current)) void router.replace(loginLocation(current.fullPath))
  })
}
