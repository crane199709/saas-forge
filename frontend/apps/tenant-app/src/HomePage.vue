<script setup lang="ts">
import { inject, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@crane199709/sdk'
import type { TenantContextResponse } from './demo-context'
import { ErrorAlert } from '@crane199709/ui'
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
  </section>
</template>

<style scoped>
dd {
  margin: 4px 0 14px;
  overflow-wrap: anywhere;
}
</style>
