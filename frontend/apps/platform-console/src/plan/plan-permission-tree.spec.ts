import { expect, it } from 'vitest'
import { catalogTree } from './catalog-fixture'
import {
  ownersOf,
  planTree,
  retiredCodes,
  selectionAfterSubApps,
  unselectedCodes,
} from './plan-permission-tree'

it('所选 Sub-App 的叶子菜单各为一个根，分组不显示，多层分组拍平', () => {
  expect(planTree(catalogTree, []).menus).toEqual([])
  const demo = planTree(catalogTree, ['demo'])
  expect(demo.menus.map((menu) => menu.code)).toEqual(['demo.overview', 'demo.attachments'])
  expect(demo.menus.every((menu) => menu.children.length === 0 && !menu.note)).toBe(true)
  // 共用提示只列显示中的页面
  expect(demo.menus[0]!.actions.at(-1)!.sharedWith).toEqual([])
  expect(demo.menus[0]!.permission!.sharedWith).toEqual(['demo.attachments'])
  expect(demo.others).toBe(catalogTree.others)

  const both = planTree(catalogTree, ['crm', 'demo'])
  expect(both.menus.map((menu) => [menu.code, menu.note])).toEqual([
    ['demo.overview', 'demo'],
    ['demo.attachments', 'demo'],
    ['crm.customers', 'crm'],
    ['crm.help', 'crm'],
  ])
  expect(both.menus[2]!.actions[0]!.sharedWith).toEqual(['demo.overview'])
})

it('权限码归属挂着它的叶子菜单的 Sub-App', () => {
  const owners = ownersOf(catalogTree)
  expect([...owners.get('order:export')!]).toEqual(['demo', 'crm'])
  expect([...owners.get('customer:read')!]).toEqual(['crm'])
  expect(owners.has('report:sync')).toBe(false)
})

it('归属的 Sub-App 全部未选与已下线的码分别列出', () => {
  const selected = ['customer:read', 'order:export', 'report:sync', 'legacy:report']
  expect(unselectedCodes(catalogTree, ['demo'], selected)).toEqual(['customer:read'])
  expect(unselectedCodes(catalogTree, [], selected)).toEqual(['customer:read', 'order:export'])
  expect(retiredCodes(catalogTree, selected)).toEqual(['legacy:report'])
})

it('取消 Sub-App 时移除只归属它的码，未选区与功能权限中的码不受影响', () => {
  const selected = ['customer:read', 'order:export', 'order:read', 'report:sync']
  expect(selectionAfterSubApps(catalogTree, selected, ['crm', 'demo'], ['demo'])).toEqual([
    'order:export',
    'order:read',
    'report:sync',
  ])
  // customer:read 切换前已在未选区（crm 未选），取消 demo 不影响它
  expect(selectionAfterSubApps(catalogTree, selected, ['demo'], [])).toEqual([
    'customer:read',
    'report:sync',
  ])
})
