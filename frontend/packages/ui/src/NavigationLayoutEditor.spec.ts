import type { NavigationLayout } from '@crane199709/sdk'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { ElTree } from 'element-plus'
import { afterEach, expect, it } from 'vitest'
import type { NavigationEntry } from './navigation'
import { NavigationLayoutEditor } from './NavigationLayoutEditor'
import { mountWithShell } from './test-mount'

afterEach(() => {
  document.body.innerHTML = ''
})

const catalog: NavigationEntry[] = [
  { page: 'members', title: '成员管理' },
  { page: 'roles', title: '角色管理' },
  {
    menu: 'demo',
    title: '示例应用',
    children: [
      { menu: 'demo.overview', title: '订单概览' },
      { menu: 'demo.attachments', title: '订单附件' },
    ],
  },
]

const finance: NavigationLayout = {
  nodes: [
    { page: 'members' },
    {
      group: 'g1',
      name: '财务',
      children: [{ menu: 'demo.attachments' }, { menu: 'retired.report' }],
    },
    { page: 'roles' },
    { menu: 'demo', children: [{ menu: 'demo.overview' }] },
  ],
}

async function mountEditor(layout: NavigationLayout | null) {
  const wrapper = await mountWithShell(NavigationLayoutEditor, {
    props: { catalog, layout },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

const rows = (wrapper: VueWrapper) =>
  wrapper.findAll('[data-testid^="navigation-node-"]').map((row) => row.text())

async function fillName(name: string) {
  const input = document.querySelector<HTMLInputElement>('[data-testid="navigation-group-name"]')!
  input.value = name
  input.dispatchEvent(new Event('input'))
  await flushPromises()
  document.querySelector<HTMLButtonElement>('[data-testid="navigation-group-confirm"]')!.click()
  await flushPromises()
}

async function saved(wrapper: VueWrapper) {
  await wrapper.get('[data-testid="navigation-save"]').trigger('click')
  return wrapper.emitted<[NavigationLayout]>('save')!.at(-1)![0]
}

it('按布局显示目录中的节点，只有自定义分组可以改名和删除', async () => {
  const wrapper = await mountEditor(finance)

  expect(rows(wrapper)).toEqual([
    expect.stringContaining('成员管理'),
    expect.stringContaining('财务'),
    expect.stringContaining('订单附件'),
    expect.stringContaining('角色管理'),
    expect.stringContaining('示例应用'),
    expect.stringContaining('订单概览'),
  ])
  expect(wrapper.find('[data-testid="navigation-rename-g1"]').exists()).toBe(true)
  expect(wrapper.find('[data-testid="navigation-remove-g1"]').exists()).toBe(true)
  expect(wrapper.find('[data-testid="navigation-node-menu:demo"]').text()).not.toContain('重命名')
  expect(wrapper.find('[data-testid="navigation-node-menu:demo"]').text()).not.toContain('删除')
})

it('保存整棵布局，目录外的引用留在原父节点末尾', async () => {
  const wrapper = await mountEditor(finance)

  expect(await saved(wrapper)).toEqual(finance)
})

it('新建分组加在顶层末尾，名称在弹窗中填写', async () => {
  const wrapper = await mountEditor(null)

  await wrapper.get('[data-testid="navigation-add-group"]').trigger('click')
  await fillName('  报表  ')

  const layout = await saved(wrapper)
  expect(layout.nodes.at(-1)).toEqual({ group: expect.any(String), name: '报表', children: [] })
  expect(rows(wrapper).at(-1)).toContain('报表')
})

it('名称为空时不能确认', async () => {
  const wrapper = await mountEditor(null)

  await wrapper.get('[data-testid="navigation-add-group"]').trigger('click')

  const confirm = document.querySelector('[data-testid="navigation-group-confirm"]')!
  expect(confirm.hasAttribute('disabled')).toBe(true)
  expect(
    document.querySelector('[data-testid="navigation-group-name"]')!.getAttribute('maxlength'),
  ).toBe('32')
})

it('重命名自定义分组', async () => {
  const wrapper = await mountEditor(finance)

  await wrapper.get('[data-testid="navigation-rename-g1"]').trigger('click')
  expect(
    document.querySelector<HTMLInputElement>('[data-testid="navigation-group-name"]')!.value,
  ).toBe('财务')
  await fillName('财务报表')

  const layout = await saved(wrapper)
  expect(layout.nodes[1]).toMatchObject({ group: 'g1', name: '财务报表' })
})

it('删除自定义分组时子节点移到它原来的位置，目录外的引用放到顶层末尾', async () => {
  const wrapper = await mountEditor(finance)

  await wrapper.get('[data-testid="navigation-remove-g1"]').trigger('click')
  await flushPromises()

  expect(await saved(wrapper)).toEqual({
    nodes: [
      { page: 'members' },
      { menu: 'demo.attachments' },
      { page: 'roles' },
      { menu: 'demo', children: [{ menu: 'demo.overview' }] },
      { menu: 'retired.report' },
    ],
  })
})

it('拖拽时页面不接收子节点，不超过三层', async () => {
  const wrapper = await mountEditor(finance)
  const tree = wrapper.getComponent(ElTree)
  const allowDrop = tree.props('allowDrop') as (...args: unknown[]) => boolean
  const node = (key: string) => tree.vm.getNode(key)

  expect(allowDrop(node('page:roles'), node('page:members'), 'inner')).toBe(false)
  expect(allowDrop(node('page:roles'), node('group:g1'), 'inner')).toBe(true)
  expect(allowDrop(node('page:roles'), node('menu:demo.attachments'), 'next')).toBe(true)
  expect(allowDrop(node('menu:demo'), node('group:g1'), 'inner')).toBe(true)
  expect(allowDrop(node('menu:demo'), node('menu:demo.attachments'), 'next')).toBe(true)
})

it('恢复默认交给页面处理，忙碌时按钮不可用', async () => {
  const wrapper = await mountEditor(finance)

  await wrapper.get('[data-testid="navigation-reset"]').trigger('click')
  expect(wrapper.emitted('reset')).toHaveLength(1)

  await wrapper.setProps({ busy: true })
  expect(wrapper.get('[data-testid="navigation-save"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[data-testid="navigation-reset"]').attributes('disabled')).toBeDefined()
})

it('布局变化后按新布局重新显示', async () => {
  const wrapper = await mountEditor(finance)

  await wrapper.setProps({ layout: null })
  await flushPromises()

  expect(rows(wrapper)[1]).toContain('角色管理')
})
