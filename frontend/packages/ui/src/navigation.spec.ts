import type { NavigationLayout } from '@crane199709/sdk'
import { describe, expect, it } from 'vitest'
import type { MenuItem } from './AppLayout'
import { arrangeNavigation, type NavigationEntry } from './navigation'

const members: NavigationEntry = { page: 'members', title: '成员管理', path: '/members' }
const roles: NavigationEntry = { page: 'roles', title: '角色管理', path: '/roles' }
const overview: NavigationEntry = {
  menu: 'demo.overview',
  title: '订单概览',
  path: '/apps/demo/overview',
}
const attachments: NavigationEntry = {
  menu: 'demo.attachments',
  title: '订单附件',
  path: '/apps/demo/attachments',
}
const stats: NavigationEntry = { menu: 'demo.stats', title: '订单统计', path: '/apps/demo/stats' }
const demo = (...children: NavigationEntry[]): NavigationEntry => ({
  menu: 'demo',
  title: '示例应用',
  children,
})

// 输出只看结构：键、标题与层级。
function outline(items: MenuItem[]): unknown[] {
  return items.map((item) =>
    item.children ? { [item.title]: outline(item.children) } : item.title,
  )
}

describe('arrangeNavigation', () => {
  it('没有布局时按默认树显示，保留清单层级，没有页面的分组不显示', () => {
    const items = arrangeNavigation(
      [
        members,
        demo(overview, { menu: 'demo.reports', title: '报表', children: [stats] }),
        { menu: 'empty', title: '空分组', children: [] },
      ],
      null,
    )
    expect(outline(items)).toEqual(['成员管理', { 示例应用: ['订单概览', { 报表: ['订单统计'] }] }])
    expect(items[0]).toEqual({ key: 'members', title: '成员管理', path: '/members' })
    expect(items[1]!.key).toBe('demo')
  })

  it('按布局放置可见节点，自定义分组带名称', () => {
    const layout: NavigationLayout = {
      nodes: [
        { group: 'g1', name: '财务', children: [{ menu: 'demo.attachments' }] },
        { menu: 'demo', children: [{ menu: 'demo.overview' }] },
        { page: 'roles' },
        { page: 'members' },
      ],
    }
    const items = arrangeNavigation([members, roles, demo(overview, attachments)], layout)
    expect(outline(items)).toEqual([
      { 财务: ['订单附件'] },
      { 示例应用: ['订单概览'] },
      '角色管理',
      '成员管理',
    ])
    expect(items[0]!.key).toBe('g1')
  })

  it('跳过不可见或已不存在的引用', () => {
    const layout: NavigationLayout = {
      nodes: [{ page: 'retired' }, { page: 'roles' }, { menu: 'report' }, { page: 'members' }],
    }
    expect(outline(arrangeNavigation([members], layout))).toEqual(['成员管理'])
  })

  it('布局中没有的可见节点放到最近的已在布局中的祖先分组末尾，否则放到顶层末尾', () => {
    const layout: NavigationLayout = {
      nodes: [
        { group: 'g1', name: '财务', children: [{ menu: 'demo.attachments' }] },
        { menu: 'demo', children: [{ menu: 'demo.overview' }] },
      ],
    }
    expect(
      outline(arrangeNavigation([members, demo(overview, attachments, stats)], layout)),
    ).toEqual([{ 财务: ['订单附件'] }, { 示例应用: ['订单概览', '订单统计'] }, '成员管理'])
  })

  it('不在布局中的清单分组连同子节点一起放入', () => {
    const layout: NavigationLayout = {
      nodes: [{ group: 'g1', name: '财务', children: [{ menu: 'demo.attachments' }] }],
    }
    expect(outline(arrangeNavigation([demo(overview, attachments, stats)], layout))).toEqual([
      { 财务: ['订单附件'] },
      { 示例应用: ['订单概览', '订单统计'] },
    ])
  })

  it('没有可见页面的清单分组与自定义分组不显示', () => {
    const layout: NavigationLayout = {
      nodes: [
        { group: 'g1', name: '财务', children: [{ menu: 'demo.attachments' }] },
        { group: 'g2', name: '空', children: [] },
        { menu: 'demo', children: [{ menu: 'demo.overview' }] },
        { page: 'members' },
      ],
    }
    expect(outline(arrangeNavigation([members], layout))).toEqual(['成员管理'])
  })

  it('Sub-App 移出后再回来时恢复布局中的位置', () => {
    const layout: NavigationLayout = {
      nodes: [
        { page: 'members' },
        { group: 'g1', name: '财务', children: [{ menu: 'demo.attachments' }] },
        { menu: 'demo', children: [{ menu: 'demo.overview' }] },
      ],
    }
    expect(outline(arrangeNavigation([members], layout))).toEqual(['成员管理'])
    expect(outline(arrangeNavigation([members, demo(overview, attachments)], layout))).toEqual([
      '成员管理',
      { 财务: ['订单附件'] },
      { 示例应用: ['订单概览'] },
    ])
  })

  it('不可见分组里仍可见的节点留在该分组原来的位置', () => {
    const layout: NavigationLayout = {
      nodes: [{ page: 'roles' }, { menu: 'demo', children: [{ page: 'members' }] }],
    }
    expect(outline(arrangeNavigation([members, roles], layout))).toEqual(['角色管理', '成员管理'])
  })

  it('新节点放入祖先分组会超过三层时放到顶层末尾', () => {
    const layout: NavigationLayout = {
      nodes: [
        {
          group: 'g1',
          name: '财务',
          children: [{ menu: 'demo', children: [{ menu: 'demo.overview' }] }],
        },
      ],
    }
    const reports: NavigationEntry = { menu: 'demo.reports', title: '报表', children: [stats] }
    expect(outline(arrangeNavigation([demo(overview, reports, attachments)], layout))).toEqual([
      { 财务: [{ 示例应用: ['订单概览', '订单附件'] }] },
      { 报表: ['订单统计'] },
    ])
  })
})
