/** Shell 与 Sub-App 共用的主题 token；CSS 变量由 Shell 写入文档根节点。 */
export const themeTokens = Object.freeze({ primary: '#409eff', alternate: '#8b5cf6' })

/** 版式 token：页面样式只读这组变量，新增档位前先确认现有档位确实不够用。 */
export const layoutTokens = Object.freeze({
  '--forge-space-1': '4px',
  '--forge-space-2': '8px',
  '--forge-space-3': '12px',
  '--forge-space-4': '16px',
  '--forge-space-5': '24px',
  '--forge-radius-sm': '4px',
  '--forge-radius-md': '6px',
  '--forge-radius-lg': '8px',
  '--forge-font-size-xs': '12px',
  '--forge-font-size-sm': '14px',
  '--forge-font-size-md': '16px',
  '--forge-font-size-lg': '20px',
  '--forge-color-page': '#f7fafc',
  '--forge-shadow-card': '0 1px 2px rgb(0 0 0 / 6%)',
})

/** 弹窗与抽屉的尺寸档位，窄屏时收缩到视口内。 */
export const overlaySize = Object.freeze({
  dialogSm: 'min(480px, calc(100vw - 32px))',
  dialogMd: 'min(640px, calc(100vw - 32px))',
  drawerMd: 'min(480px, 100vw)',
  drawerLg: 'min(760px, 100vw)',
})

/**
 * 把版式 token、主色写入文档根节点：`--forge-*` 与 Element Plus 主色变量，Sub-App 读取同一组变量。
 * 只由 Shell 调用，Sub-App 不得修改文档根节点样式。
 *
 * @param color CSS 颜色值，如 `#409eff`
 */
export function applyTheme(color: string) {
  const style = document.documentElement.style
  for (const [name, value] of Object.entries(layoutTokens)) style.setProperty(name, value)
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
