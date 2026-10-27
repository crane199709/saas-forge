import type { MenuNode } from '@forge/sdk'
import type { RouteRecordRaw, Router } from 'vue-router'

/** 菜单中的一个可导航入口。 */
export interface MenuLink {
  code: string
  name: string
  to: string
}

/** 菜单在导航中的展示：分组下列出其叶子入口。 */
export interface MenuSection {
  code: string
  name: string
  links: MenuLink[]
}

const routeName = (app: string) => `app:${app}`

function leaves(nodes: MenuNode[]): MenuNode[] {
  return nodes.flatMap((node) =>
    node.children?.length ? leaves(node.children) : node.app && node.path ? [node] : [],
  )
}

function link(node: MenuNode): MenuLink {
  return { code: node.code, name: node.name, to: `/apps/${node.app}/${node.path}` }
}

/** 把菜单树转为导航分组；顶层叶子节点自成一组。 */
export function menuSections(menus: MenuNode[]): MenuSection[] {
  return menus
    .map((node) => ({
      code: node.code,
      name: node.name,
      links: node.children?.length ? leaves(node.children).map(link) : leaves([node]).map(link),
    }))
    .filter((section) => section.links.length > 0)
}

/**
 * 按后端下发、已按权限裁剪的菜单注册 Sub-App 路由：只有菜单中可见的 Sub-App 才挂到 `/apps/<appCode>`，
 * 其余 Sub-App 的路由不存在，深链接也无法进入。再次调用时先移除上一次注册的路由，供登录、退出后重新生成。
 *
 * 粒度是 Sub-App：同一 Sub-App 中不在菜单里的路由（如详情页）随该 Sub-App 一起注册，页面内按钮由 `v-perm` 控制。
 *
 * @param subApps 已加载的 Sub-App 路由，按应用编码索引
 */
export function applyMenuRoutes(
  router: Router,
  menus: MenuNode[],
  subApps: Record<string, RouteRecordRaw[]>,
) {
  for (const app of Object.keys(subApps)) {
    if (router.hasRoute(routeName(app))) router.removeRoute(routeName(app))
  }
  const visible = new Set(leaves(menus).map((node) => node.app!))
  for (const [app, routes] of Object.entries(subApps)) {
    if (visible.has(app))
      router.addRoute({ path: `/apps/${app}`, name: routeName(app), children: routes })
  }
}
