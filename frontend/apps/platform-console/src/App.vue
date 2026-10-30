<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { AppLayout, ErrorAlert, type MenuItem } from '@forge/ui'
import { consolePages } from './router'
import { useConsoleAccess } from './shell'
import UserMenu from './UserMenu.vue'

const { t } = useI18n()
const route = useRoute()
const access = useConsoleAccess()

// 菜单按当前 Operator 的平台权限显示，Token 换发后权限变化时随之出现或消失。
const menu = computed<MenuItem[]>(() =>
  consolePages
    .filter((page) => access.has(page.permission))
    .map((page) => ({ key: page.path.slice(1), title: t(page.title), path: page.path })),
)
</script>

<template>
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
</template>

<style scoped>
.access-failed {
  margin-bottom: 16px;
}
</style>
