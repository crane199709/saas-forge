<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { computed, inject, provide } from 'vue'
import { useTenantClient, useAccessControl } from '@crane199709/sdk'
import { AppLayout, applyTheme, themeTokens, type MenuItem } from '@crane199709/ui'
import TenantSwitcher from './TenantSwitcher.vue'
import UserMenu from './UserMenu.vue'
import { menuSections } from './menu-routes'
import { subAppHostKey } from './shell'

import NotificationBell from './notification/NotificationBell.vue'
import { unreadCountKey, useUnreadCount } from './notification/unread-count'

provide(unreadCountKey, useUnreadCount(useTenantClient()))
const access = useAccessControl()
const host = inject(subAppHostKey, undefined)
const { t } = useI18n()
const route = useRoute()

const menu = computed<MenuItem[]>(() => [
  { key: 'home', title: t('app.home'), path: '/' },
  { key: 'notifications', title: '通知中心', path: '/notifications' },
  // Shell 自带的租户管理页按 iam 的管理权限码显示，授权被撤销后随权限刷新隐藏。
  ...[
    {
      key: 'announcements',
      path: '/announcements',
      title: '公告管理',
      permission: 'announcement:manage',
    },
    { key: 'members', path: '/members', title: t('member.nav'), permission: 'iam:member:manage' },
    { key: 'org', path: '/org', title: t('org.nav'), permission: 'iam:org:manage' },
    { key: 'roles', path: '/roles', title: t('role.nav'), permission: 'iam:role:manage' },
    { key: 'audit', path: '/audit', title: t('audit.nav'), permission: 'iam:audit:read' },
    {
      key: 'dictionaries',
      path: '/dictionaries',
      title: t('referenceData.dictionary.nav'),
      permission: 'iam:reference-data:manage',
    },
    {
      key: 'parameters',
      path: '/parameters',
      title: t('referenceData.parameter.nav'),
      permission: 'iam:reference-data:manage',
    },
    {
      key: 'security-settings',
      path: '/security-settings',
      title: t('securitySettings.nav'),
      permission: 'iam:security:manage',
    },
  ].filter((page) => access.has(page.permission)),
  // 导航只列出已注册的 Sub-App：菜单可见、套餐允许且在当前环境登记了入口地址
  ...menuSections(access.menus(), host?.codes() ?? new Set()).map((section) => ({
    key: section.code,
    title: section.name,
    children: section.links.map((link) => ({ key: link.code, title: link.name, path: link.to })),
  })),
])

applyTheme(themeTokens.primary)
</script>

<template>
  <router-view v-if="route.meta.standalone" />
  <AppLayout
    v-else
    :class="{ 'security-layout': route.path === '/security-settings' }"
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
  .security-layout :deep(.shell-aside) {
    width: 120px !important;
  }
  .security-layout :deep(.el-menu-item) {
    padding: 0 12px;
  }
  .security-layout :deep(.el-main) {
    padding: 12px;
  }

  :deep(.shell-header) {
    height: auto;
    min-height: 60px;
    flex-wrap: wrap;
    gap: 12px;
    padding: 12px 20px;
  }
  :deep(.brand) {
    white-space: nowrap;
  }
  :deep(.shell-actions) {
    flex-wrap: wrap;
    gap: 8px !important;
    max-width: 100%;
  }
  :deep(.tenant-switcher .el-select) {
    width: 150px;
  }
}
</style>
