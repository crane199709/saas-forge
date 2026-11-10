import type { NavigationLayout, NavigationLayoutNode } from '@crane199709/sdk'
import type { NavigationEntry } from './navigation'

/** 导航最多三层，顶层算第一层。 */
const maxDepth = 3

/** 编辑中的布局节点。带 `children` 的是分组（清单分组或自定义分组），没有的是页面。 */
export interface EditorNode {
  /** 树内唯一的键：`page:<页面键>`、`menu:<菜单编码>` 或 `group:<分组 id>`。 */
  id: string
  kind: 'page' | 'menu' | 'group'
  /** 页面键、菜单编码或自定义分组 id。 */
  ref: string
  title: string
  children?: EditorNode[]
}

/** 布局中当前不在目录里的引用，保存时放回 `parent`（显示中节点的 `id`，null 为顶层）末尾。 */
interface Retained {
  parent: string | null
  node: NavigationLayoutNode
}

/** 编辑状态：显示的树与不显示但须保存的目录外引用。 */
export interface EditorTree {
  nodes: EditorNode[]
  retained: Retained[]
}

const refOf = (node: { page?: string; menu?: string }) =>
  node.page !== undefined ? `page:${node.page}` : `menu:${node.menu}`

const height = (node: { children?: unknown[] }): number =>
  node.children ? 1 + Math.max(0, ...node.children.map((child) => height(child as EditorNode))) : 1

function fromEntry(entry: NavigationEntry): EditorNode {
  const kind = entry.page !== undefined ? 'page' : 'menu'
  const ref = (entry.page ?? entry.menu)!
  return {
    id: `${kind}:${ref}`,
    kind,
    ref,
    title: entry.title,
    ...(entry.children ? { children: [] } : {}),
  }
}

/**
 * 由目录与已保存的布局得出编辑状态。目录是管理页可排列的全部节点，不按操作者本人权限裁剪；
 * 布局中目录外的引用不显示，记下原父节点以便保存时保留；目录中有而布局中没有的节点，
 * 与导航合并规则相同，放到最近的、已在布局中的祖先分组末尾，没有时放到顶层末尾。
 *
 * @param catalog 目录，`children` 表示清单分组
 * @param layout 已保存的布局；null 表示按目录顺序
 */
export function editorTree(
  catalog: NavigationEntry[],
  layout: NavigationLayout | null | undefined,
): EditorTree {
  const entries = new Map<string, NavigationEntry>()
  const index = (items: NavigationEntry[]) =>
    items.forEach((entry) => {
      entries.set(refOf(entry), entry)
      index(entry.children ?? [])
    })
  index(catalog)

  const retained: Retained[] = []
  const placed = new Map<string, EditorNode>()
  const known = (node: NavigationLayoutNode) => node.group !== undefined || entries.has(refOf(node))
  // 目录外的节点连同其中同在目录外的子节点一起保留；目录内的子节点在它的位置显示。
  const hidden = (node: NavigationLayoutNode): NavigationLayoutNode => {
    const children = (node.children ?? []).filter((child) => !known(child)).map(hidden)
    const own = node.page !== undefined ? { page: node.page } : { menu: node.menu }
    return children.length ? { ...own, children } : own
  }
  const lift = (nodes: NavigationLayoutNode[], parent: string | null): EditorNode[] =>
    nodes.flatMap((node) =>
      known(node) ? fromLayout([node], parent) : lift(node.children ?? [], parent),
    )
  const fromLayout = (nodes: NavigationLayoutNode[], parent: string | null): EditorNode[] =>
    nodes.flatMap((node) => {
      const children = node.children ?? []
      if (node.group !== undefined) {
        const id = `group:${node.group}`
        return [
          {
            id,
            kind: 'group',
            ref: node.group,
            title: node.name ?? '',
            children: fromLayout(children, id),
          },
        ]
      }
      const ref = refOf(node)
      const entry = entries.get(ref)
      if (!entry) {
        retained.push({ parent, node: hidden(node) })
        return lift(children, parent)
      }
      if (placed.has(ref)) return fromLayout(children, parent)
      const item = fromEntry(entry)
      placed.set(ref, item)
      if (item.children) {
        item.children = fromLayout(children, item.id)
        return [item]
      }
      return [item, ...fromLayout(children, parent)]
    })
  const nodes = fromLayout(layout?.nodes ?? [], null)

  const fill = (items: NavigationEntry[], target: EditorNode[]) =>
    items.forEach((entry) => {
      let item = placed.get(refOf(entry))
      if (!item) {
        item = fromEntry(entry)
        placed.set(refOf(entry), item)
        target.push(item)
      }
      if (item.children) fill(entry.children ?? [], item.children)
    })
  fill(catalog, nodes)

  return { nodes, retained }
}

/**
 * 编辑状态转为要保存的布局。目录外的引用放回原父节点末尾；原父节点已不在或放回后超过三层时放到顶层末尾。
 */
export function toLayout(tree: EditorTree): NavigationLayout {
  const containers = new Map<string, { children: NavigationLayoutNode[]; depth: number }>()
  const convert = (nodes: EditorNode[], depth: number): NavigationLayoutNode[] =>
    nodes.map((node) => {
      if (!node.children) return node.kind === 'page' ? { page: node.ref } : { menu: node.ref }
      const children = convert(node.children, depth + 1)
      containers.set(node.id, { children, depth })
      return node.kind === 'group'
        ? { group: node.ref, name: node.title, children }
        : { menu: node.ref, children }
    })
  const nodes = convert(tree.nodes, 1)
  tree.retained.forEach(({ parent, node }) => {
    const target = parent === null ? undefined : containers.get(parent)
    if (target && target.depth + height(node) <= maxDepth) target.children.push(node)
    else nodes.push(node)
  })
  return { nodes }
}

function groupIds(nodes: EditorNode[]): string[] {
  return nodes.flatMap((node) => [
    ...(node.kind === 'group' ? [node.ref] : []),
    ...groupIds(node.children ?? []),
  ])
}

/** 在顶层末尾新建自定义分组，返回新分组。 */
export function addGroup(tree: EditorTree, name: string): EditorNode {
  const taken = new Set(groupIds(tree.nodes))
  let ref: string
  do ref = Math.random().toString(36).slice(2, 10)
  while (!ref || taken.has(ref))
  const group: EditorNode = { id: `group:${ref}`, kind: 'group', ref, title: name, children: [] }
  tree.nodes.push(group)
  return group
}

/** 删除自定义分组，子节点移到它原来的位置。 */
export function removeGroup(tree: EditorTree, group: EditorNode) {
  const remove = (nodes: EditorNode[]): boolean => {
    const at = nodes.indexOf(group)
    if (at >= 0) {
      nodes.splice(at, 1, ...(group.children ?? []))
      return true
    }
    return nodes.some((node) => remove(node.children ?? []))
  }
  remove(tree.nodes)
}

/**
 * 能否把节点拖到目标处：页面不接收子节点，拖入后不超过三层。
 *
 * @param targetLevel 目标节点所在层，顶层为 1
 * @param type 放在目标之前、之内或之后
 */
export function canDrop(
  dragging: EditorNode,
  target: EditorNode,
  targetLevel: number,
  type: 'prev' | 'inner' | 'next',
): boolean {
  if (type === 'inner') return !!target.children && targetLevel + height(dragging) <= maxDepth
  return targetLevel - 1 + height(dragging) <= maxDepth
}
