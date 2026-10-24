import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// Element Plus 内置文案默认是英文，与第一期只支持简体中文保持一致
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import { i18n } from './i18n'

document.title = i18n.global.t('app.title')

createApp(App).use(createPinia()).use(i18n).use(ElementPlus, { locale: zhCn }).mount('#app')
