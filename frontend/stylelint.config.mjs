const rawLength = '/\\d(px|rem|em)\\b/'
const rawColor = ['/#[0-9a-f]{3,8}\\b/i', '/\\b(rgb|hsl)a?\\(/']

export default {
  customSyntax: 'postcss-html',
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
