<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { computed, inject, provide } from 'vue'
import { useTenantClient, useAccessControl } from '@crane199709/sdk'
import {
  AppLayout,
  applyTheme,
  arrangeNavigation,
  themeTokens,
  type MenuItem,
} from '@crane199709/ui'
import TenantSwitcher from './TenantSwitcher.vue'
import UserMenu from './UserMenu.vue'
import { menuEntries } from './menu-routes'
import { defaultNavigation, shellPages } from './navigation/shell-pages'
import { subAppHostKey } from './shell'

import NotificationBell from './notification/NotificationBell.vue'
import { unreadCountKey, useUnreadCount } from './notification/unread-count'

provide(unreadCountKey, useUnreadCount(useTenantClient()))
const access = useAccessControl()
const host = inject(subAppHostKey, undefined)
const { t } = useI18n()
const route = useRoute()

const menu = computed<MenuItem[]>(() => [
  { key: 'home', title: t('app.workbench'), icon: 'home', path: '/' },
  ...(access.has(['todo:read', 'notification:read', 'announcement:read'])
    ? [
        {
          key: 'notifications',
          title: '通知中心',
          icon: 'bell',
          path: '/notifications',
        } satisfies MenuItem,
      ]
    : []),
  // 工作台与通知中心固定在顶部，其余按租户的导航布局排列。
  ...arrangeNavigation(
    defaultNavigation(
      // Shell 自带的租户管理页按 iam 的管理权限码显示，授权被撤销后随权限刷新隐藏。
      shellPages(t).filter((page) => access.has(page.permission)),
      // 导航只列出已注册的 Sub-App：菜单可见、套餐允许且在当前环境登记了入口地址
      menuEntries(access.menus(), host?.codes() ?? new Set()),
    ),
    access.layout(),
  ),
])

applyTheme(themeTokens.primary)
</script>

<template>
  <router-view v-if="route.meta.standalone" />
  <AppLayout
    v-else
    :class="{
      'security-layout': route.path === '/security-settings',
      'workbench-layout': route.path === '/',
    }"
    :title="t('app.product')"
    :menu="menu"
    :active-path="route.path"
  >
    <template #actions>
      <NotificationBell />
      <TenantSwitcher />
      <UserMenu />
    </template>
    <router-view />
  </AppLayout>
</template>

<style scoped>
/* 铃铛加入顶栏后，窄屏仍须完整显示切换租户和个人菜单。 */
@media (max-width: 640px) {
  .security-layout :deep(.shell-aside),
  .workbench-layout :deep(.shell-aside) {
    width: 120px !important;
  }
  .security-layout :deep(.el-menu-item),
  .workbench-layout :deep(.el-menu-item) {
    padding: 0 var(--forge-space-3);
  }
  /* AppLayout 用内联样式设置内容区内边距 */
  .security-layout :deep(.shell-main),
  .workbench-layout :deep(.shell-main) {
    padding: var(--forge-space-3) !important;
  }

  :deep(.shell-header) {
    height: auto;
    min-height: 56px;
    flex-wrap: wrap;
    gap: var(--forge-space-3);
    padding: var(--forge-space-3) var(--forge-space-4);
  }
  :deep(.brand) {
    white-space: nowrap;
  }
  :deep(.shell-actions) {
    flex-wrap: wrap;
    gap: var(--forge-space-2) !important;
    max-width: 100%;
  }
  :deep(.tenant-switcher .el-select) {
    width: 150px;
  }
}
</style>

<style>
/*
 * 表格密度由 Shell 统一设置，Sub-App 页面不再逐表调整：40px 行高 = 单元格上下内边距
 * --forge-space-2（8px × 2）+ 正文行高 23px + 1px 分隔线；正文字号保持 14px。
 * 展开行（el-table__expanded-cell）自带大内边距，不参与密度覆盖。
 *
 * 括号里的 14px / 8px 是运行时 token 的构建期兜底，值与 theme.ts 的 --forge-font-size-sm /
 * --forge-space-2 完全一致，不改变像素，不要当成冗余删掉：这两个 token 由 applyTheme() 在运行时
 * 写入文档根节点，构建产物里并不存在；el-table 首屏按文字宽度测量并写死列宽，若测量早于 token
 * 注入，列宽会按继承的 16px 字号算出、文字却按 14px 渲染，整列错位，加载瞬间列宽会跳一下。
 */
.el-table {
  --forge-table-row-height: 40px;
  /* stylelint-disable-next-line declaration-property-value-disallowed-list -- 兜底值是 token 的默认值，不是新档位 */
  font-size: var(--forge-font-size-sm, 14px);
}
.el-table .el-table__cell:not(.el-table__expanded-cell) {
  height: var(--forge-table-row-height);
  /* stylelint-disable-next-line declaration-property-value-disallowed-list -- 同上 */
  padding: var(--forge-space-2, 8px) 0;
}
</style>
