<script setup lang="ts">
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { tenantClientKey } from '@forge/sdk'
import type { TenantLoginResult } from '@forge/sdk'
import AuthCard from './AuthCard.vue'
import ErrorAlert from './ErrorAlert.vue'
import { errorTip, type ErrorTip } from './error-tip'
import { loginFlow, safeRedirect, signOut } from './login-flow'
import RegisterForm from './RegisterForm.vue'
import SendCodeButton from './SendCodeButton.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const account = ref('')
const password = ref('')
// 未登录时的入口：账号密码、邮箱或手机验证码、自主注册。
const mode = ref<'password' | 'code' | 'register'>('password')
const target = ref('')
const code = ref('')
const busy = ref(false)
const error = ref<ErrorTip | null>(loginFlow.error)
loginFlow.error = null

function login() {
  return enter(() => client.login(account.value, password.value))
}
function codeLogin() {
  return enter(() => client.loginWithCode(target.value, code.value))
}
// 注册后用同一账号密码登录，新身份没有 Membership，因此进入无租户页。
function registered(newAccount: string, newPassword: string) {
  account.value = newAccount
  return enter(() => client.login(newAccount, newPassword))
}
function showError(cause: unknown) {
  error.value = errorTip(cause, t)
}
function switchMode(next: typeof mode.value) {
  mode.value = next
  clearError()
}
function clearError() {
  error.value = null
  loginFlow.logoutFailed = false
}
// 密码登录与验证码登录的结果形态相同，统一处理自动进入、选择租户与无租户；redirect 随之带到选择租户页。
async function enter(authenticate: () => Promise<TenantLoginResult>) {
  busy.value = true
  clearError()
  try {
    const result = await authenticate()
    password.value = ''
    code.value = ''
    if (result.status === 'selectTenant') {
      loginFlow.memberships = result.memberships
      await router.push({ path: '/select-tenant', query: route.query })
    } else if (result.status === 'noTenant') {
      await router.push('/no-tenant')
    } else {
      await router.replace(safeRedirect(route.query.redirect))
    }
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}
async function retryLogout() {
  busy.value = true
  await signOut(client)
  busy.value = false
}
</script>

<template>
  <AuthCard :title="t('auth.title')">
    <ErrorAlert v-if="error" :tip="error" />
    <template v-if="loginFlow.logoutFailed">
      <ErrorAlert :tip="{ message: t('auth.logoutFailed') }" />
      <el-button :loading="busy" @click="retryLogout">{{ t('auth.retryLogout') }}</el-button>
    </template>
    <nav class="modes">
      <el-button
        v-for="item in ['password', 'code', 'register'] as const"
        :key="item"
        link
        :type="mode === item ? 'primary' : 'default'"
        :data-testid="`mode-${item}`"
        @click="switchMode(item)"
        >{{ t(`auth.mode.${item}`) }}</el-button
      >
    </nav>
    <RegisterForm
      v-if="mode === 'register'"
      @registered="registered"
      @submitting="clearError"
      @error="showError"
    />
    <el-form v-else-if="mode === 'code'" label-position="top" @submit.prevent="codeLogin">
      <el-form-item :label="t('code.target')" for="code-target">
        <el-input
          id="code-target"
          v-model="target"
          name="code-target"
          autocomplete="username"
          required
        />
      </el-form-item>
      <el-form-item :label="t('code.code')" for="code">
        <div class="code-row">
          <el-input
            id="code"
            v-model="code"
            name="code"
            autocomplete="one-time-code"
            inputmode="numeric"
            required
          />
          <SendCodeButton :target="target" :send="client.sendLoginCode" @error="showError" />
        </div>
      </el-form-item>
      <el-button native-type="submit" type="primary" :loading="busy">{{
        t('auth.login')
      }}</el-button>
    </el-form>
    <el-form v-else label-position="top" @submit.prevent="login">
      <el-form-item :label="t('auth.account')" for="tenant-account">
        <el-input
          id="tenant-account"
          v-model="account"
          name="account"
          autocomplete="username"
          required
        />
      </el-form-item>
      <el-form-item :label="t('auth.password')" for="tenant-password">
        <el-input
          id="tenant-password"
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
  </AuthCard>
</template>

<style scoped>
.modes {
  display: flex;
  gap: 16px;
  margin: 12px 0;
}
.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
</style>
