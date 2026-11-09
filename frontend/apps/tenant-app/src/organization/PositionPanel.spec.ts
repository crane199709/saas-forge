import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import PositionPanel from './PositionPanel.vue'
import { createOrganizationApi } from './org-api'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

it('岗位恢复允许同名，取消不提交，确认后通知有效列表刷新', async () => {
  let restored = false
  const fetcher = vi.fn(async (_url: string, init?: RequestInit) => {
    if (init?.method === 'POST') restored = true
    return new Response(
      JSON.stringify({
        code: 'ok',
        httpStatus: 200,
        data: restored ? [] : [{ id: 'deleted', name: '工程师', updatedAt: null, updatedBy: null }],
      }),
    )
  })
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'm' }),
  )
  const wrapper = mount(PositionPanel, {
    props: {
      api: createOrganizationApi(createTenantClient(sessionStorage)),
      positions: [{ id: 'active', name: '工程师' }],
    },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
  await wrapper.get('[data-testid="restore-view-deleted"]').trigger('click')
  await flushPromises()
  expect(wrapper.find('[data-testid="position-active"]').exists()).toBe(false)
  await wrapper.get('[data-testid="restore-deleted"]').trigger('click')
  await flushPromises()
  const cancel = [...document.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')].find(
    (button) => button.textContent === '取消',
  )!
  cancel.click()
  await flushPromises()
  expect(restored).toBe(false)
  await wrapper.get('[data-testid="restore-deleted"]').trigger('click')
  await flushPromises()
  document.querySelector<HTMLButtonElement>('[data-testid="restore-confirm"]')!.click()
  await flushPromises()
  expect(restored).toBe(true)
  expect(wrapper.find('[data-testid="restore-deleted"]').exists()).toBe(false)
  expect(wrapper.emitted('changed')).toHaveLength(1)
  wrapper.unmount()
})

function ok(data: unknown = null) {
  return new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data }))
}

async function mountPositions(fetcher: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'm' }),
  )
  const wrapper = mount(PositionPanel, {
    props: {
      api: createOrganizationApi(createTenantClient(sessionStorage)),
      positions: [{ id: 'p1', name: '工程师' }],
    },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

it('点击新增后在弹窗中填写，提交后关闭并通知刷新', async () => {
  const fetcher = vi.fn().mockResolvedValue(ok({ id: 'p2', name: '设计师' }))
  const wrapper = await mountPositions(fetcher)
  expect(wrapper.find('input[data-testid="position-name"]').exists()).toBe(false)

  await wrapper.get('[data-testid="position-create"]').trigger('click')
  await flushPromises()
  await wrapper.get('input[data-testid="position-name"]').setValue(' 设计师 ')
  await wrapper.get('[data-testid="position-save"]').trigger('click')
  await flushPromises()

  expect(fetcher.mock.calls[0]![1].method).toBe('POST')
  expect(JSON.parse(fetcher.mock.calls[0]![1].body)).toEqual({ name: '设计师' })
  expect(wrapper.emitted('changed')).toHaveLength(1)
  wrapper.unmount()
})

it('编辑时带出当前名称，提交改名', async () => {
  const fetcher = vi.fn().mockResolvedValue(ok({ id: 'p1', name: '高级工程师' }))
  const wrapper = await mountPositions(fetcher)

  await wrapper.get('[data-testid="position-edit-p1"]').trigger('click')
  await flushPromises()
  const input = wrapper.get('input[data-testid="position-name"]')
  expect((input.element as HTMLInputElement).value).toBe('工程师')
  await input.setValue('高级工程师')
  await wrapper.get('[data-testid="position-save"]').trigger('click')
  await flushPromises()

  expect(fetcher.mock.calls[0]![0]).toBe(`${location.origin}/iam/org/positions/p1`)
  expect(fetcher.mock.calls[0]![1].method).toBe('PUT')
  wrapper.unmount()
})
