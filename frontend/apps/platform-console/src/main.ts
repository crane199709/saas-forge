// Element Plus 组件与样式由构建插件按需引入；@crane199709/ui 列表页以 vLoading 指令具名导入，插件不为指令补样式。
import 'element-plus/es/components/loading/style/css'
import { createPlatformClient, platformClientKey } from '@crane199709/sdk'
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
  .mount('#app')
