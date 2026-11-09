<script setup lang="ts">
import { inject, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@crane199709/sdk'
import type { TenantContextResponse } from './demo-context'
import { ErrorAlert, PageHeader } from '@crane199709/ui'
import { errorTip, type ErrorTip } from './error-tip'

const { t } = useI18n()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
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
    <PageHeader :title="t('app.welcome')" />
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <div v-if="session" class="session">
      <p>{{ t('auth.identity') }}：{{ session.identityName }}</p>
      <p>{{ t('auth.tenant') }}：{{ session.tenantName }}</p>
    </div>
    <el-descriptions v-if="context" class="info" :column="1" border>
      <el-descriptions-item :label="t('auth.identityId')">{{
        context.identityId
      }}</el-descriptions-item>
      <el-descriptions-item :label="t('auth.tenantId')">{{
        context.tenantId
      }}</el-descriptions-item>
      <el-descriptions-item :label="t('auth.membershipId')">{{
        context.membershipId
      }}</el-descriptions-item>
    </el-descriptions>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.session {
  margin-bottom: var(--forge-space-4);
}
.session p {
  margin: 0 0 var(--forge-space-1);
}
.info {
  max-width: 640px;
  overflow-wrap: anywhere;
}
</style>
