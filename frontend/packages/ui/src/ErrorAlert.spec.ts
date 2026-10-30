import { expect, it, vi } from 'vitest'
import { ErrorAlert } from './ErrorAlert'
import { mountWithShell } from './test-mount'

it('展示文案与 traceId，点击复制 traceId', async () => {
  const writeText = vi.fn(() => Promise.resolve())
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
  const wrapper = await mountWithShell(ErrorAlert, {
    props: { tip: { message: '订单不存在', traceId: 'trace-1' } },
  })

  expect(wrapper.get('[role="alert"]').text()).toContain('订单不存在')
  expect(wrapper.text()).toContain('追踪 ID：trace-1')
  await wrapper.get('[data-testid="copy-trace-id"]').trigger('click')
  expect(writeText).toHaveBeenCalledWith('trace-1')
})

it('没有 traceId 时只展示文案', async () => {
  const wrapper = await mountWithShell(ErrorAlert, { props: { tip: { message: '请求失败' } } })

  expect(wrapper.text()).toContain('请求失败')
  expect(wrapper.text()).not.toContain('追踪 ID')
  expect(wrapper.find('[data-testid="copy-trace-id"]').exists()).toBe(false)
})
