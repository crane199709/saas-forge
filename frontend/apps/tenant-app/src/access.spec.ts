import { mount } from '@vue/test-utils'
import { createAccessControl, type TenantAccess } from '@forge/sdk'
import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

function control(access: TenantAccess) {
  const api = vi.fn().mockResolvedValue(access)
  return { access: createAccessControl({ api }), api }
}

const Buttons = {
  template: `<div>
    <button data-testid="create" v-perm="'order:create'">新建</button>
    <button data-testid="either" v-perm="['order:approve', 'order:read']">查看</button>
  </div>`,
}

describe('createAccessControl', () => {
  it('从 IAM 读取当前 Membership 的权限并按权限码查询', async () => {
    const { access, api } = control({ permissions: ['order:read'], menus: [] })
    await access.load()

    expect(api).toHaveBeenCalledWith('/iam/auth/access')
    expect(access.has('order:read')).toBe(true)
    expect(access.has('order:create')).toBe(false)
    expect(access.has(['order:create', 'order:read'])).toBe(true)
  })

  it('v-perm 按权限显示或隐藏元素，权限变化后随之更新', async () => {
    const { access } = control({ permissions: ['order:read'], menus: [] })
    await access.load()
    const wrapper = mount(Buttons, { global: { plugins: [access] }, attachTo: document.body })

    expect(wrapper.get('[data-testid="create"]').isVisible()).toBe(false)
    expect(wrapper.get('[data-testid="either"]').isVisible()).toBe(true)

    access.clear()
    await nextTick()
    expect(wrapper.get('[data-testid="either"]').isVisible()).toBe(false)
    wrapper.unmount()
  })
})
