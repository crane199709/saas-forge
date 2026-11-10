import type { NavigationLayout, NavigationLayoutNode } from '@crane199709/sdk'
import { height, navigationLimits, placeNavigation, type NavigationEntry } from './navigation'

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

/** 目录外引用的保存形态；其中目录内的子节点在显示树里，以 `surfaced`（显示中节点的 `id`）占住原顺序。 */
type RetainedNode = { page?: string; menu?: string; children?: RetainedChild[] }
type RetainedChild = RetainedNode | { surfaced: string }

/** 布局中当前不在目录里的引用，保存时放回 `parent`（显示中节点的 `id`，null 为顶层）。 */
interface Retained {
  parent: string | null
  node: RetainedNode
}

/** 编辑状态：显示的树与不显示但须保存的目录外引用。 */
export interface EditorTree {
  nodes: EditorNode[]
  retained: Retained[]
  /** 从目录外分组里显示出来、尚未被拖动过的节点 `id`，保存时放回原分组。 */
  surfaced: Set<string>
}

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

const own = (node: { page?: string; menu?: string }): RetainedNode =>
  node.page !== undefined ? { page: node.page } : { menu: node.menu }

/**
 * 由目录与已保存的布局得出编辑状态，放置规则与导航相同（{@link placeNavigation}）。目录是管理页可排列的全部节点，
 * 不按操作者本人权限裁剪；布局中目录外的引用不显示，其中目录内的子节点显示在它的位置，并记下原顺序以便保存时放回。
 *
 * @param catalog 目录，`children` 表示清单分组
 * @param layout 已保存的布局；null 表示按目录顺序
 */
export function editorTree(
  catalog: NavigationEntry[],
  layout: NavigationLayout | null | undefined,
): EditorTree {
  const retained: Retained[] = []
  const surfaced = new Set<string>()
  const nodes = placeNavigation<EditorNode>(catalog, layout, {
    create: fromEntry,
    group: (node) => ({
      id: `group:${node.group}`,
      kind: 'group',
      ref: node.group!,
      title: node.name ?? '',
      children: [],
    }),
    children: (item) => item.children,
    absent: (node, { parent, visible, place }) => {
      // 先登记外层：显示出来的节点里若还有目录外引用，保存时排在外层之后，外层已就位。
      const kept: Retained = { parent: parent?.id ?? null, node: own(node) }
      retained.push(kept)
      const shown: EditorNode[] = []
      const conceal = (hidden: NavigationLayoutNode): RetainedNode => {
        const children = (hidden.children ?? []).flatMap((child): RetainedChild[] => {
          if (!visible(child)) return [conceal(child)]
          const items = place([child])
          shown.push(...items)
          items.forEach((item) => surfaced.add(item.id))
          return items.map((item) => ({ surfaced: item.id }))
        })
        return children.length ? { ...own(hidden), children } : own(hidden)
      }
      kept.node = conceal(node)
      return shown
    },
  })
  return { nodes, retained, surfaced }
}

/** 节点被拖动过：保存时留在拖动后的位置，不再放回目录外的原分组。 */
export function markMoved(tree: EditorTree, node: EditorNode) {
  tree.surfaced.delete(node.id)
}

/**
 * 编辑状态转为要保存的布局。目录外的引用若有未拖动过的显示节点，连同这些节点按原顺序放在第一个这样的节点的位置，
 * 即它原来的位置；否则放回原父节点末尾。原父节点已不在或放回后超过三层时放到顶层末尾。
 */
export function toLayout(tree: EditorTree): NavigationLayout {
  const { maxDepth } = navigationLimits
  const output = new Map<string, NavigationLayoutNode>()
  const containers = new Map<string, NavigationLayoutNode[]>()
  const convert = (nodes: EditorNode[]): NavigationLayoutNode[] =>
    nodes.map((node) => {
      let out: NavigationLayoutNode
      if (!node.children) out = node.kind === 'page' ? { page: node.ref } : { menu: node.ref }
      else {
        const children = convert(node.children)
        containers.set(node.id, children)
        out =
          node.kind === 'group'
            ? { group: node.ref, name: node.title, children }
            : { menu: node.ref, children }
      }
      output.set(node.id, out)
      return out
    })
  const nodes = convert(tree.nodes)

  // 输出树中某一列节点所在的层（顶层为 1）与包含某个节点的那一列；不在树中时为 undefined。
  const levelOf = (target: NavigationLayoutNode[], list = nodes, level = 1): number | undefined => {
    if (list === target) return level
    for (const node of list) {
      const found = node.children && levelOf(target, node.children, level + 1)
      if (found) return found
    }
  }
  const holderOf = (
    target: NavigationLayoutNode,
    list = nodes,
  ): NavigationLayoutNode[] | undefined => {
    if (list.includes(target)) return list
    for (const node of list) {
      const found = node.children && holderOf(target, node.children)
      if (found) return found
    }
  }

  tree.retained.forEach(({ parent, node }) => {
    const taken: NavigationLayoutNode[] = []
    const build = (kept: RetainedChild, restore: boolean): NavigationLayoutNode[] => {
      if ('surfaced' in kept) {
        const shown = output.get(kept.surfaced)
        if (!restore || !shown || !tree.surfaced.has(kept.surfaced)) return []
        taken.push(shown)
        return [shown]
      }
      const children = (kept.children ?? []).flatMap((child) => build(child, restore))
      const base = kept.page !== undefined ? { page: kept.page } : { menu: kept.menu }
      return [children.length ? { ...base, children } : base]
    }

    const restored = build(node, true)[0]!
    const [first, ...rest] = taken
    if (first) {
      const holder = holderOf(first)!
      if (levelOf(holder)! - 1 + height(restored) <= maxDepth) {
        rest.forEach((item) => {
          const list = holderOf(item)!
          list.splice(list.indexOf(item), 1)
        })
        holder.splice(holder.indexOf(first), 1, restored)
        return
      }
    }

    const kept = build(node, false)[0]!
    const target = parent === null ? undefined : containers.get(parent)
    const level = target && levelOf(target)
    if (target && level && level - 1 + height(kept) <= maxDepth) target.push(kept)
    else nodes.push(kept)
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
  const { maxDepth } = navigationLimits
  if (type === 'inner') return !!target.children && targetLevel + height(dragging) <= maxDepth
  return targetLevel - 1 + height(dragging) <= maxDepth
}
