import { describe, expect, it } from 'vitest'
import { defaultMenuIcon, resolveMenuIcon } from './icons'

describe('resolveMenuIcon', () => {
  it('把 kebab-case 与 PascalCase 归一到同一个图标', () => {
    expect(resolveMenuIcon('office-building')).toBe(resolveMenuIcon('OfficeBuilding'))
    expect(resolveMenuIcon('price-tag')).toBe(resolveMenuIcon('PriceTag'))
  })

  it('未命中白名单时回退默认图标', () => {
    expect(resolveMenuIcon('not-in-whitelist')).toBe(defaultMenuIcon)
    expect(resolveMenuIcon(undefined)).toBe(defaultMenuIcon)
    expect(resolveMenuIcon(null)).toBe(defaultMenuIcon)
    expect(resolveMenuIcon('  ')).toBe(defaultMenuIcon)
  })
})
