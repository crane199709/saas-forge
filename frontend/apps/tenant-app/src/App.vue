<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { provide, ref, watchEffect } from 'vue'
import { createTenantClient, tenantClientKey } from '@forge/sdk'
import { themeTokens } from '@forge/ui'
import LoginPanel from './LoginPanel.vue'
import TenantSwitcher from './TenantSwitcher.vue'

provide(tenantClientKey, createTenantClient(sessionStorage))

const { t } = useI18n()
const route = useRoute()
const alternateTheme = ref(false)
watchEffect(() => {
  const color = alternateTheme.value ? themeTokens.alternate : themeTokens.primary
  document.documentElement.style.setProperty('--forge-color-primary', color)
  document.documentElement.style.setProperty('--el-color-primary', color)
  // Element Plus 的悬停、禁用和边框使用派生 token，必须与主色一起切换。
  for (const level of [3, 5, 7, 8, 9]) {
    document.documentElement.style.setProperty(
      `--el-color-primary-light-${level}`,
      `color-mix(in srgb, ${color}, white ${level * 10}%)`,
    )
  }
  document.documentElement.style.setProperty(
    '--el-color-primary-dark-2',
    `color-mix(in srgb, ${color}, black 20%)`,
  )
})
defineProps<{ sharedChecks?: Record<string, boolean>; loadError?: string }>()
</script>

<template>
  <el-container>
    <el-header class="shell-header">
      <TenantSwitcher />
    </el-header>
    <el-main>
      <el-card>
        <h1>{{ t('app.welcome') }}</h1>
        <LoginPanel />
        <nav>
          <router-link to="/">{{ t('spike.home') }}</router-link>
          <router-link to="/apps/demo/overview">{{ t('spike.demo') }}</router-link>
        </nav>
        <p data-testid="shell-route">{{ route.fullPath }}</p>
        <el-button
          type="primary"
          data-testid="shell-theme"
          @click="alternateTheme = !alternateTheme"
          >{{ t('spike.theme') }}</el-button
        >
        <p class="scope-probe" data-testid="shell-scope">{{ t('spike.scope') }}</p>
        <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
        <ul>
          <li v-for="(same, name) in sharedChecks" :key="name" :data-testid="`shared-${name}`">
            {{ t(same ? 'spike.singleton' : 'spike.duplicate', { name }) }}
          </li>
        </ul>
        <router-view />
      </el-card>
    </el-main>
  </el-container>
</template>

<style scoped>
.shell-header {
  display: flex;
  align-items: center;
  justify-content: flex-end;
}
nav {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
}
.scope-probe {
  border: 0;
}
</style>
