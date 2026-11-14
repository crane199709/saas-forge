import { expect, it } from 'vitest'
import { h } from 'vue'
import { EmptyState } from './EmptyState'
import { defaultMenuIcon, resolveMenuIcon } from './icons'
import { mountWithShell } from './test-mount'

it('只传标题时渲染标题，不渲染图标、描述与操作区', async () => {
  const wrapper = await mountWithShell(EmptyState, { props: { title: '暂无待办' } })

  expect(wrapper.get('.empty-state-title').text()).toBe('暂无待办')
  expect(wrapper.find('.empty-state-icon').exists()).toBe(false)
  expect(wrapper.find('.empty-state-description').exists()).toBe(false)
  expect(wrapper.find('.empty-state-actions').exists()).toBe(false)
})

it('按标题、描述、操作的顺序渲染三种入参组合', async () => {
  const wrapper = await mountWithShell(EmptyState, {
    props: { title: '暂无公告', description: '公告发布后会出现在这里。' },
    slots: { default: () => h('a', { href: '/notifications' }, '查看全部公告') },
  })

  const children = [...wrapper.get('.empty-state').element.children]
  expect(children.map((child) => child.className)).toEqual([
    'empty-state-title',
    'empty-state-description',
    'empty-state-actions',
  ])
  expect(wrapper.get('.empty-state-description').text()).toBe('公告发布后会出现在这里。')
  expect(wrapper.get('.empty-state-actions a').text()).toBe('查看全部公告')
})

it('图标名经白名单解析后渲染，并对读屏隐藏', async () => {
  const wrapper = await mountWithShell(EmptyState, {
    props: { title: '暂无通知', icon: 'Bell' },
  })

  const icon = wrapper.get('.empty-state-icon')
  expect(icon.attributes('aria-hidden')).toBe('true')
  expect(wrapper.findComponent(resolveMenuIcon('bell')).exists()).toBe(true)
})

it('图标名不在白名单时回退默认图标，仍然渲染', async () => {
  const wrapper = await mountWithShell(EmptyState, {
    props: { title: '暂无数据', icon: 'not-in-whitelist' },
  })

  expect(wrapper.findComponent(defaultMenuIcon).exists()).toBe(true)
})
