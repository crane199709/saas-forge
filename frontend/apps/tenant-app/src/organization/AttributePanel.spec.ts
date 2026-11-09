import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import { createTenantClient } from '@crane199709/sdk'
import { i18n } from '../i18n'
import AttributePanel from './AttributePanel.vue'
import { createOrganizationApi } from './org-api'

afterEach(() => {
  sessionStorage.clear()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

const ok = () => new Response(JSON.stringify({ code: 'ok', httpStatus: 200, data: {} }))

async function mountPanel(fetcher: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetcher)
  sessionStorage.setItem(
    'forge.tenant.session',
    JSON.stringify({ accessToken: 'access', refreshToken: 'r', membershipId: 'm' }),
  )
  const wrapper = mount(AttributePanel, {
    props: {
      api: createOrganizationApi(createTenantClient(sessionStorage)),
      attributes: [
        { id: 'a1', code: 'cost_center', name: '成本中心', type: 'string', required: false },
      ],
    },
    global: { plugins: [i18n, ElementPlus] },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

it('点击新增后在弹窗中填写并提交', async () => {
  const fetcher = vi.fn().mockImplementation(async () => ok())
  const wrapper = await mountPanel(fetcher)

  await wrapper.get('[data-testid="attribute-create"]').trigger('click')
  await flushPromises()
  await wrapper.get('input[data-testid="attribute-code"]').setValue('employee_no')
  await wrapper.get('input[data-testid="attribute-name"]').setValue(' 工号 ')
  await wrapper.get('[data-testid="attribute-save"]').trigger('click')
  await flushPromises()

  expect(JSON.parse(fetcher.mock.calls[0]![1].body)).toEqual({
    code: 'employee_no',
    name: '工号',
    type: 'string',
    required: false,
  })
  expect(wrapper.emitted('changed')).toHaveLength(1)
  wrapper.unmount()
})

it('编辑时编码不可修改，只提交名称与必填', async () => {
  const fetcher = vi.fn().mockImplementation(async () => ok())
  const wrapper = await mountPanel(fetcher)

  await wrapper.get('[data-testid="attribute-edit-cost_center"]').trigger('click')
  await flushPromises()
  expect(
    (wrapper.get('input[data-testid="attribute-code"]').element as HTMLInputElement).disabled,
  ).toBe(true)
  await wrapper.get('input[data-testid="attribute-name"]').setValue('成本归属')
  await wrapper.get('[data-testid="attribute-save"]').trigger('click')
  await flushPromises()

  expect(fetcher.mock.calls[0]![1].method).toBe('PUT')
  expect(JSON.parse(fetcher.mock.calls[0]![1].body)).toEqual({ name: '成本归属', required: false })
  wrapper.unmount()
})
