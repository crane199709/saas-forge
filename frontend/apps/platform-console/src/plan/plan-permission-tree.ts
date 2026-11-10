import type { PermissionMenu, PermissionTree, TreePermission } from '@crane199709/ui'
import type { CatalogMenu, CatalogPermissionTree } from './plan-api'

function leaves(menus: CatalogMenu[]): CatalogMenu[] {
  return menus.flatMap((menu) => (menu.children.length ? leaves(menu.children) : [menu]))
}

/**
 * 权限码归属的 Sub-App：叶子菜单的页面权限与操作归属该叶子的 `app`，一个码可归属多个 Sub-App。
 * 没有挂在任何叶子上的码（`others`）不在其中。
 */
export function ownersOf(tree: CatalogPermissionTree): Map<string, Set<string>> {
  const owners = new Map<string, Set<string>>()
  for (const leaf of leaves(tree.menus)) {
    if (!leaf.app) continue
    for (const permission of [...(leaf.permission ? [leaf.permission] : []), ...leaf.actions]) {
      const apps = owners.get(permission.code) ?? new Set<string>()
      apps.add(leaf.app)
      owners.set(permission.code, apps)
    }
  }
  return owners
}

/**
 * 按所选 Sub-App 显示的授权树：所选 Sub-App 的每个叶子菜单成为一个根，分组节点不显示，按清单顺序排列。
 * 选了多个 Sub-App 时根节点旁显示 Sub-App 编码；共用提示只列显示中的页面。
 */
export function planTree(tree: CatalogPermissionTree, subApps: string[]): PermissionTree {
  const shown = leaves(tree.menus).filter((leaf) => leaf.app && subApps.includes(leaf.app))
  const codes = new Set(shown.map((leaf) => leaf.code))
  const visible = (permission: TreePermission): TreePermission => ({
    ...permission,
    sharedWith: permission.sharedWith.filter((code) => codes.has(code)),
  })
  return {
    menus: shown.map((leaf): PermissionMenu => ({
      code: leaf.code,
      name: leaf.name,
      icon: leaf.icon,
      permission: leaf.permission && visible(leaf.permission),
      actions: leaf.actions.map(visible),
      children: [],
      note: subApps.length > 1 ? (leaf.app ?? undefined) : undefined,
    })),
    others: tree.others,
  }
}

/** 已勾选、归属的 Sub-App 全部未选中的码：不在树中显示，保持勾选。 */
export function unselectedCodes(
  tree: CatalogPermissionTree,
  subApps: string[],
  selected: string[],
): string[] {
  const owners = ownersOf(tree)
  return selected.filter((code) => {
    const apps = owners.get(code)
    return apps !== undefined && ![...apps].some((app) => subApps.includes(app))
  })
}

/** 已不在目录（菜单树与 `others`）中的码，即业务服务已下线的权限码。 */
export function retiredCodes(tree: CatalogPermissionTree, selected: string[]): string[] {
  const known = new Set([
    ...ownersOf(tree).keys(),
    ...tree.others.map((permission) => permission.code),
  ])
  return selected.filter((code) => !known.has(code))
}

/**
 * 切换 Sub-App 后的勾选：移除切换前显示在树中、切换后不再归属任何已选 Sub-App 的码。
 * 切换前就在「所属 Sub-App 未选择」区里的码不受影响；重新选回 Sub-App 时不恢复之前的勾选。
 */
export function selectionAfterSubApps(
  tree: CatalogPermissionTree,
  selected: string[],
  before: string[],
  after: string[],
): string[] {
  const owners = ownersOf(tree)
  return selected.filter((code) => {
    const apps = [...(owners.get(code) ?? [])]
    const shownBefore = apps.some((app) => before.includes(app))
    const shownAfter = apps.some((app) => after.includes(app))
    return !shownBefore || shownAfter
  })
}
