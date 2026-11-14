import { afterEach, expect, it } from 'vitest'
import { applyTheme, layoutTokens } from './theme'

afterEach(() => document.documentElement.removeAttribute('style'))

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

it('现有 token 的名称与取值保持不变', () => {
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
  expect(style.getPropertyValue('--forge-color-page')).toBe('#f7fafc')
  expect(style.getPropertyValue('--forge-shadow-card')).toBe('0 1px 2px rgb(0 0 0 / 6%)')
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
