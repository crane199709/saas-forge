<script setup lang="ts">
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, tenantClientKey } from '@crane199709/sdk'
import type { MfaFactor, TenantLoginResult } from '@crane199709/sdk'
import AuthCard from './AuthCard.vue'
import { ErrorAlert, MfaLoginForm } from '@crane199709/ui'
import { errorTip, type ErrorTip } from './error-tip'
import { loginFlow, safeRedirect, signOut } from './login-flow'
import RegisterForm from './RegisterForm.vue'
import MfaBindForm from './security/MfaBindForm.vue'
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
// First Factor 通过后的步骤：已绑定验证器时输入动态码；唯一的租户要求 MFA 时当场绑定后重新进入该 Membership。
const step = ref<'credentials' | 'mfa' | 'bind'>('credentials')
let bindMembershipId = ''
const error = ref<ErrorTip | null>(loginFlow.error)
loginFlow.error = null

function login() {
  return enter(() => client.login(account.value, password.value))
}
function codeLogin() {
  return enter(() => client.loginWithCode(target.value, code.value))
}
function submitMfa(factor: MfaFactor) {
  return enter(() => client.submitMfa(factor))
}
// 确认绑定后登录会话已补记 MFA，被拒的授权码不能重试，重新走一次授权码流程。
function bound() {
  return enter(async () => ({
    status: 'signedIn',
    session: await client.selectTenant(bindMembershipId),
  }))
}
function backToCredentials() {
  step.value = 'credentials'
  clearError()
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
    if (result.status === 'mfa') {
      step.value = 'mfa'
    } else if (result.status === 'mfaBindRequired') {
      bindMembershipId = result.membershipId
      step.value = 'bind'
    } else if (result.status === 'selectTenant') {
      loginFlow.memberships = result.memberships
      await router.push({ path: '/select-tenant', query: route.query })
    } else if (result.status === 'noTenant') {
      await router.push('/no-tenant')
    } else {
      await router.replace(safeRedirect(route.query.redirect))
    }
  } catch (cause) {
    error.value = errorTip(cause, t)
    // 待 MFA 已过期或登录会话已失效：只能重新输入 First Factor。
    if (step.value !== 'credentials' && isSessionLost(cause)) step.value = 'credentials'
  } finally {
    busy.value = false
  }
}
function isSessionLost(cause: unknown) {
  if (cause instanceof ApiError) return ['auth.mfaExpired', 'auth.failed'].includes(cause.code)
  return cause instanceof Error && cause.message === 'auth.failed'
}
async function retryLogout() {
  busy.value = true
  await signOut(client)
  busy.value = false
}
</script>

<template>
  <AuthCard :title="t('auth.title')">
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <template v-if="loginFlow.logoutFailed">
      <ErrorAlert class="page-error" :tip="{ message: t('auth.logoutFailed') }" />
      <el-button :loading="busy" @click="retryLogout">{{ t('auth.retryLogout') }}</el-button>
    </template>
    <MfaLoginForm
      v-if="step === 'mfa'"
      :busy="busy"
      @submit="submitMfa"
      @cancel="backToCredentials"
    />
    <section v-else-if="step === 'bind'" :aria-label="t('mfa.bindTitle')">
      <h3>{{ t('mfa.bindTitle') }}</h3>
      <p>{{ t('mfa.bindRequired') }}</p>
      <MfaBindForm @bound="bound" @submitting="clearError" @error="showError" />
    </section>
    <template v-else>
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
        <div class="submit-row">
          <el-button native-type="submit" type="primary" :loading="busy">{{
            t('auth.login')
          }}</el-button>
          <router-link to="/forgot-password" data-testid="forgot-password">{{
            t('auth.forgotPassword')
          }}</router-link>
        </div>
      </el-form>
    </template>
  </AuthCard>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
h3 {
  margin: 0 0 var(--forge-space-2);
  font-size: var(--forge-font-size-md);
  font-weight: 600;
}
.modes {
  display: flex;
  gap: var(--forge-space-2);
  margin-bottom: var(--forge-space-3);
}
.submit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.submit-row a {
  color: var(--el-color-primary);
  text-decoration: none;
}
.code-row {
  display: flex;
  gap: var(--forge-space-2);
  width: 100%;
}
</style>
