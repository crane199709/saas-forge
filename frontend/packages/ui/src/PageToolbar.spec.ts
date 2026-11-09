import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'
import { h } from 'vue'
import { PageToolbar } from './PageToolbar'

it('筛选与主操作分区渲染', () => {
  const wrapper = mount(PageToolbar, {
    slots: { default: () => h('input'), actions: () => h('button', '新建') },
  })

  expect(wrapper.find('.page-toolbar-filters input').exists()).toBe(true)
  expect(wrapper.get('.page-toolbar-actions').text()).toBe('新建')
})

it('没有主操作时不渲染操作区', () => {
  const wrapper = mount(PageToolbar, { slots: { default: () => h('input') } })

  expect(wrapper.find('.page-toolbar-actions').exists()).toBe(false)
})
