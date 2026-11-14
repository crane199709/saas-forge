import {
  Avatar,
  Bell,
  Collection,
  Document,
  Folder,
  Grid,
  Guide,
  HomeFilled,
  Key,
  Lock,
  Menu,
  OfficeBuilding,
  PriceTag,
  Search,
  Setting,
  Tickets,
  Timer,
  User,
  Warning,
} from '@element-plus/icons-vue'
import { describe, expect, it } from 'vitest'
import { defaultMenuIcon, resolveMenuIcon } from './icons'

describe('resolveMenuIcon', () => {
  // 名字与图标的对应关系是 `sub-app.md` 对外承诺的白名单契约，逐条钉住：
  // 只断言「两次调用结果相等」的话，把 bell 接错成别的图标也会全绿。
  it.each([
    ['avatar', Avatar],
    ['bell', Bell],
    ['collection', Collection],
    ['document', Document],
    ['folder', Folder],
    ['grid', Grid],
    ['guide', Guide],
    ['home', HomeFilled],
    ['key', Key],
    ['lock', Lock],
    ['menu', Menu],
    ['office-building', OfficeBuilding],
    ['price-tag', PriceTag],
    ['search', Search],
    ['setting', Setting],
    ['tickets', Tickets],
    ['timer', Timer],
    ['user', User],
    ['warning', Warning],
  ])('白名单名字 %s 解析为对应的 Element Plus 图标', (name, expected) => {
    expect(resolveMenuIcon(name)).toBe(expected)
  })

  it('把 kebab-case 与 PascalCase 归一到同一个图标', () => {
    expect(resolveMenuIcon('office-building')).toBe(OfficeBuilding)
    expect(resolveMenuIcon('OfficeBuilding')).toBe(OfficeBuilding)
    expect(resolveMenuIcon('price-tag')).toBe(PriceTag)
    expect(resolveMenuIcon('PriceTag')).toBe(PriceTag)
  })

  it('未命中白名单时回退到菜单图标', () => {
    expect(defaultMenuIcon).toBe(Menu)
    expect(resolveMenuIcon('not-in-whitelist')).toBe(Menu)
    expect(resolveMenuIcon(undefined)).toBe(Menu)
    expect(resolveMenuIcon(null)).toBe(Menu)
    expect(resolveMenuIcon('  ')).toBe(Menu)
  })
})
