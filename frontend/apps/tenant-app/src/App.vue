<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { computed, inject } from 'vue'
import { useAccessControl } from '@forge/sdk'
import { themeTokens } from '@forge/ui'
import TenantSwitcher from './TenantSwitcher.vue'
import UserMenu from './UserMenu.vue'
import { menuSections } from './menu-routes'
import { applyPrimaryColor, subAppHostKey } from './shell'

const access = useAccessControl()
const host = inject(subAppHostKey, undefined)
const { t } = useI18n()
const route = useRoute()

// Shell 自带的租户管理页按 iam 的管理权限码显示，授权被撤销后随权限刷新隐藏。
const pages = computed(() =>
  [
    { code: 'members', to: '/members', name: t('member.nav'), permission: 'iam:member:manage' },
    { code: 'org', to: '/org', name: t('org.nav'), permission: 'iam:org:manage' },
    { code: 'roles', to: '/roles', name: t('role.nav'), permission: 'iam:role:manage' },
    { code: 'audit', to: '/audit', name: t('audit.nav'), permission: 'iam:audit:read' },
  ].filter((page) => access.has(page.permission)),
)
// 导航只列出已注册的 Sub-App：菜单可见、套餐允许且在当前环境登记了入口地址
const sections = computed(() => menuSections(access.menus(), host?.codes() ?? new Set()))
// 高亮与当前路径最长匹配的菜单项，管理页内的下级路径也归到该项。
const active = computed(() => {
  const paths = ['/', ...pages.value.map((page) => page.to)].concat(
    sections.value.flatMap((section) => section.links.map((link) => link.to)),
  )
  return paths
    .filter((path) => route.path === path || route.path.startsWith(`${path}/`))
    .reduce((best, path) => (path.length > best.length ? path : best), '')
})

applyPrimaryColor(themeTokens.primary)
</script>

<template>
  <router-view v-if="route.meta.standalone" />
  <el-container v-else class="shell">
    <el-header class="shell-header">
      <router-link to="/" class="brand">{{ t('app.product') }}</router-link>
      <div class="shell-actions">
        <TenantSwitcher />
        <UserMenu />
      </div>
    </el-header>
    <el-container>
      <el-aside width="220px" class="shell-aside">
        <el-menu :default-active="active" class="shell-menu">
          <el-menu-item index="/">
            <router-link to="/" class="menu-link" data-testid="menu-home">{{
              t('app.home')
            }}</router-link>
          </el-menu-item>
          <el-menu-item v-for="page in pages" :key="page.code" :index="page.to">
            <router-link :to="page.to" class="menu-link" :data-testid="`menu-${page.code}`">{{
              page.name
            }}</router-link>
          </el-menu-item>
          <el-menu-item-group v-for="section in sections" :key="section.code" :title="section.name">
            <el-menu-item v-for="item in section.links" :key="item.code" :index="item.to">
              <router-link :to="item.to" class="menu-link" :data-testid="`menu-${item.code}`">{{
                item.name
              }}</router-link>
            </el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </el-aside>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.shell {
  min-height: 100vh;
}
.shell-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color-light);
}
.brand {
  color: var(--el-text-color-primary);
  font-size: 18px;
  font-weight: 600;
  text-decoration: none;
}
.shell-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}
.shell-menu {
  height: 100%;
}
.menu-link {
  display: block;
  width: 100%;
  color: inherit;
  text-decoration: none;
}
</style>
