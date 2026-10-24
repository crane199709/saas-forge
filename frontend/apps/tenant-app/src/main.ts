import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 内置文案默认是英文，与第一期只支持简体中文保持一致
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { loadSubApp } from './load-sub-app'

document.title = i18n.global.t('app.title')

const router = createRouter({
  history: createWebHistory(),
  routes: [{ path: '/', component: { render: () => null } }],
})
let sharedChecks: Record<string, boolean> = {}
let loadError = ''
try {
  const subApp = await loadSubApp()
  router.addRoute({ path: '/apps/demo', children: subApp.routes })
  sharedChecks = subApp.checks
} catch (error) {
  console.error(error)
  loadError = i18n.global.t('spike.loadError')
}
createApp(App, { sharedChecks, loadError })
  .use(createPinia())
  .use(i18n)
  .use(router)
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')
