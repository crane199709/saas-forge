<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import {
  AppLayout,
  ErrorAlert,
  applyTheme,
  arrangeNavigation,
  themeTokens,
  type MenuItem,
} from '@crane199709/ui'
// Element Plus 内置文案默认是英文，与第一期只支持简体中文保持一致；按需引入时语言包经 ConfigProvider 下发。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { consolePages } from './router'
import { useConsoleAccess } from './shell'
import UserMenu from './UserMenu.vue'

const { t } = useI18n()
const route = useRoute()
const access = useConsoleAccess()

// 菜单按当前 Operator 的平台权限显示，Token 换发后权限变化时随之出现或消失；位置按平台导航布局排列。
const menu = computed<MenuItem[]>(() =>
  arrangeNavigation(
    consolePages
      .filter((page) => access.has(page.permission))
      .map((page) => ({ page: page.path.slice(1), title: t(page.title), path: page.path })),
    access.state.value.layout,
  ),
)

applyTheme(themeTokens.primary)
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view v-if="route.meta.standalone" />
    <AppLayout v-else :title="t('app.product')" :menu="menu" :active-path="route.path">
      <template #actions>
        <UserMenu />
      </template>
      <ErrorAlert
        v-if="access.failed.value"
        :tip="{ message: t('access.failed') }"
        class="access-failed"
      />
      <router-view />
    </AppLayout>
  </el-config-provider>
</template>

<style scoped>
.access-failed {
  margin-bottom: var(--forge-space-3);
}
</style>
