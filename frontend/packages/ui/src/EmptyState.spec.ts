import { Bell, Menu } from '@element-plus/icons-vue'
import { expect, it } from 'vitest'
import { h } from 'vue'
import { EmptyState } from './EmptyState'
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

it('图标名经白名单解析后渲染对应的图标，并对读屏隐藏', async () => {
  const wrapper = await mountWithShell(EmptyState, {
    props: { title: '暂无通知', icon: 'bell' },
  })

  const icon = wrapper.get('.empty-state-icon')
  expect(icon.attributes('aria-hidden')).toBe('true')
  expect(wrapper.findComponent(Bell).exists()).toBe(true)
})

it('图标名不在白名单时回退默认图标，仍然渲染', async () => {
  const wrapper = await mountWithShell(EmptyState, {
    // 白名单外的名字来自外部清单，运行时回退；这里刻意越过类型给出非法名字。
    props: { title: '暂无数据', icon: 'not-in-whitelist' as never },
  })

  expect(wrapper.findComponent(Menu).exists()).toBe(true)
})

it('图标尺寸取组件层 token，不复用间距与字号档位', async () => {
  const wrapper = await mountWithShell(EmptyState, {
    props: { title: '暂无通知', icon: 'bell' },
  })

  const element = wrapper.get('.empty-state-icon').element as HTMLElement
  // 取值仍是 48px / 20px，改动间距档不会顺带改空状态尺寸。
  expect(element.style.getPropertyValue('--forge-empty-icon-size')).toBe('48px')
  expect(element.style.getPropertyValue('--forge-empty-icon-glyph')).toBe('20px')
  expect(element.style.width).toBe('var(--forge-empty-icon-size)')
  expect(element.style.height).toBe('var(--forge-empty-icon-size)')
  expect(element.style.fontSize).toBe('var(--forge-empty-icon-glyph)')
})
