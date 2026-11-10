import postcssHtml from 'postcss-html'
import type { Config } from 'stylelint'

const rawLength = '/\\d(px|rem|em)\\b/'
const rawColor = ['/#[0-9a-f]{3,8}\\b/i', '/\\b(rgb|hsl)a?\\(/']

/**
 * Sub-App 页面的样式规则：间距、字号、圆角只用 `--forge-*` token，颜色只用 token 或 Element Plus 变量，不写字面量。
 * 同时校验 `.vue` 与 `.css`；语法对象直接传入，消费工程不依赖 stylelint 按名称解析 `postcss-html`。
 */
const config: Config = {
  customSyntax: postcssHtml,
  rules: {
    'declaration-property-value-disallowed-list': {
      '/^(margin|padding|gap|row-gap|column-gap)(-|$)/': [rawLength],
      'font-size': [rawLength],
      'border-radius': [rawLength],
      '/^(color|background(-color)?|border(-[a-z]+)*|outline(-color)?|fill|stroke|box-shadow)$/':
        rawColor,
    },
  },
}

export default config
