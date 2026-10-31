import type { MenuNode } from '@crane199709/sdk'
import { describe, expect, it } from 'vitest'
import { menuSections } from './menu-routes'

const demoMenu: MenuNode[] = [
  {
    code: 'demo',
    name: '示例应用',
    children: [{ code: 'demo.overview', name: '订单概览', app: 'demo', path: 'overview' }],
  },
]

describe('menuSections', () => {
  it('分组列出叶子入口，顶层叶子自成一组，没有入口的分组不展示', () => {
    expect(
      menuSections(
        [
          ...demoMenu,
          { code: 'home', name: '首页', app: 'demo', path: 'home' },
          { code: 'empty', name: '空分组', children: [] },
        ],
        new Set(['demo']),
      ),
    ).toEqual([
      {
        code: 'demo',
        name: '示例应用',
        links: [{ code: 'demo.overview', name: '订单概览', to: '/apps/demo/overview' }],
      },
      {
        code: 'home',
        name: '首页',
        links: [{ code: 'home', name: '首页', to: '/apps/demo/home' }],
      },
    ])
  })

  it('不展示指向未注册 Sub-App 的入口', () => {
    expect(
      menuSections(
        [...demoMenu, { code: 'report', name: '报表', app: 'report', path: 'monthly' }],
        new Set(['report']),
      ),
    ).toEqual([
      {
        code: 'report',
        name: '报表',
        links: [{ code: 'report', name: '报表', to: '/apps/report/monthly' }],
      },
    ])
  })
})
