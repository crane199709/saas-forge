import { Tickets } from '@element-plus/icons-vue'
import { flushPromises } from '@vue/test-utils'
import { expect, it } from 'vitest'
import { h } from 'vue'
import { AppLayout, type MenuItem } from './AppLayout'
import { mountWithShell } from './test-mount'

const menu: MenuItem[] = [
  { key: 'home', title: '首页', path: '/' },
  { key: 'members', title: '成员', path: '/members' },
  {
    key: 'demo',
    title: '示例',
    children: [{ key: 'demo.orders', title: '订单', path: '/apps/demo/orders' }],
  },
]

it('渲染产品名、顶栏插槽、菜单链接与分组及内容区', async () => {
  const wrapper = await mountWithShell(AppLayout, {
    props: { title: 'SaaS Forge', menu, activePath: '/' },
    slots: { actions: () => h('span', '用户'), default: () => h('p', '页面内容') },
  })

  expect(wrapper.get('.brand').attributes('href')).toBe('/')
  expect(wrapper.get('.brand').text()).toBe('SaaS Forge')
  expect(wrapper.get('.shell-actions').text()).toBe('用户')
  expect(wrapper.get('[data-testid="menu-members"]').attributes('href')).toBe('/members')
  expect(wrapper.get('[data-testid="menu-group-demo"]').text()).toContain('示例')
  expect(wrapper.get('[data-testid="menu-demo.orders"]').text()).toBe('订单')
  expect(wrapper.get('main').text()).toBe('页面内容')
})

const withIcons: MenuItem[] = [
  { key: 'home', title: '首页', path: '/', icon: 'home' },
  {
    key: 'demo',
    title: '示例',
    icon: 'grid',
    children: [{ key: 'demo.orders', title: '订单', path: '/apps/demo/orders', icon: 'tickets' }],
  },
]

it('在菜单文字之前渲染对辅助技术隐藏的图标', async () => {
  const wrapper = await mountWithShell(AppLayout, {
    props: { title: 'SaaS Forge', menu: withIcons, activePath: '/' },
  })

  const link = wrapper.get('[data-testid="menu-home"]')
  const leafIcon = link.get('.el-icon')
  expect(leafIcon.attributes('aria-hidden')).toBe('true')
  expect(link.element.firstElementChild).toBe(leafIcon.element)
  expect(leafIcon.element.nextSibling?.textContent).toBe('首页')
  // 布局把图标名交给白名单解析：叶子项渲染的是 `tickets` 对应的元素图标，不是任意图标。
  expect(wrapper.get('[data-testid="menu-demo.orders"]').findComponent(Tickets).exists()).toBe(true)

  const groupTitle = wrapper.get('[data-testid="menu-group-demo"] .el-sub-menu__title')
  const groupIcon = groupTitle.get('.el-icon')
  expect(groupIcon.attributes('aria-hidden')).toBe('true')
  expect(groupTitle.element.firstElementChild).toBe(groupIcon.element)
  expect(groupIcon.element.nextSibling?.textContent).toBe('示例')
})

it('高亮与当前路径最长匹配的菜单项，下级路径归到所属项', async () => {
  const wrapper = await mountWithShell(AppLayout, {
    props: { title: 'SaaS Forge', menu, activePath: '/apps/demo/orders/42' },
  })

  expect(wrapper.get('.el-menu-item.is-active').text()).toBe('订单')
  await wrapper.setProps({ activePath: '/members' })
  expect(wrapper.get('.el-menu-item.is-active').text()).toBe('成员')
})

const nested: MenuItem[] = [
  { key: 'home', title: '首页', path: '/' },
  {
    key: 'demo',
    title: '示例',
    children: [
      { key: 'demo.overview', title: '概览', path: '/apps/demo/overview' },
      {
        key: 'finance',
        title: '财务',
        children: [{ key: 'demo.orders', title: '订单', path: '/apps/demo/orders' }],
      },
    ],
  },
  {
    key: 'settings',
    title: '系统设置',
    children: [{ key: 'parameters', title: '参数设置', path: '/parameters' }],
  },
]

// 子菜单收起时其内容以 display: none 隐藏
function shown(wrapper: Awaited<ReturnType<typeof mountWithShell>>, testid: string) {
  let element: HTMLElement | null = wrapper.get(`[data-testid="${testid}"]`).element as HTMLElement
  for (; element; element = element.parentElement) {
    if (element.style.display === 'none') return false
  }
  return true
}

it('三层菜单仅默认展开当前页所在层级，允许手动展开多个分组和收起', async () => {
  const wrapper = await mountWithShell(AppLayout, {
    props: { title: 'SaaS Forge', menu: nested, activePath: '/apps/demo/orders' },
    attachTo: document.body,
  })

  expect(wrapper.find('[data-testid="menu-group-demo"]').exists()).toBe(true)
  expect(wrapper.get('[data-testid="menu-group-finance"]').text()).toContain('订单')
  expect(shown(wrapper, 'menu-demo.overview')).toBe(true)
  expect(shown(wrapper, 'menu-demo.orders')).toBe(true)
  expect(shown(wrapper, 'menu-parameters')).toBe(false)
  expect(wrapper.get('.el-menu-item.is-active').text()).toBe('订单')

  await wrapper.get('[data-testid="menu-group-settings"] .el-sub-menu__title').trigger('click')
  expect(shown(wrapper, 'menu-parameters')).toBe(true)
  expect(shown(wrapper, 'menu-demo.orders')).toBe(true)

  await wrapper.get('[data-testid="menu-group-finance"] .el-sub-menu__title').trigger('click')
  expect(shown(wrapper, 'menu-demo.orders')).toBe(false)
  expect(shown(wrapper, 'menu-demo.overview')).toBe(true)
  expect(shown(wrapper, 'menu-parameters')).toBe(true)

  await wrapper.get('[data-testid="menu-group-finance"] .el-sub-menu__title').trigger('click')
  expect(shown(wrapper, 'menu-demo.orders')).toBe(true)
  wrapper.unmount()
})

it.each([
  { activePath: '/', expanded: false },
  { activePath: '/apps/demo/orders/42', expanded: true },
])('菜单异步加载后仅展开当前页所在分组：$activePath', async ({ activePath, expanded }) => {
  const wrapper = await mountWithShell(AppLayout, {
    props: { title: 'SaaS Forge', menu: [], activePath },
    attachTo: document.body,
  })
  await wrapper.setProps({ menu: nested })
  await flushPromises()

  expect(shown(wrapper, 'menu-demo.overview')).toBe(expanded)
  expect(shown(wrapper, 'menu-demo.orders')).toBe(expanded)
  expect(shown(wrapper, 'menu-parameters')).toBe(false)
  wrapper.unmount()
})
