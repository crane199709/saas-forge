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
import type { Component } from 'vue'

/**
 * 菜单图标白名单，也是图标来源的唯一事实来源：键是归一化后的图标名，值是 Element Plus 图标。
 * 白名单外的名字不报错，一律回退到 `defaultMenuIcon`，避免一份清单写错图标名就让菜单消失。
 */
const whitelist: Readonly<Record<string, Component>> = {
  avatar: Avatar,
  bell: Bell,
  collection: Collection,
  document: Document,
  folder: Folder,
  grid: Grid,
  guide: Guide,
  home: HomeFilled,
  key: Key,
  lock: Lock,
  menu: Menu,
  'office-building': OfficeBuilding,
  'price-tag': PriceTag,
  search: Search,
  setting: Setting,
  tickets: Tickets,
  timer: Timer,
  user: User,
  warning: Warning,
}

/** 图标名缺失或不在白名单时使用的默认图标。 */
export const defaultMenuIcon: Component = Menu

/** 把 kebab-case、PascalCase、下划线或空格写法归一到白名单的键。 */
function canonical(name: string): string {
  return name
    .trim()
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/[\s_]+/g, '-')
    .toLowerCase()
}

/**
 * 解析 Resource Manifest 菜单里声明的图标名，返回对应的图标组件。
 *
 * @param name 图标名，kebab-case 与 PascalCase 等价，如 `office-building` 与 `OfficeBuilding`
 */
export function resolveMenuIcon(name?: string | null): Component {
  return (name && whitelist[canonical(name)]) || defaultMenuIcon
}
