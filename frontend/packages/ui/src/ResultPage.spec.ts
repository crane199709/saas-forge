import { expect, it } from 'vitest'
import { h } from 'vue'
import { ResultPage } from './ResultPage'
import { mountWithShell } from './test-mount'

it('按种类展示默认文案，默认插槽作为操作区', async () => {
  const wrapper = await mountWithShell(ResultPage, {
    props: { status: '404' },
    slots: { default: () => h('button', '返回首页') },
  })

  const result = wrapper.get('[data-testid="status-404"]')
  expect(result.text()).toContain('页面不存在')
  expect(result.get('button').text()).toBe('返回首页')
})

it('标题与说明可由使用方覆盖', async () => {
  const wrapper = await mountWithShell(ResultPage, {
    props: { status: 'no-permission', subTitle: '请联系超级管理员' },
  })

  const result = wrapper.get('[data-testid="status-no-permission"]')
  expect(result.text()).toContain('暂无可用权限')
  expect(result.text()).toContain('请联系超级管理员')
})
