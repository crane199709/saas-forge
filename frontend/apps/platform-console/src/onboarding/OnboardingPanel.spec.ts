import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import OnboardingPanel from './OnboardingPanel.vue'
import { i18n } from '../i18n'
import ElementPlus from 'element-plus'
describe('服务接入诊断', () => {
  it('独立显示配置未知与运行状态，刷新重新读取', async () => {
    const read = vi.fn().mockResolvedValue({
      directoryAvailable: true,
      services: [
        {
          service: 'demo-service',
          configuration: {
            status: 'UNKNOWN',
            source: 'NACOS_CLIENT',
            checkedAt: '2026-11-12T00:00:00Z',
            missing: ['NACOS_UNAVAILABLE'],
          },
          runtime: {
            status: 'LOADED',
            checkedAt: '2026-11-12T00:00:00Z',
            reporting: { enabled: true, phase: 'REPORTED', attempts: 1, reportedVersion: '1' },
          },
          request: { status: 'NOT_CHECKED', detail: 'ROUTE_PREFIX_MISSING' },
        },
      ],
    })
    const wrapper = mount(OnboardingPanel, {
      props: { api: { read } },
      global: { plugins: [i18n, ElementPlus] },
    })
    await vi.waitFor(() => expect(wrapper.text()).toContain('配置未知'))
    expect(wrapper.text()).toContain('运行已加载')
    expect(wrapper.text()).toContain('未探测')
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('刷新'))!
      .trigger('click')
    await nextTick()
    expect(read).toHaveBeenCalledTimes(2)
  })
})
