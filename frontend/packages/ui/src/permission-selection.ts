/** 菜单节点上可勾选的 Permission Code。 */
export interface TreePermission {
  code: string
  name: string
  /** 同时挂着该权限码的其他菜单编码，勾选时一起生效。 */
  sharedWith: string[]
}

/** 授权树的菜单节点：叶子是页面，带 `children` 的是分组。 */
export interface PermissionMenu {
  code: string
  name: string
  icon: string | null
  /** 页面权限；不挂权限码的页面始终可见，页面本身不可勾选。 */
  permission: TreePermission | null
  actions: TreePermission[]
  children: PermissionMenu[]
  /** 显示在节点名称旁的小字，如所属 Sub-App。 */
  note?: string
}

/** 未挂在菜单上的权限码。 */
export interface GrantablePermission {
  code: string
  name: string
  service: string
  group: string | null
}

/** 授权树：菜单与「功能权限」区。 */
export interface PermissionTree {
  menus: PermissionMenu[]
  others: GrantablePermission[]
}

/** 调用方追加的权限分区，显示在「功能权限」区之后，逐项勾选。 */
export interface PermissionSection {
  key: string
  title: string
  permissions: { code: string; name: string }[]
}

export function menuRows(
  menus: PermissionMenu[],
  depth = 0,
): { menu: PermissionMenu; depth: number }[] {
  return menus.flatMap((menu) => [{ menu, depth }, ...menuRows(menu.children, depth + 1)])
}

export function menuCodes(menu: PermissionMenu): string[] {
  return [
    ...new Set([
      ...(menu.permission ? [menu.permission.code] : []),
      ...menu.actions.map((action) => action.code),
      ...menu.children.flatMap(menuCodes),
    ]),
  ]
}

/** 同一码可同时是页面权限与另一页面的操作，联动需要传播到所有引用位置。 */
export function togglePermissions(
  selected: string[],
  codes: string[],
  checked: boolean,
  menus: PermissionMenu[],
): string[] {
  const result = new Set(selected)
  const pending = [...codes]
  const visited = new Set<string>()
  const rows = menuRows(menus)
  while (pending.length) {
    const code = pending.shift()!
    if (visited.has(code)) continue
    visited.add(code)
    if (checked) result.add(code)
    else result.delete(code)
    for (const { menu } of rows) {
      if (checked && menu.permission && menu.actions.some((action) => action.code === code)) {
        pending.push(menu.permission.code)
      }
      if (!checked && menu.permission?.code === code) {
        pending.push(...menu.actions.map((action) => action.code))
      }
    }
  }
  return [...result]
}

/** 按服务、分组把连续的权限码分段，保持接口给出的顺序。 */
export function groupPermissions(permissions: GrantablePermission[]) {
  const groups: {
    key: string
    service: string
    group: string | null
    permissions: GrantablePermission[]
  }[] = []
  for (const permission of permissions) {
    const key = `${permission.service}\u0000${permission.group ?? ''}`
    const last = groups.at(-1)
    if (last?.key === key) last.permissions.push(permission)
    else
      groups.push({
        key,
        service: permission.service,
        group: permission.group,
        permissions: [permission],
      })
  }
  return groups
}
