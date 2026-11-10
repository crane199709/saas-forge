import type { NavigationLayout, NavigationLayoutNode } from '@crane199709/sdk'
import type { MenuItem } from './AppLayout'

/**
 * 默认导航树的节点，恰有 `page`（Shell Page 的页面键）或 `menu`（清单菜单编码）之一，作为菜单项的 `key`。
 * 带 `path` 的是页面，带 `children` 的是分组。
 */
export interface NavigationEntry {
  page?: string
  menu?: string
  title: string
  path?: string
  children?: NavigationEntry[]
}

const refOf = (node: { page?: string; menu?: string }) =>
  node.page !== undefined ? `page:${node.page}` : `menu:${node.menu}`

function toItem(entry: NavigationEntry): MenuItem {
  const key = (entry.page ?? entry.menu)!
  return entry.children
    ? { key, title: entry.title, children: [] }
    : { key, title: entry.title, path: entry.path }
}

// 分组只在含有页面时显示，按层级自下而上去掉空分组。
function prune(items: MenuItem[]): MenuItem[] {
  return items.flatMap((item) => {
    if (!item.children) return [item]
    const children = prune(item.children)
    return children.length ? [{ ...item, children }] : []
  })
}

/**
 * 按 Navigation Layout 排列导航。默认树只含当前可见的节点，布局只决定位置：
 * 布局中不可见或已不存在的引用跳过；可见但布局中没有的节点放到默认树中最近的、已在布局中的祖先分组末尾，
 * 没有这样的祖先时放到顶层末尾；没有页面的分组不显示。
 *
 * @param defaults 默认导航树，已按权限、套餐等裁剪为可见节点，顺序即没有布局时的顺序
 * @param layout 已保存的布局；null 表示按默认树显示
 */
export function arrangeNavigation(
  defaults: NavigationEntry[],
  layout: NavigationLayout | null | undefined,
): MenuItem[] {
  const visible = new Map<string, NavigationEntry>()
  const index = (entries: NavigationEntry[]) =>
    entries.forEach((entry) => {
      visible.set(refOf(entry), entry)
      index(entry.children ?? [])
    })
  index(defaults)

  // 已放置的节点及其菜单项；后续把布局中没有的子节点追加到这里。
  const placed = new Map<string, MenuItem>()
  const fromLayout = (nodes: NavigationLayoutNode[]): MenuItem[] =>
    nodes.flatMap((node) => {
      const children = node.children ?? []
      if (node.group !== undefined) {
        return [{ key: node.group, title: node.name ?? '', children: fromLayout(children) }]
      }
      const ref = refOf(node)
      const entry = visible.get(ref)
      // 不可见的引用跳过，其中仍可见的节点留在它原来的位置。
      if (!entry || placed.has(ref)) return fromLayout(children)
      const item = toItem(entry)
      placed.set(ref, item)
      if (item.children) {
        item.children = fromLayout(children)
        return [item]
      }
      return [item, ...fromLayout(children)]
    })
  const root = fromLayout(layout?.nodes ?? [])

  const fill = (entries: NavigationEntry[], target: MenuItem[]) =>
    entries.forEach((entry) => {
      let item = placed.get(refOf(entry))
      if (!item) {
        item = toItem(entry)
        placed.set(refOf(entry), item)
        target.push(item)
      }
      if (item.children) fill(entry.children ?? [], item.children)
    })
  fill(defaults, root)

  return prune(root)
}
