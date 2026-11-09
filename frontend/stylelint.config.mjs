import { readFileSync } from 'node:fs'

const rawLength = '/\\d(px|rem|em)\\b/'
const rawColor = ['/#[0-9a-f]{3,8}\\b/i', '/\\b(rgb|hsl)a?\\(/']

// 尚未迁移到版式 token 的文件；只减不增，迁移一个就从清单删一个。
const legacy = JSON.parse(readFileSync(new URL('./stylelint.legacy.json', import.meta.url), 'utf8'))

export default {
  customSyntax: 'postcss-html',
  ignoreFiles: legacy,
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
