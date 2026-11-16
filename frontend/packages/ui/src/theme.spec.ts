import { afterEach, expect, it } from 'vitest'
import { applyTheme, layoutTokens, themeTokens } from './theme'

afterEach(() => document.documentElement.removeAttribute('style'))

it('默认主题入口统一写入靛蓝与 Slate 浅色语义变量', () => {
  applyTheme()

  const style = document.documentElement.style
  expect(style.getPropertyValue('--forge-color-primary')).toBe('#4F46E5')
  expect(style.getPropertyValue('--el-color-primary')).toBe('#4F46E5')
  expect(style.getPropertyValue('--forge-color-page')).toBe('#F8FAFC')
  expect(style.getPropertyValue('--el-bg-color-page')).toBe('#F8FAFC')
  expect(style.getPropertyValue('--el-bg-color')).toBe('#FFFFFF')
  expect(style.getPropertyValue('--el-bg-color-overlay')).toBe('#FFFFFF')
  expect(style.getPropertyValue('--el-text-color-primary')).toBe('#0F172A')
  expect(style.getPropertyValue('--el-text-color-regular')).toBe('#334155')
  expect(style.getPropertyValue('--el-text-color-secondary')).toBe('#64748B')
  expect(style.getPropertyValue('--el-border-color')).toBe('#CBD5E1')
  expect(style.getPropertyValue('--el-border-color-light')).toBe('#E2E8F0')
  expect(style.getPropertyValue('--el-fill-color-light')).toBe('#F1F5F9')
  expect(style.getPropertyValue('--el-text-color-placeholder')).toBe('#94A3B8')
  expect(style.getPropertyValue('--el-text-color-disabled')).toBe('#CBD5E1')
  expect(style.getPropertyValue('--el-border-color-lighter')).toBe('#E2E8F0')
  expect(style.getPropertyValue('--el-border-color-extra-light')).toBe('#F8FAFC')
  expect(style.getPropertyValue('--el-border-color-dark')).toBe('#94A3B8')
  expect(style.getPropertyValue('--el-border-color-darker')).toBe('#64748B')
  expect(style.getPropertyValue('--el-fill-color')).toBe('#E2E8F0')
  expect(style.getPropertyValue('--el-fill-color-lighter')).toBe('#F8FAFC')
  expect(style.getPropertyValue('--el-fill-color-extra-light')).toBe('#F8FAFC')
  expect(style.getPropertyValue('--el-fill-color-dark')).toBe('#CBD5E1')
  expect(style.getPropertyValue('--el-fill-color-darker')).toBe('#94A3B8')
  expect(style.getPropertyValue('--el-fill-color-blank')).toBe('#FFFFFF')
  expect(style.getPropertyValue('--el-disabled-bg-color')).toBe('#F1F5F9')
  expect(style.getPropertyValue('--el-disabled-text-color')).toBe('#94A3B8')
  expect(style.getPropertyValue('--el-disabled-border-color')).toBe('#E2E8F0')
  expect(style.getPropertyValue('--el-color-primary-light-3')).toBe(
    'color-mix(in srgb, #4F46E5, white 30%)',
  )
  expect(style.getPropertyValue('--el-color-primary-dark-2')).toBe(
    'color-mix(in srgb, #4F46E5, black 20%)',
  )
})

it('写入版式 token', () => {
  applyTheme('#409eff')

  const style = document.documentElement.style
  for (const [name, value] of Object.entries(layoutTokens))
    expect(style.getPropertyValue(name)).toBe(value)
})

it('写入新增的档位 token', () => {
  applyTheme('#409eff')

  const style = document.documentElement.style
  expect(style.getPropertyValue('--forge-space-6')).toBe('32px')
  expect(style.getPropertyValue('--forge-space-7')).toBe('48px')
  expect(style.getPropertyValue('--forge-shadow-float')).toBe('0 6px 16px rgb(0 0 0 / 12%)')
  expect(style.getPropertyValue('--forge-font-family')).toBe(
    '"Helvetica Neue", Helvetica, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "微软雅黑", Arial, sans-serif',
  )
  expect(style.getPropertyValue('--forge-z-index-dropdown')).toBe('1000')
  expect(style.getPropertyValue('--forge-z-index-sticky')).toBe('1100')
  expect(style.getPropertyValue('--forge-z-index-overlay')).toBe('1200')
  expect(style.getPropertyValue('--forge-z-index-dialog')).toBe('1300')
  expect(style.getPropertyValue('--forge-z-index-toast')).toBe('1400')
})

