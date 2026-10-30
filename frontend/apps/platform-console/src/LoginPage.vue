<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@forge/sdk'
import { loginFlow, safeRedirect } from './login-flow'
import { usePlatformClient } from './shell'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const client = usePlatformClient()
const account = ref('')
const password = ref('')
const busy = ref(false)
const error = ref<{ message: string; traceId?: string } | null>(null)

// 优先展示服务端已翻译的 userTip；SDK 本地判定的 auth.failed 与其他故障按 i18n key 显示。
function showError(cause: unknown) {
  const traceId = cause instanceof ApiError ? cause.traceId : undefined
  if (cause instanceof ApiError && cause.userTip) {
    error.value = { message: cause.userTip, traceId }
  } else {
    const local = cause instanceof Error && cause.message === 'auth.failed'
    error.value = { message: t(local ? 'auth.failed' : 'error.unknown'), traceId }
  }
}

async function login() {
  busy.value = true
  error.value = null
  loginFlow.logoutFailed = false
  try {
    await client.login(account.value, password.value)
    password.value = ''
    await router.replace(safeRedirect(route.query.redirect))
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card">
      <section :aria-label="t('auth.title')">
        <h2>{{ t('auth.title') }}</h2>
        <el-alert
          v-if="loginFlow.logoutFailed"
          :title="t('auth.logoutFailed')"
          type="error"
          :closable="false"
          role="alert"
        />
        <el-alert v-if="error" :title="error.message" type="error" :closable="false" role="alert">
          <p v-if="error.traceId" class="trace">
            {{ t('error.traceId') }}：<code>{{ error.traceId }}</code>
          </p>
        </el-alert>
        <el-form label-position="top" @submit.prevent="login">
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
.trace {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
}
</style>
