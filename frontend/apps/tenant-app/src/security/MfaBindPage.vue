<script setup lang="ts">
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { tenantClientKey } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
import AuthCard from '../AuthCard.vue'
import { errorTip, type ErrorTip } from '../error-tip'
import { loginFlow, needsFullLogin, safeRedirect, signOut } from '../login-flow'
import MfaBindForm from './MfaBindForm.vue'

// 已登录的身份刷新 Token 时被 MFA Requirement 拦下后到这里：当场绑定，成功后重试进入原页面（账号安全规格 2.4）。
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const error = ref<ErrorTip | null>(null)
const busy = ref(false)

function showError(cause: unknown) {
  if (needsFullLogin(cause)) void backToLogin()
  else error.value = errorTip(cause, t)
}
// Token 与登录会话都走不通，或身份已在别处绑定：只有完整登录才能在授权上记下 MFA。
async function backToLogin() {
  client.clear()
  loginFlow.error = { message: t('mfa.reloginRequired') }
  await router.replace({ path: '/login', query: route.query })
}
async function bound() {
  busy.value = true
  error.value = null
  try {
    await client.resumeEntry()
    await router.replace(safeRedirect(route.query.redirect))
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}
async function logout() {
  await signOut(client)
  await router.replace('/login')
}
</script>

<template>
  <AuthCard :title="t('auth.title')">
    <ErrorAlert v-if="error" :tip="error" />
    <section :aria-label="t('mfa.bindTitle')">
      <h3>{{ t('mfa.bindTitle') }}</h3>
      <p>{{ t('mfa.bindRequired') }}</p>
      <MfaBindForm @bound="bound" @submitting="error = null" @error="showError" />
    </section>
    <el-button link :disabled="busy" data-testid="mfa-bind-logout" @click="logout">{{
      t('auth.logout')
    }}</el-button>
  </AuthCard>
</template>
