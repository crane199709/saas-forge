<script setup lang="ts">
import { inject, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@forge/sdk'
import type { TenantLoginResult, TenantMembership } from '@forge/sdk'
import type { TenantContextResponse } from './demo-context'
import ErrorAlert from './ErrorAlert.vue'
import { errorTip, type ErrorTip } from './error-tip'
import TenantSelect from './TenantSelect.vue'
import RegisterForm from './RegisterForm.vue'
import SendCodeButton from './SendCodeButton.vue'

const { t } = useI18n()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const session = ref(client.session())
const context = ref<TenantContextResponse | null>(null)
const account = ref('')
const password = ref('')
// 未登录时的入口：账号密码、邮箱或手机验证码、自主注册。
const mode = ref<'password' | 'code' | 'register'>('password')
const target = ref('')
const code = ref('')
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
// IAM 退出失败时本地凭证已清除，需要单独提供重试入口。
const logoutFailed = ref(false)
// 密码已校验、尚未选定租户时的可选 Membership；noTenant 表示没有任何有效 Membership。
const choices = ref<TenantMembership[] | null>(null)
const noTenant = ref(false)
let contextGeneration = 0

async function loadContext() {
  const started = contextGeneration
  const value = await client.api<TenantContextResponse>('/demo/context')
  if (started !== contextGeneration) return
  context.value = value
  session.value = client.session()
}
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
// 密码登录与验证码登录的结果形态相同，统一处理自动进入、选择租户与无租户。
async function enter(authenticate: () => Promise<TenantLoginResult>) {
  contextGeneration += 1
  busy.value = true
  clearError()
  try {
    const result = await authenticate()
    password.value = ''
    code.value = ''
    if (result.status === 'selectTenant') {
      choices.value = result.memberships
    } else if (result.status === 'noTenant') {
      noTenant.value = true
    } else {
      session.value = result.session
      await loadContext()
    }
  } catch (cause) {
    error.value = errorTip(cause, t)
    session.value = client.session()
  } finally {
    busy.value = false
  }
}
async function select(membershipId: string) {
  contextGeneration += 1
  busy.value = true
  clearError()
  try {
    session.value = await client.selectTenant(membershipId)
    choices.value = null
    await loadContext()
  } catch (cause) {
    error.value = errorTip(cause, t)
    // 登录会话失效时只能重新输入凭证；租户被拒绝时留在选择页。
    if (cause instanceof Error && cause.message === 'auth.failed') choices.value = null
    session.value = client.session()
  } finally {
    busy.value = false
  }
}
function clearError() {
  error.value = null
  logoutFailed.value = false
}
async function reset() {
  contextGeneration += 1
  busy.value = true
  clearError()
  try {
    await client.logout()
  } catch {
    error.value = { message: t('auth.logoutFailed') }
    logoutFailed.value = true
  } finally {
    session.value = null
    context.value = null
    choices.value = null
    noTenant.value = false
    password.value = ''
    code.value = ''
    mode.value = 'password'
    busy.value = false
  }
}
// 刷新失败（Membership 或租户已停用、凭证被撤销）时 SDK 清除会话，面板随之回到登录表单。
const unsubscribe = client.onSessionChange((value) => {
  if (value) return
  contextGeneration += 1
  session.value = null
  context.value = null
})
onUnmounted(unsubscribe)
onMounted(async () => {
  if (!session.value) return
  const started = contextGeneration
  try {
    await loadContext()
  } catch (cause) {
    if (started !== contextGeneration) return
    error.value = errorTip(cause, t)
    session.value = client.session()
  }
})
</script>

<template>
  <section class="login-panel" :aria-label="t('auth.title')">
    <h2>{{ t('auth.title') }}</h2>
    <ErrorAlert v-if="error" :tip="error" />
    <el-button v-if="logoutFailed" :loading="busy" @click="reset">{{
      t('auth.retryLogout')
    }}</el-button>
    <TenantSelect v-if="choices" :memberships="choices" :busy="busy" @select="select" />
    <div v-else-if="noTenant" class="no-tenant">
      <h3>{{ t('tenant.noneTitle') }}</h3>
      <p>{{ t('tenant.noneHint') }}</p>
      <el-button :loading="busy" @click="reset">{{ t('auth.loginAgain') }}</el-button>
    </div>
    <template v-else-if="!session">
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
    </template>
    <div v-else>
      <p>{{ t('auth.identity') }}：{{ session.identityName }}</p>
      <p>{{ t('auth.tenant') }}：{{ session.tenantName }}</p>
      <dl v-if="context">
        <dt>{{ t('auth.identityId') }}</dt>
        <dd>{{ context.identityId }}</dd>
        <dt>{{ t('auth.tenantId') }}</dt>
        <dd>{{ context.tenantId }}</dd>
        <dt>{{ t('auth.membershipId') }}</dt>
        <dd>{{ context.membershipId }}</dd>
      </dl>
      <el-button :loading="busy" @click="reset">{{ t('auth.loginAgain') }}</el-button>
    </div>
  </section>
</template>

<style scoped>
.login-panel {
  max-width: 560px;
  margin-bottom: 28px;
}
.modes {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
}
.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
dd {
  margin: 4px 0 14px;
  overflow-wrap: anywhere;
}
</style>
