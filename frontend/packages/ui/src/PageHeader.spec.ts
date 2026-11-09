import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import { h } from 'vue'
import { PageHeader } from './PageHeader'

it('渲染标题、说明与页面操作', () => {
  const wrapper = mount(PageHeader, {
    props: { title: '角色管理', description: '配置角色的权限' },
    slots: { actions: () => h('button', '新建') },
  })

  expect(wrapper.get('h2').text()).toBe('角色管理')
  expect(wrapper.get('p').text()).toBe('配置角色的权限')
  expect(wrapper.get('.page-header-actions').text()).toBe('新建')
})

it('没有说明和操作时不渲染对应区域', () => {
  const wrapper = mount(PageHeader, { props: { title: '角色管理' } })

  expect(wrapper.find('p').exists()).toBe(false)
  expect(wrapper.find('.page-header-actions').exists()).toBe(false)
})
