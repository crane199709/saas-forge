<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@crane199709/sdk'
import type { MfaFactor, PlatformLoginResult } from '@crane199709/sdk'
import { ErrorAlert, MfaLoginForm, type ErrorTip } from '@crane199709/ui'
import { errorTip } from './error-tip'
import { loginFlow, safeRedirect } from './login-flow'
import MfaBindForm from './security/MfaBindForm.vue'
import { usePlatformClient } from './shell'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const client = usePlatformClient()
const account = ref('')
const password = ref('')
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
// 密码通过后的步骤：已绑定验证器时输入动态码；mfa-required 且未绑定时当场绑定。
const step = ref<'credentials' | 'mfa' | 'bind'>('credentials')
const otpauthUri = ref('')

function showError(cause: unknown) {
  error.value = errorTip(cause, t)
  // 待 MFA 已过期或登录会话已失效：只能重新输入密码。
  if (isSessionLost(cause)) step.value = 'credentials'
}

function isSessionLost(cause: unknown) {
  if (cause instanceof ApiError) return ['auth.mfaExpired', 'auth.failed'].includes(cause.code)
  return cause instanceof Error && cause.message === 'auth.failed'
}

function clearError() {
  error.value = null
  loginFlow.logoutFailed = false
}

function backToCredentials() {
  step.value = 'credentials'
  clearError()
}

async function enter(authenticate: () => Promise<PlatformLoginResult>) {
  busy.value = true
  clearError()
  try {
    const result = await authenticate()
    password.value = ''
    if (result.status === 'mfa') {
      step.value = 'mfa'
    } else if (result.status === 'bind') {
      otpauthUri.value = (await client.startLoginBinding()).otpauthUri
      step.value = 'bind'
    } else {
      await router.replace(safeRedirect(route.query.redirect))
    }
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}

const login = () => enter(() => client.login(account.value, password.value))
const submitMfa = (factor: MfaFactor) => enter(() => client.submitMfa(factor))
// 恢复码已保存，此时才签发 Token 并离开登录页。
const bound = () =>
  enter(async () => ({ status: 'signedIn', session: await client.completeLogin() }))
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card">
      <section :aria-label="t('auth.title')">
        <h2>{{ t('auth.title') }}</h2>
        <ErrorAlert v-if="loginFlow.logoutFailed" :tip="{ message: t('auth.logoutFailed') }" />
        <ErrorAlert v-if="error" :tip="error" />
        <MfaLoginForm
          v-if="step === 'mfa'"
          :busy="busy"
          @submit="submitMfa"
          @cancel="backToCredentials"
        />
        <section v-else-if="step === 'bind'" :aria-label="t('mfa.bindTitle')">
          <h3>{{ t('mfa.bindTitle') }}</h3>
          <p>{{ t('mfa.bindRequired') }}</p>
          <MfaBindForm
            :otpauth-uri="otpauthUri"
            :confirm="client.confirmLoginBinding"
            @bound="bound"
            @submitting="clearError"
            @error="showError"
          />
          <el-button link @click="backToCredentials">{{ t('auth.backToLogin') }}</el-button>
        </section>
        <el-form v-else label-position="top" @submit.prevent="login">
          <el-form-item :label="t('auth.account')" for="operator-account">
            <el-input
              id="operator-account"
              v-model="account"
              name="account"
              autocomplete="username"
              required
            />
          </el-form-item>
          <el-form-item :label="t('auth.password')" for="operator-password">
            <el-input
              id="operator-password"
              v-model="password"
              name="password"
              type="password"
              autocomplete="current-password"
              required
              show-password
            />
          </el-form-item>
          <el-button native-type="submit" type="primary" :loading="busy">{{
            t('auth.login')
          }}</el-button>
        </el-form>
      </section>
    </el-card>
  </main>
</template>

<style scoped>
.auth-page {
  display: flex;
  justify-content: center;
  padding: 80px 16px;
}
.auth-card {
  width: 100%;
  max-width: 480px;
}
</style>
