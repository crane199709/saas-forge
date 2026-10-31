import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createAccessControl, createTenantClient, tenantClientKey } from '@crane199709/sdk'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import { createI18n } from 'vue-i18n'
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import DevHost from './DevHost.vue'

/** 开发用宿主的选项。 */
export interface DevHostOptions {
  /** 应用编码；路由与 Shell 一样挂在 `/apps/<appCode>` 下。 */
  appCode: string
  /** Sub-App 导出的相对路由。 */
  routes: RouteRecordRaw[]
  /** 打开根路径时进入的 Sub-App 内路径，如 `overview`。 */
  home?: string
}

/**
 * 不启动 Shell App 工程时运行与调试 Sub-App 的宿主：按 Shell 的方式创建 Vue 应用、Router、Pinia、i18n 与 Element Plus，
 * 提供 Tenant Realm 客户端与权限上下文，并提供一个最小的登录栏，经开发服务器代理到本地网关取得 Token。
 *
 * 只用于 `vite` 开发模式；构建产物只暴露 `./sub-app`，由真正的 Shell 加载。Sub-App 自己不得创建这些实例（隔离约定）。
 */
export async function startDevHost(options: DevHostOptions) {
  const client = createTenantClient(localStorage)
  const access = createAccessControl(client)
  const base = `/apps/${options.appCode}`
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', redirect: `${base}/${options.home ?? ''}` },
      { path: base, children: options.routes },
    ],
  })
  async function refresh() {
    if (!client.session()) return access.clear()
    try {
      await access.load()
    } catch (error) {
      console.error(error)
      access.clear()
    }
  }
  await refresh()
  client.onSessionChange(() => void refresh())
  createApp(DevHost, { appCode: options.appCode })
    .provide(tenantClientKey, client)
    .use(access)
    .use(createPinia())
    .use(createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': {} } }))
    .use(router)
    .use(ElementPlus, { locale: zhCn })
    .mount('#app')
}
