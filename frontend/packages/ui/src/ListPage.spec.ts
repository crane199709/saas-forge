import type { Page } from '@crane199709/sdk'
import { ElTableColumn } from 'element-plus'
import { expect, it, vi } from 'vitest'
import { h } from 'vue'
import { ListPage } from './ListPage'
import { mountWithShell } from './test-mount'

interface Order {
  id: string
  title: string
}

function page(number: number, totalElements: number): Page<Order> {
  return {
    items: [{ id: `o-${number}`, title: `第 ${number} 页订单` }],
    page: { number, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) },
  }
}

function mountList(props: Record<string, unknown>) {
  return mountWithShell(ListPage, {
    props,
    slots: {
      search: () => h('button', { class: 'search' }, '查询'),
      default: () => h(ElTableColumn, { prop: 'title', label: '标题' }),
    },
  })
}

it('渲染搜索区、表格行与分页信息', async () => {
  const wrapper = await mountList({ page: page(2, 45) })

  expect(wrapper.get('.list-page-search .search').text()).toBe('查询')
  expect(wrapper.find('[role="group"]').exists()).toBe(false)
  expect(wrapper.find('.el-table button').exists()).toBe(false)
  // el-table 在列注册后的下一轮渲染出行
  await vi.waitFor(() => expect(wrapper.get('.el-table').text()).toContain('第 2 页订单'))
  expect(wrapper.get('[data-testid="list-page-total"]').text()).toBe('共 45 条')
  expect(wrapper.get('.el-pager .is-active').text()).toBe('2')
  expect(wrapper.findAll('.el-pager .number').map((item) => item.text())).toEqual(['1', '2', '3'])
})

it('翻页时按新页码触发 page-change', async () => {
  const wrapper = await mountList({ page: page(1, 45) })

  await wrapper.get('.btn-next').trigger('click')
  await wrapper.findAll('.el-pager .number')[2]!.trigger('click')

  expect(wrapper.emitted('page-change')).toEqual([[2], [3]])
})

it('只有一页时不显示翻页，加载中翻页不可用', async () => {
  const wrapper = await mountList({ page: page(1, 3) })
  expect(wrapper.get('[data-testid="list-page-total"]').text()).toBe('共 3 条')
  expect(wrapper.find('.el-pagination').exists()).toBe(false)

  await wrapper.setProps({ page: page(1, 45), loading: true })
  expect(wrapper.get('.list-page').attributes('aria-busy')).toBe('true')
  expect(wrapper.get('.btn-next').attributes('disabled')).toBeDefined()
})

it('首次加载完成前没有分页信息', async () => {
  const wrapper = await mountList({ page: null, emptyText: '暂无订单' })

  expect(wrapper.get('.el-table').text()).toContain('暂无订单')
  expect(wrapper.find('.list-page-footer').exists()).toBe(false)
})

it('按受控 deleted 切换未删除与已删除视图', async () => {
  const wrapper = await mountList({ page: page(2, 45), deleted: false })

  expect(wrapper.get('[role="group"]').attributes('aria-label')).toBe('记录视图')
  expect(wrapper.get('button[aria-pressed="true"]').text()).toBe('未删除')
  await wrapper.get('button[aria-pressed="false"]').trigger('click')
  expect(wrapper.emitted('deleted-change')).toEqual([[true]])
  expect(wrapper.emitted('page-change')).toBeUndefined()

  await wrapper.setProps({ deleted: true, page: page(1, 45) })
  expect(wrapper.get('button[aria-pressed="true"]').text()).toBe('已删除')
  await wrapper.get('button[aria-pressed="false"]').trigger('click')
  expect(wrapper.emitted('deleted-change')).toEqual([[true], [false]])
})

it('已删除视图提供恢复操作并把完整条目交给页面处理', async () => {
  const result = page(1, 3)
  const wrapper = await mountList({ page: result, deleted: true })

  await vi.waitFor(() => expect(wrapper.findAll('.el-table button')).toHaveLength(1))
  expect(wrapper.get('.el-table button').text()).toBe('恢复')
  await wrapper.get('.el-table button').trigger('click')
  expect(wrapper.emitted('restore')).toEqual([[result.items[0]]])

  await wrapper.setProps({ deleted: false })
  await vi.waitFor(() => expect(wrapper.find('.el-table button').exists()).toBe(false))
})

it('加载期间禁用视图切换和恢复', async () => {
  const wrapper = await mountList({ page: page(1, 3), deleted: true, loading: true })

  await vi.waitFor(() => expect(wrapper.find('.el-table button').exists()).toBe(true))
  const buttons = wrapper.findAll('[role="group"] button, .el-table button')
  expect(buttons).toHaveLength(3)
  for (const button of buttons) {
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
  }
  expect(wrapper.emitted('deleted-change')).toBeUndefined()
  expect(wrapper.emitted('restore')).toBeUndefined()
})
