import type { MenuNode } from '@crane199709/sdk'
import type { NavigationEntry } from '@crane199709/ui'

/**
 * 把菜单树转为默认导航树，保持清单原有层级；没有页面的分组留给导航合并时隐藏。
 *
 * @param apps 已注册的 Sub-App 编码；指向其他 Sub-App（未在当前环境登记入口地址）的入口按不可见处理
 */
export function menuEntries(menus: MenuNode[], apps: ReadonlySet<string>): NavigationEntry[] {
  return menus.flatMap((node): NavigationEntry[] => {
    if (node.children?.length) {
      return [{ menu: node.code, title: node.name, children: menuEntries(node.children, apps) }]
    }
    return node.app && node.path && apps.has(node.app)
      ? [{ menu: node.code, title: node.name, path: `/apps/${node.app}/${node.path}` }]
      : []
  })
}
