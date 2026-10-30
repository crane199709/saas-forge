/** Shell 与 Sub-App 共用的主题 token；CSS 变量由 Shell 写入文档根节点。 */
export const themeTokens = Object.freeze({ primary: '#409eff', alternate: '#8b5cf6' })

/**
 * 把主色写入文档根节点的 `--forge-color-primary` 与 Element Plus 主色变量，Sub-App 读取同一组变量。
 * 只由 Shell 调用，Sub-App 不得修改文档根节点样式。
 *
 * @param color CSS 颜色值，如 `#409eff`
 */
export function applyTheme(color: string) {
  const style = document.documentElement.style
  style.setProperty('--forge-color-primary', color)
  style.setProperty('--el-color-primary', color)
  // Element Plus 的悬停、禁用和边框使用派生 token，必须与主色一起切换。
  for (const level of [3, 5, 7, 8, 9]) {
    style.setProperty(
      `--el-color-primary-light-${level}`,
      `color-mix(in srgb, ${color}, white ${level * 10}%)`,
    )
  }
  style.setProperty('--el-color-primary-dark-2', `color-mix(in srgb, ${color}, black 20%)`)
}
