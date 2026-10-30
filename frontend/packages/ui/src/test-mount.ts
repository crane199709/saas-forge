import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { Component } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'

/** 按 Shell 的方式挂载：已安装 Element Plus、zh-CN 的全局 i18n 与路由，组件文案经 local scope 取得。 */
export async function mountWithShell(
  component: Component,
  options: Parameters<typeof mount>[1] = {},
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
  })
  await router.push('/')
  const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': {} } })
  return mount(component, { ...options, global: { plugins: [ElementPlus, i18n, router] } })
}
