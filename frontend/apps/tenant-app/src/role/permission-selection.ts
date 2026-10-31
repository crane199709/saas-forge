import type { PermissionMenu } from './role-api'

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
