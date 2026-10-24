import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import App from './App.vue'
import { i18n } from './i18n'

describe('App', () => {
  it('按 i18n key 渲染简体中文文案', () => {
    const wrapper = mount(App, { global: { plugins: [i18n, ElementPlus] } })

    expect(wrapper.text()).toContain('欢迎使用 SaaS Forge 平台控制台')
  })
})
