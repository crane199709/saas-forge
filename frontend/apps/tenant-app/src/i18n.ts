import { createI18n } from 'vue-i18n'
import zhCN from './locales/zh-CN.json'

// 第一期只有简体中文，但文案一律经 key 渲染，之后增加语言只需补语言包
export const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: { 'zh-CN': zhCN },
})
