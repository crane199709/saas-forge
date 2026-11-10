import type { NavigationEntry } from '@crane199709/ui'

/** 进入导航布局的 Shell Page；首页与通知中心固定在导航顶部，不在其中。 */
export interface ShellPage {
  /** 页面键，Navigation Layout 以它引用页面。 */
  key: string
  path: string
  title: string
  /** iam 的管理权限码，授权被撤销后随权限刷新隐藏。 */
  permission: string
}

type Translate = (key: string) => string

/** Shell 自带的租户管理页，按默认顺序。 */
export function shellPages(t: Translate): ShellPage[] {
  return [
    {
      key: 'announcements',
      path: '/announcements',
      title: '公告管理',
      permission: 'announcement:manage',
    },
    { key: 'members', path: '/members', title: t('member.nav'), permission: 'iam:member:manage' },
    { key: 'org', path: '/org', title: t('org.nav'), permission: 'iam:org:manage' },
    { key: 'roles', path: '/roles', title: t('role.nav'), permission: 'iam:role:manage' },
    { key: 'audit', path: '/audit', title: t('audit.nav'), permission: 'iam:audit:read' },
    {
      key: 'dictionaries',
      path: '/dictionaries',
      title: t('referenceData.dictionary.nav'),
      permission: 'iam:reference-data:manage',
    },
    {
      key: 'parameters',
      path: '/parameters',
      title: t('referenceData.parameter.nav'),
      permission: 'iam:reference-data:manage',
    },
    {
      key: 'security-settings',
      path: '/security-settings',
      title: t('securitySettings.nav'),
      permission: 'iam:security:manage',
    },
    {
      key: menuLayoutPage,
      path: '/menu-layout',
      title: t('menuLayout.nav'),
      permission: 'iam:menu:manage',
    },
  ]
}

const menuLayoutPage = 'menu-layout'

/**
 * 默认导航树：Shell Page 按默认顺序，之后是清单菜单；菜单管理页放在最后。
 *
 * @param pages 要列出的 Shell Page
 * @param menus 清单菜单
 */
export function defaultNavigation(pages: ShellPage[], menus: NavigationEntry[]): NavigationEntry[] {
  const entry = (page: ShellPage): NavigationEntry => ({
    page: page.key,
    title: page.title,
    path: page.path,
  })
  return [
    ...pages.filter((page) => page.key !== menuLayoutPage).map(entry),
    ...menus,
    ...pages.filter((page) => page.key === menuLayoutPage).map(entry),
  ]
}
