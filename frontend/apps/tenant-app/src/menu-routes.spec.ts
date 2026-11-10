import type { MenuNode } from '@crane199709/sdk'
import { describe, expect, it } from 'vitest'
import { menuEntries } from './menu-routes'

describe('menuEntries', () => {
  it('保持清单的多层分组，叶子入口指向 Sub-App 路由', () => {
    const menus: MenuNode[] = [
      {
        code: 'demo',
        name: '示例应用',
        children: [
          { code: 'demo.overview', name: '订单概览', app: 'demo', path: 'overview' },
          {
            code: 'demo.finance',
            name: '财务',
            children: [{ code: 'demo.bills', name: '账单', app: 'demo', path: 'bills' }],
          },
        ],
      },
      { code: 'home', name: '主页', app: 'demo', path: 'home' },
    ]
    expect(menuEntries(menus, new Set(['demo']))).toEqual([
      {
        menu: 'demo',
        title: '示例应用',
        children: [
          { menu: 'demo.overview', title: '订单概览', path: '/apps/demo/overview' },
          {
            menu: 'demo.finance',
            title: '财务',
            children: [{ menu: 'demo.bills', title: '账单', path: '/apps/demo/bills' }],
          },
        ],
      },
      { menu: 'home', title: '主页', path: '/apps/demo/home' },
    ])
  })

  it('指向未注册 Sub-App 的入口按不可见处理', () => {
    const menus: MenuNode[] = [
      {
        code: 'demo',
        name: '示例应用',
        children: [{ code: 'demo.overview', name: '订单概览', app: 'demo', path: 'overview' }],
      },
      { code: 'report', name: '报表', app: 'report', path: 'monthly' },
    ]
    expect(menuEntries(menus, new Set(['report']))).toEqual([
      { menu: 'demo', title: '示例应用', children: [] },
      { menu: 'report', title: '报表', path: '/apps/report/monthly' },
    ])
  })
})
