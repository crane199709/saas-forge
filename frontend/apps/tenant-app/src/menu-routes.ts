import type { MenuNode } from '@forge/sdk'

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

function leaves(nodes: MenuNode[]): MenuNode[] {
  return nodes.flatMap((node) =>
    node.children?.length ? leaves(node.children) : node.app && node.path ? [node] : [],
  )
}

function link(node: MenuNode): MenuLink {
  return { code: node.code, name: node.name, to: `/apps/${node.app}/${node.path}` }
}

/**
 * 把菜单树转为导航分组；顶层叶子节点自成一组。
 *
 * @param apps 已注册的 Sub-App 编码；指向其他 Sub-App（未在当前环境登记入口地址）的入口不展示
 */
export function menuSections(menus: MenuNode[], apps: ReadonlySet<string>): MenuSection[] {
  const loadable = (node: MenuNode) => apps.has(node.app!)
  return menus
    .map((node) => ({
      code: node.code,
      name: node.name,
      links: (node.children?.length ? leaves(node.children) : leaves([node]))
        .filter(loadable)
        .map(link),
    }))
    .filter((section) => section.links.length > 0)
}
