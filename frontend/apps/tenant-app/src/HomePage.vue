<script setup lang="ts">
import { inject, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { tenantClientKey } from '@forge/sdk'
import type { TenantContextResponse } from './demo-context'
import ErrorAlert from './ErrorAlert.vue'
import { errorTip, type ErrorTip } from './error-tip'
import { alternateTheme, subAppHostKey } from './shell'

const { t } = useI18n()
const route = useRoute()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const host = inject(subAppHostKey, undefined)
const session = ref(client.session())
// 下游服务按 Token 解析出的租户上下文，用于确认请求带的是当前租户的 Token。
const context = ref<TenantContextResponse | null>(null)
const error = ref<ErrorTip | null>(null)

onUnmounted(
  client.onSessionChange((value) => {
    session.value = value
  }),
)
onMounted(async () => {
  if (!session.value) return
  try {
    context.value = await client.api<TenantContextResponse>('/demo/context')
  } catch (cause) {
    if (!(cause instanceof Error && cause.message === 'auth.sessionChanged')) {
      error.value = errorTip(cause, t)
    }
  }
})
</script>

<template>
  <section>
    <h1>{{ t('app.welcome') }}</h1>
    <ErrorAlert v-if="error" :tip="error" />
    <template v-if="session">
      <p>{{ t('auth.identity') }}：{{ session.identityName }}</p>
      <p>{{ t('auth.tenant') }}：{{ session.tenantName }}</p>
    </template>
    <dl v-if="context">
      <dt>{{ t('auth.identityId') }}</dt>
      <dd>{{ context.identityId }}</dd>
      <dt>{{ t('auth.tenantId') }}</dt>
      <dd>{{ context.tenantId }}</dd>
      <dt>{{ t('auth.membershipId') }}</dt>
      <dd>{{ context.membershipId }}</dd>
    </dl>
    <!-- 工单 02 的技术验证诊断，暂放首页，frontend-shell 工单 03 移到 /__diagnostics -->
    <p data-testid="shell-route">{{ route.fullPath }}</p>
    <el-button type="primary" data-testid="shell-theme" @click="alternateTheme = !alternateTheme">{{
      t('spike.theme')
    }}</el-button>
    <p class="scope-probe" data-testid="shell-scope">{{ t('spike.scope') }}</p>
    <!-- Sub-App 导出 sharedReferences 时展示共享依赖是否为同一运行时实例 -->
    <ul v-for="(checks, app) in host?.checks" :key="app">
      <li v-for="(same, name) in checks" :key="name" :data-testid="`shared-${app}-${name}`">
        {{ t(same ? 'spike.singleton' : 'spike.duplicate', { name }) }}
      </li>
    </ul>
  </section>
</template>

<style scoped>
dd {
  margin: 4px 0 14px;
  overflow-wrap: anywhere;
}
.scope-probe {
  border: 0;
}
</style>
