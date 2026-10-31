<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { computed, inject } from 'vue'
import { useAccessControl } from '@crane199709/sdk'
import { AppLayout, applyTheme, themeTokens, type MenuItem } from '@crane199709/ui'
import TenantSwitcher from './TenantSwitcher.vue'
import UserMenu from './UserMenu.vue'
import { menuSections } from './menu-routes'
import { subAppHostKey } from './shell'

const access = useAccessControl()
const host = inject(subAppHostKey, undefined)
const { t } = useI18n()
const route = useRoute()

const menu = computed<MenuItem[]>(() => [
  { key: 'home', title: t('app.home'), path: '/' },
  // Shell 自带的租户管理页按 iam 的管理权限码显示，授权被撤销后随权限刷新隐藏。
  ...[
    { key: 'members', path: '/members', title: t('member.nav'), permission: 'iam:member:manage' },
    { key: 'org', path: '/org', title: t('org.nav'), permission: 'iam:org:manage' },
    { key: 'roles', path: '/roles', title: t('role.nav'), permission: 'iam:role:manage' },
    { key: 'audit', path: '/audit', title: t('audit.nav'), permission: 'iam:audit:read' },
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
  <AppLayout v-else :title="t('app.product')" :menu="menu" :active-path="route.path">
    <template #actions>
      <TenantSwitcher />
      <UserMenu />
    </template>
    <router-view />
  </AppLayout>
</template>
