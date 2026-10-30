<script setup lang="ts">
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { tenantClientKey } from '@forge/sdk'
import AuthCard from './AuthCard.vue'
import { ErrorAlert } from '@forge/ui'
import { errorTip, type ErrorTip } from './error-tip'
import { loginFlow, safeRedirect } from './login-flow'
import TenantSelect from './TenantSelect.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

async function select(membershipId: string) {
  busy.value = true
  error.value = null
  try {
    await client.selectTenant(membershipId)
    loginFlow.memberships = null
    await router.replace(safeRedirect(route.query.redirect))
  } catch (cause) {
    // 登录会话失效时只能重新输入凭证；租户被拒绝时留在选择页。
    if (cause instanceof Error && cause.message === 'auth.failed') {
      loginFlow.memberships = null
      loginFlow.error = errorTip(cause, t)
      await router.replace({ path: '/login', query: route.query })
    } else {
      error.value = errorTip(cause, t)
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AuthCard :title="t('auth.title')">
    <ErrorAlert v-if="error" :tip="error" />
    <TenantSelect
      v-if="loginFlow.memberships"
      :memberships="loginFlow.memberships"
      :busy="busy"
      @select="select"
    />
  </AuthCard>
</template>