it('现有 token 的名称与版式取值保持不变', () => {
  applyTheme('#409eff')

  const style = document.documentElement.style
  expect(style.getPropertyValue('--forge-color-primary')).toBe('#409eff')
  expect(style.getPropertyValue('--forge-space-1')).toBe('4px')
  expect(style.getPropertyValue('--forge-space-2')).toBe('8px')
  expect(style.getPropertyValue('--forge-space-3')).toBe('12px')
  expect(style.getPropertyValue('--forge-space-4')).toBe('16px')
  expect(style.getPropertyValue('--forge-space-5')).toBe('24px')
  expect(style.getPropertyValue('--forge-radius-sm')).toBe('4px')
  expect(style.getPropertyValue('--forge-radius-md')).toBe('6px')
  expect(style.getPropertyValue('--forge-radius-lg')).toBe('8px')
  expect(style.getPropertyValue('--forge-font-size-xs')).toBe('12px')
  expect(style.getPropertyValue('--forge-font-size-sm')).toBe('14px')
  expect(style.getPropertyValue('--forge-font-size-md')).toBe('16px')
  expect(style.getPropertyValue('--forge-font-size-lg')).toBe('20px')
  expect(style.getPropertyValue('--forge-color-page')).toBe('#F8FAFC')
  expect(style.getPropertyValue('--forge-shadow-card')).toBe('0 1px 2px rgb(0 0 0 / 6%)')
})

it('组件层 token 只写在组件自身元素上，不写到文档根节点', () => {
  applyTheme('#409eff')

  // 层归属见 ADR-0029：组件私有取值若进了根节点，Sub-App 会跟着拿到它不该依赖的变量。
  expect(document.documentElement.style.getPropertyValue('--forge-empty-icon-size')).toBe('')
  expect(document.documentElement.style.getPropertyValue('--forge-empty-icon-glyph')).toBe('')
})

it('写入主色及 Element Plus 的派生色', () => {
  applyTheme('#8b5cf6')

  const style = document.documentElement.style
  expect(style.getPropertyValue('--forge-color-primary')).toBe('#8b5cf6')
  expect(style.getPropertyValue('--el-color-primary')).toBe('#8b5cf6')
  for (const level of [3, 5, 7, 8, 9]) {
    expect(style.getPropertyValue(`--el-color-primary-light-${level}`)).toBe(
      `color-mix(in srgb, #8b5cf6, white ${level * 10}%)`,
    )
  }
  expect(style.getPropertyValue('--el-color-primary-dark-2')).toBe(
    'color-mix(in srgb, #8b5cf6, black 20%)',
  )
})

it('诊断切色仅改变主色链，Slate 中性色与反馈语义色保持不变', () => {
  const style = document.documentElement.style
  style.setProperty('--el-color-success', '#67c23a')
  style.setProperty('--el-color-warning', '#e6a23c')
  style.setProperty('--el-color-danger', '#f56c6c')
  applyTheme()
  const neutralNames = Array.from(style).filter((name) =>
    /^--el-(text-color|border-color|fill-color|bg-color|disabled)/.test(name),
  )
  const before = neutralNames.map((name) => [name, style.getPropertyValue(name)])

  applyTheme(themeTokens.alternate)

  expect(style.getPropertyValue('--el-color-primary')).toBe('#8b5cf6')
  expect(neutralNames.map((name) => [name, style.getPropertyValue(name)])).toEqual(before)
  expect(style.getPropertyValue('--forge-color-page')).toBe('#F8FAFC')
  expect(style.getPropertyValue('--el-color-success')).toBe('#67c23a')
  expect(style.getPropertyValue('--el-color-warning')).toBe('#e6a23c')
  expect(style.getPropertyValue('--el-color-danger')).toBe('#f56c6c')
})
