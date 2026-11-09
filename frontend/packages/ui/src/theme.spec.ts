import { afterEach, expect, it } from 'vitest'
import { applyTheme, layoutTokens } from './theme'

afterEach(() => document.documentElement.removeAttribute('style'))

it('写入版式 token', () => {
  applyTheme('#409eff')

  const style = document.documentElement.style
  for (const [name, value] of Object.entries(layoutTokens))
    expect(style.getPropertyValue(name)).toBe(value)
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
