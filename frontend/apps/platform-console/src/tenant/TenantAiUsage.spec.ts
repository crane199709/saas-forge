import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { expect, it, vi } from 'vitest'
import TenantAiUsage from './TenantAiUsage.vue'

const snapshot = {
  month: '2026-11',
  businessZone: 'Asia/Shanghai',
  received: true,
  inputTokens: 9000,
  outputTokens: 1200,
  cachedInputTokens: 2000,
  totalTokens: 10200,
  estimatedTokens: 65536,
  turnCount: 3,
  toolCalls: 2,
  unknownUsageTurns: 1,
  days: [
    {
      usageDate: '2026-11-14',
      version: 2,
      inputTokens: 9000,
      outputTokens: 1200,
      cachedInputTokens: 2000,
      turnCount: 3,
      toolCalls: 2,
      unknownUsageTurns: 1,
      estimatedTokens: 65536,
      reportedAt: '2026-11-14T05:10:00Z',
    },
  ],
}
it('运营只读查询展示未知估计与日明细，切换租户不显示前一租户数据', async () => {
  const aiUsage = vi.fn().mockResolvedValue(snapshot)
  const wrapper = mount(TenantAiUsage, {
    props: { tenantId: 'a', api: { aiUsage } },
    global: { plugins: [ElementPlus] },
  })
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(aiUsage).toHaveBeenCalledWith('a', undefined)
  expect(wrapper.text()).toContain('65,536')
  expect(wrapper.text()).toContain('1 个回合的用量不完整')
  expect(wrapper.text()).toContain('2026-11-14')
  await wrapper.setProps({ tenantId: 'b' })
  expect(wrapper.text()).not.toContain('65,536')
  expect(wrapper.text()).toContain('查询本月')
  wrapper.unmount()
})
it('尚未回报与查询失败有独立状态，关闭后不回写异步结果', async () => {
  const aiUsage = vi.fn().mockResolvedValue({ ...snapshot, received: false, days: [] })
  const wrapper = mount(TenantAiUsage, {
    props: { tenantId: 'a', api: { aiUsage } },
    global: { plugins: [ElementPlus] },
  })
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('尚未收到该月用量')
  aiUsage.mockRejectedValue(new Error('unavailable'))
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('用量查询失败')
  wrapper.unmount()
})

it('晚到的旧租户响应不会覆盖新租户查询', async () => {
  let resolveFirst!: (value: typeof snapshot) => void
  const first = new Promise<typeof snapshot>((resolve) => {
    resolveFirst = resolve
  })
  const aiUsage = vi
    .fn()
    .mockReturnValueOnce(first)
    .mockResolvedValue({ ...snapshot, received: false, days: [] })
  const wrapper = mount(TenantAiUsage, {
    props: { tenantId: 'a', api: { aiUsage } },
    global: { plugins: [ElementPlus] },
  })
  await wrapper.get('form').trigger('submit')
  await wrapper.setProps({ tenantId: 'b' })
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  resolveFirst(snapshot)
  await flushPromises()
  expect(wrapper.text()).toContain('尚未收到该月用量')
  expect(wrapper.text()).not.toContain('65,536')
  wrapper.unmount()
})
