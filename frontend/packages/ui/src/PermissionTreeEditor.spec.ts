import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { afterEach, expect, it } from 'vitest'
import { PermissionTreeEditor } from './PermissionTreeEditor'
import type { PermissionSection, PermissionTree } from './permission-selection'
import { mountWithShell } from './test-mount'

afterEach(() => {
  document.body.innerHTML = ''
})

const tree: PermissionTree = {
  menus: [
    {
      code: 'orders',
      name: '订单概览',
      icon: null,
      permission: { code: 'order:read', name: '查看订单', sharedWith: ['attachments'] },
      actions: [
        { code: 'order:create', name: '创建订单', sharedWith: [] },
        { code: 'order:delete', name: '删除订单', sharedWith: [] },
      ],
      children: [],
      note: 'demo',
    },
    {
      code: 'attachments',
      name: '订单附件',
      icon: null,
      permission: { code: 'order:read', name: '查看订单', sharedWith: ['orders'] },
      actions: [],
      children: [],
    },
    { code: 'help', name: '帮助', icon: null, permission: null, actions: [], children: [] },
  ],
  others: [{ code: 'report:sync', name: '同步报表', service: 'report-service', group: '报表' }],
}

async function mountTree(
  props: { pageAsChild?: boolean; sections?: PermissionSection[] } = {},
  selected: string[] = [],
) {
  const wrapper: VueWrapper = await mountWithShell(PermissionTreeEditor, {
    props: {
      tree,
      modelValue: selected,
      ...props,
      'onUpdate:modelValue': (codes: string[]) => wrapper.setProps({ modelValue: codes }),
    },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

const input = (id: string) =>
  document.querySelector(`[data-testid="${id}"] input`) as HTMLInputElement

async function click(id: string) {
  input(id).click()
  await flushPromises()
}

const value = (wrapper: VueWrapper) => [...(wrapper.props('modelValue') as string[])].sort()

it('默认方式下页面勾选框代表页面权限，勾选页面同时授予全部操作', async () => {
  const wrapper = await mountTree()
  expect(document.querySelector('[data-testid="page-orders"]')).toBeNull()
  expect(input('menu-help').disabled).toBe(true)
  expect(document.body.textContent).toContain('始终可见')
  expect(document.body.textContent).toContain('与「订单附件」共用权限，会一起显示')

  await click('menu-orders')
  expect(value(wrapper)).toEqual(['order:create', 'order:delete', 'order:read'])
  expect(input('menu-attachments').checked).toBe(true)

  await click('action-orders-order:delete')
  expect(input('menu-orders').checked).toBe(true)
  expect(value(wrapper)).toEqual(['order:create', 'order:read'])

  await click('menu-orders')
  expect(value(wrapper)).toEqual([])
})

it('页面权限作为子项时，页面节点只负责全选和半选', async () => {
  const wrapper = await mountTree({ pageAsChild: true })
  expect(document.querySelector('[data-testid="menu-orders"]')?.parentElement?.textContent).toBe(
    '订单概览demo',
  )
  expect(document.querySelector('[data-testid="page-orders"]')?.textContent).toBe(
    '查看订单（order:read）',
  )

  await click('menu-orders')
  expect(value(wrapper)).toEqual(['order:create', 'order:delete', 'order:read'])
  expect(input('page-attachments').checked).toBe(true)

  await click('action-orders-order:delete')
  expect(input('menu-orders').checked).toBe(false)
  expect(input('menu-orders').indeterminate).toBe(true)
  expect(input('page-orders').checked).toBe(true)

  // 取消页面权限时其下操作一并取消，共用的页面同步
  await click('page-orders')
  expect(value(wrapper)).toEqual([])
  expect(input('menu-attachments').checked).toBe(false)

  // 勾选操作时自动勾上页面权限
  await click('action-orders-order:create')
  expect(value(wrapper)).toEqual(['order:create', 'order:read'])
  expect(input('page-orders').checked).toBe(true)
  expect(input('menu-orders').indeterminate).toBe(true)
  expect(input('menu-help').disabled).toBe(true)
})

it('调用方的分区显示在功能权限之后，逐项勾选', async () => {
  const wrapper = await mountTree(
    {
      sections: [
        { key: 'retired', title: '已下线', permissions: [{ code: 'old:read', name: 'old:read' }] },
        { key: 'empty', title: '空分区', permissions: [] },
      ],
    },
    ['old:read'],
  )
  const text = document.body.textContent!
  expect(text.indexOf('功能权限')).toBeLessThan(text.indexOf('已下线'))
  expect(text).not.toContain('空分区')
  expect(input('perm-old:read').checked).toBe(true)

  await click('perm-old:read')
  await click('perm-report:sync')
  expect(value(wrapper)).toEqual(['report:sync'])
})
