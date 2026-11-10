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

/** 导航与布局编辑共用的限制，与后端保存时的校验一致。 */
export const navigationLimits = {
  /** 最多三层，顶层算第一层。 */
  maxDepth: 3,
  /** 自定义分组名称的最大字符数。 */
  maxGroupName: 32,
} as const

export const refOf = (node: { page?: string; menu?: string }) =>
  node.page !== undefined ? `page:${node.page}` : `menu:${node.menu}`

/** 节点占的层数：页面为 1，分组为 1 加最深子节点的层数。 */
export const height = (node: { children?: unknown[] }): number =>
  node.children
    ? 1 + Math.max(0, ...node.children.map((child) => height(child as { children?: unknown[] })))
    : 1

/** 放置时不可见的引用的处理上下文。 */
export interface AbsentContext<T> {
  /** 该引用所在的分组，null 为顶层。 */
  parent: T | null
  /** 引用当前是否可见（自定义分组总是可见）。 */
  visible: (node: NavigationLayoutNode) => boolean
  /** 在该引用的位置（同一分组、同一层）放置节点。 */
  place: (nodes: NavigationLayoutNode[]) => T[]
}

/** 合并规则中随输出类型不同的部分。 */
export interface Placement<T> {
  /** 由默认树的节点新建输出节点；分组须带空的 `children`。 */
  create: (entry: NavigationEntry) => T
  /** 由布局中的自定义分组新建输出节点，带空的 `children`。 */
  group: (node: NavigationLayoutNode) => T
  children: (item: T) => T[] | undefined
  /** 不可见的引用：返回放在它位置上的节点。 */
  absent: (node: NavigationLayoutNode, context: AbsentContext<T>) => T[]
}

/**
 * 导航合并规则（导航布局规格第 3 节），导航与布局编辑共用：按布局放置可见节点；
 * 可见但布局中没有的节点放到默认树中最近的、已在布局中的祖先分组末尾，没有这样的祖先、
 * 或放入后会超过三层时放到顶层末尾。不可见的引用交给 `absent` 处理。
 */
export function placeNavigation<T>(
  defaults: NavigationEntry[],
  layout: NavigationLayout | null | undefined,
  placement: Placement<T>,
): T[] {
  const visible = new Map<string, NavigationEntry>()
  const index = (entries: NavigationEntry[]) =>
    entries.forEach((entry) => {
      visible.set(refOf(entry), entry)
      index(entry.children ?? [])
    })
  index(defaults)
  const isVisible = (node: NavigationLayoutNode) =>
    node.group !== undefined || visible.has(refOf(node))

  // 已放置的节点及其所在层；后续把布局中没有的子节点追加到这里。
  const placed = new Map<string, { item: T; level: number }>()
  const fromLayout = (nodes: NavigationLayoutNode[], parent: T | null, level: number): T[] =>
    nodes.flatMap((node) => {
      const children = node.children ?? []
      if (node.group !== undefined) {
        const group = placement.group(node)
        placement.children(group)!.push(...fromLayout(children, group, level + 1))
        return [group]
      }
      const ref = refOf(node)
      const entry = visible.get(ref)
      if (!entry) {
        return placement.absent(node, {
          parent,
          visible: isVisible,
          place: (nodes) => fromLayout(nodes, parent, level),
        })
      }
      if (placed.has(ref)) return fromLayout(children, parent, level)
      const item = placement.create(entry)
      placed.set(ref, { item, level })
      const own = placement.children(item)
      if (own) {
        own.push(...fromLayout(children, item, level + 1))
        return [item]
      }
      return [item, ...fromLayout(children, parent, level)]
    })
  const root = fromLayout(layout?.nodes ?? [], null, 1)

  // 只算尚未放置的部分：已放置的子节点留在原处，不随新分组下移。
  const pending = (entry: NavigationEntry): number =>
    entry.children
      ? 1 + Math.max(0, ...entry.children.filter((child) => !placed.has(refOf(child))).map(pending))
      : 1
  const fill = (entries: NavigationEntry[], target: T[], level: number) =>
    entries.forEach((entry) => {
      let current = placed.get(refOf(entry))
      if (!current) {
        const item = placement.create(entry)
        const fits = level - 1 + pending(entry) <= navigationLimits.maxDepth
        current = { item, level: fits ? level : 1 }
        ;(fits ? target : root).push(item)
        placed.set(refOf(entry), current)
      }
      const own = placement.children(current.item)
      if (own) fill(entry.children ?? [], own, current.level + 1)
    })
  fill(defaults, root, 1)
  return root
}

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
 * 没有这样的祖先或放入后超过三层时放到顶层末尾；没有页面的分组不显示。
 *
 * @param defaults 默认导航树，已按权限、套餐等裁剪为可见节点，顺序即没有布局时的顺序
 * @param layout 已保存的布局；null 表示按默认树显示
 */
export function arrangeNavigation(
  defaults: NavigationEntry[],
  layout: NavigationLayout | null | undefined,
): MenuItem[] {
  return prune(
    placeNavigation<MenuItem>(defaults, layout, {
      create: toItem,
      group: (node) => ({ key: node.group!, title: node.name ?? '', children: [] }),
      children: (item) => item.children,
      // 不可见的引用跳过，其中仍可见的节点留在它原来的位置。
      absent: (node, { place }) => place(node.children ?? []),
    }),
  )
}
