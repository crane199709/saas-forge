<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { consolePages } from './router'
import { useConsoleAccess } from './shell'
import UserMenu from './UserMenu.vue'

const { t } = useI18n()
const route = useRoute()
const access = useConsoleAccess()

// 菜单按当前 Operator 的平台权限显示，Token 换发后权限变化时随之出现或消失。
const pages = computed(() => consolePages.filter((page) => access.has(page.permission)))
// 页内的下级路径也归到所属菜单项。
const active = computed(
  () =>
    pages.value.find((page) => route.path === page.path || route.path.startsWith(`${page.path}/`))
      ?.path ?? '',
)
</script>

<template>
  <router-view v-if="route.meta.standalone" />
  <el-container v-else class="shell">
    <el-header class="shell-header">
      <router-link to="/" class="brand">{{ t('app.product') }}</router-link>
      <UserMenu />
    </el-header>
    <el-container>
      <el-aside width="220px" class="shell-aside">
        <el-menu :default-active="active" class="shell-menu">
          <el-menu-item v-for="page in pages" :key="page.path" :index="page.path">
            <router-link
              :to="page.path"
              class="menu-link"
              :data-testid="`menu-${page.path.slice(1)}`"
              >{{ t(page.title) }}</router-link
            >
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main>
        <el-alert
          v-if="access.failed.value"
          :title="t('access.failed')"
          type="error"
          :closable="false"
          role="alert"
          class="access-failed"
        />
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
.shell-menu {
  height: 100%;
}
.menu-link {
  display: block;
  width: 100%;
  color: inherit;
  text-decoration: none;
}
.access-failed {
  margin-bottom: 16px;
}
</style>
