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
  expect(wrapper.get('.el-menu-item-group').text()).toContain('示例')
  expect(wrapper.get('[data-testid="menu-demo.orders"]').text()).toBe('订单')
  expect(wrapper.get('main').text()).toBe('页面内容')
})

it('高亮与当前路径最长匹配的菜单项，下级路径归到所属项', async () => {
  const wrapper = await mountWithShell(AppLayout, {
    props: { title: 'SaaS Forge', menu, activePath: '/apps/demo/orders/42' },
  })

  expect(wrapper.get('.el-menu-item.is-active').text()).toBe('订单')
  await wrapper.setProps({ activePath: '/members' })
  expect(wrapper.get('.el-menu-item.is-active').text()).toBe('成员')
})
