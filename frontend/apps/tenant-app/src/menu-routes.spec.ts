import type { MenuNode } from '@forge/sdk'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { applyMenuRoutes, menuSections } from './menu-routes'

const page = { render: () => null }
const subApps = {
  demo: [{ path: 'overview', name: 'demo:overview', component: page }],
  report: [{ path: 'monthly', name: 'report:monthly', component: page }],
}

function router() {
  return createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: page }] })
}

const demoMenu: MenuNode[] = [
  {
    code: 'demo',
    name: '示例应用',
    children: [{ code: 'demo.overview', name: '订单概览', app: 'demo', path: 'overview' }],
  },
]

describe('applyMenuRoutes', () => {
  it('只注册菜单中可见的 Sub-App 路由', () => {
    const shell = router()
    applyMenuRoutes(shell, demoMenu, subApps)

    expect(shell.resolve('/apps/demo/overview').name).toBe('demo:overview')
    expect(shell.resolve('/apps/report/monthly').matched).toHaveLength(0)
  })

  it('菜单变化后移除不再可见的 Sub-App 路由', () => {
    const shell = router()
    applyMenuRoutes(shell, demoMenu, subApps)
    applyMenuRoutes(shell, [], subApps)

    expect(shell.resolve('/apps/demo/overview').matched).toHaveLength(0)
  })
})

describe('menuSections', () => {
  it('分组列出叶子入口，顶层叶子自成一组，没有入口的分组不展示', () => {
    expect(
      menuSections([
        ...demoMenu,
        { code: 'home', name: '首页', app: 'demo', path: 'home' },
        { code: 'empty', name: '空分组', children: [] },
      ]),
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
})
