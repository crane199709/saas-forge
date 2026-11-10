import type { NavigationLayout } from '@crane199709/sdk'
import { describe, expect, it } from 'vitest'
import type { NavigationEntry } from './navigation'
import {
  addGroup,
  canDrop,
  editorTree,
  removeGroup,
  toLayout,
  type EditorNode,
} from './navigation-editor'

const members: NavigationEntry = { page: 'members', title: '成员管理' }
const roles: NavigationEntry = { page: 'roles', title: '角色管理' }
const overview: NavigationEntry = { menu: 'demo.overview', title: '订单概览' }
const attachments: NavigationEntry = { menu: 'demo.attachments', title: '订单附件' }
const demo: NavigationEntry = { menu: 'demo', title: '示例应用', children: [overview, attachments] }
const catalog = [members, roles, demo]

// 只看结构：标题与层级。
function outline(nodes: EditorNode[]): unknown[] {
  return nodes.map((node) =>
    node.children ? { [node.title]: outline(node.children) } : node.title,
  )
}

const find = (nodes: EditorNode[], title: string): EditorNode | undefined => {
  for (const node of nodes) {
    if (node.title === title) return node
    const found = find(node.children ?? [], title)
    if (found) return found
  }
}

describe('editorTree', () => {
  it('没有布局时按目录顺序，保持清单层级', () => {
    const { nodes } = editorTree(catalog, null)

    expect(outline(nodes)).toEqual(['成员管理', '角色管理', { 示例应用: ['订单概览', '订单附件'] }])
  })

  it('按布局放置节点，目录中有但布局中没有的放到最近的祖先分组末尾', () => {
    const layout: NavigationLayout = {
      nodes: [
        { group: 'g1', name: '财务', children: [{ menu: 'demo.attachments' }] },
        { menu: 'demo', children: [] },
        { page: 'roles' },
      ],
    }

    const { nodes } = editorTree(catalog, layout)

    expect(outline(nodes)).toEqual([
      { 财务: ['订单附件'] },
      { 示例应用: ['订单概览'] },
      '角色管理',
      '成员管理',
    ])
  })
})

describe('toLayout', () => {
  it('保存整棵树，清单分组与自定义分组都带子节点', () => {
    const layout: NavigationLayout = {
      nodes: [
        { group: 'g1', name: '财务', children: [{ menu: 'demo.attachments' }] },
        { page: 'members' },
        { page: 'roles' },
        { menu: 'demo', children: [{ menu: 'demo.overview' }] },
      ],
    }
    const tree = editorTree(catalog, layout)

    expect(toLayout(tree)).toEqual(layout)
  })

  it('目录外的引用留在原父节点末尾，父节点不在了放到顶层末尾', () => {
    const tree = editorTree(catalog, {
      nodes: [
        {
          group: 'g1',
          name: '财务',
          children: [{ menu: 'gone.report' }, { menu: 'demo.attachments' }],
        },
        { group: 'g2', name: '临时', children: [{ page: 'retired' }] },
        { page: 'members' },
        { page: 'roles' },
        { menu: 'demo', children: [{ menu: 'demo.overview' }] },
      ],
    })
    removeGroup(tree, find(tree.nodes, '临时')!)

    expect(toLayout(tree).nodes).toEqual([
      {
        group: 'g1',
        name: '财务',
        children: [{ menu: 'demo.attachments' }, { menu: 'gone.report' }],
      },
      { page: 'members' },
      { page: 'roles' },
      { menu: 'demo', children: [{ menu: 'demo.overview' }] },
      { page: 'retired' },
    ])
  })

  it('目录外的分组保留其中同在目录外的子节点，目录内的子节点留在显示位置', () => {
    const tree = editorTree(catalog, {
      nodes: [
        { menu: 'gone', children: [{ menu: 'gone.a' }, { page: 'members' }] },
        { page: 'roles' },
      ],
    })

    expect(outline(tree.nodes)).toEqual([
      '成员管理',
      '角色管理',
      { 示例应用: ['订单概览', '订单附件'] },
    ])
    expect(toLayout(tree).nodes.slice(-1)).toEqual([
      { menu: 'gone', children: [{ menu: 'gone.a' }] },
    ])
  })

  it('放回原父节点会超过三层时放到顶层末尾', () => {
    const tree = editorTree(catalog, {
      nodes: [
        {
          group: 'a',
          name: 'A',
          children: [
            { group: 'b', name: 'B', children: [{ menu: 'gone', children: [{ menu: 'gone.a' }] }] },
          ],
        },
      ],
    })

    expect(toLayout(tree).nodes.slice(-1)).toEqual([
      { menu: 'gone', children: [{ menu: 'gone.a' }] },
    ])
  })
})

describe('自定义分组', () => {
  it('新建的分组加在顶层末尾，id 不与已有分组重复', () => {
    const tree = editorTree(catalog, { nodes: [{ group: 'g1', name: '财务', children: [] }] })

    const group = addGroup(tree, '报表')

    expect(tree.nodes.at(-1)).toBe(group)
    expect(group.ref).not.toBe('g1')
    expect(group.ref.length).toBeGreaterThan(0)
    expect(group.ref.length).toBeLessThanOrEqual(64)
  })

  it('删除分组时子节点移到它原来的位置', () => {
    const tree = editorTree(catalog, {
      nodes: [
        { page: 'members' },
        { group: 'g1', name: '财务', children: [{ page: 'roles' }, { menu: 'demo.attachments' }] },
        { menu: 'demo', children: [{ menu: 'demo.overview' }] },
      ],
    })

    removeGroup(tree, find(tree.nodes, '财务')!)

    expect(outline(tree.nodes)).toEqual([
      '成员管理',
      '角色管理',
      '订单附件',
      { 示例应用: ['订单概览'] },
    ])
  })
})

describe('canDrop', () => {
  const tree = editorTree(catalog, {
    nodes: [
      {
        group: 'a',
        name: 'A',
        children: [{ group: 'b', name: 'B', children: [{ page: 'members' }] }],
      },
      { page: 'roles' },
      { menu: 'demo', children: [{ menu: 'demo.overview' }, { menu: 'demo.attachments' }] },
    ],
  })
  const node = (title: string) => find(tree.nodes, title)!

  it('页面不能接收子节点', () => {
    expect(canDrop(node('示例应用'), node('角色管理'), 1, 'inner')).toBe(false)
    expect(canDrop(node('订单概览'), node('角色管理'), 1, 'inner')).toBe(false)
  })

  it('页面可以放入分组或排在任意节点前后', () => {
    expect(canDrop(node('角色管理'), node('示例应用'), 1, 'inner')).toBe(true)
    expect(canDrop(node('角色管理'), node('B'), 2, 'inner')).toBe(true)
    expect(canDrop(node('订单概览'), node('成员管理'), 3, 'next')).toBe(true)
  })

  it('不允许超过三层', () => {
    expect(canDrop(node('角色管理'), node('成员管理'), 3, 'inner')).toBe(false)
    expect(canDrop(node('示例应用'), node('B'), 2, 'inner')).toBe(false)
    expect(canDrop(node('示例应用'), node('A'), 1, 'inner')).toBe(true)
    expect(canDrop(node('A'), node('示例应用'), 1, 'inner')).toBe(false)
    expect(canDrop(node('A'), node('角色管理'), 1, 'prev')).toBe(true)
    expect(canDrop(node('示例应用'), node('成员管理'), 3, 'prev')).toBe(false)
  })
})
