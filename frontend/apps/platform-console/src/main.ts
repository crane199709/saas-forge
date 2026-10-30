import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 内置文案默认是英文，与第一期只支持简体中文保持一致
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createPlatformClient, platformClientKey } from '@forge/sdk'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { createWebHistory } from 'vue-router'
import App from './App.vue'
import { i18n } from './i18n'
import { createConsoleRouter, installGuard } from './router'
import { consoleAccessKey, createConsoleAccess } from './shell'

document.title = i18n.global.t('app.title')

// Shell 只创建一个 Platform Realm 客户端，刷新队列只在同一实例内合并。
// 会话存于 localStorage，同一浏览器的标签页共享登录（ADR-0017）。
const client = createPlatformClient(localStorage)
const access = createConsoleAccess(client)
const router = createConsoleRouter(createWebHistory())
// 首次导航由守卫等待这次读取，刷新页面时可直接进入有权限的页面。
void access.reload()
installGuard(router, client, access)

createApp(App)
  .provide(platformClientKey, client)
  .provide(consoleAccessKey, access)
  .use(createPinia())
  .use(i18n)
  .use(router)
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')
