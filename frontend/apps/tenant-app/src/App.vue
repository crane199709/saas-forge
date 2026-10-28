<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { computed, inject, onUnmounted, ref, watchEffect } from 'vue'
import { tenantClientKey, useAccessControl } from '@forge/sdk'
import { themeTokens } from '@forge/ui'
import LoginPanel from './LoginPanel.vue'
import TenantSwitcher from './TenantSwitcher.vue'
import { menuSections } from './menu-routes'

const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const access = useAccessControl()
const sections = computed(() => menuSections(access.menus()))

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const signedIn = ref(client.session() !== null)
// 会话失效后离开 Sub-App 路由，不再展示属于已失效会话的页面。
onUnmounted(
  client.onSessionChange((value) => {
    signedIn.value = value !== null
    if (!value) void router.replace('/')
  }),
)
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
          <!-- 租户管理页按 iam 的管理权限码显示，授权被撤销后随权限刷新隐藏。 -->
          <router-link
            v-if="signedIn"
            v-perm="'iam:member:manage'"
            to="/members"
            data-testid="menu-members"
            >{{ t('member.nav') }}</router-link
          >
          <router-link v-if="signedIn" v-perm="'iam:org:manage'" to="/org" data-testid="menu-org">{{
            t('org.nav')
          }}</router-link>
          <router-link
            v-if="signedIn"
            v-perm="'iam:role:manage'"
            to="/roles"
            data-testid="menu-roles"
            >{{ t('role.nav') }}</router-link
          >
          <template v-for="section in sections" :key="section.code">
            <span class="menu-section">{{ section.name }}</span>
            <router-link
              v-for="item in section.links"
              :key="item.code"
              :to="item.to"
              :data-testid="`menu-${item.code}`"
              >{{ item.name }}</router-link
            >
          </template>
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
.menu-section {
  color: var(--el-text-color-secondary);
}
.scope-probe {
  border: 0;
}
</style>
