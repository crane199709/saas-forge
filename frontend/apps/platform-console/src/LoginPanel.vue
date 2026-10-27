<script setup lang="ts">
import { inject, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError, platformClientKey } from '@forge/sdk'

/** forge-platform 经 SDK 验签后返回的当前 Operator。 */
interface CurrentOperatorResponse {
  operatorId: string
}

const { t } = useI18n()
const providedClient = inject(platformClientKey)
if (!providedClient) throw new Error('Shell must provide a PlatformClient')
const client = providedClient
const session = ref(client.session())
const operator = ref<CurrentOperatorResponse | null>(null)
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

async function loadOperator() {
  operator.value = await client.api<CurrentOperatorResponse>('/platform/operators/current')
  session.value = client.session()
}

async function login() {
  busy.value = true
  error.value = null
  try {
    session.value = await client.login(account.value, password.value)
    password.value = ''
    await loadOperator()
  } catch (cause) {
    showError(cause)
    session.value = client.session()
  } finally {
    busy.value = false
  }
}

async function logout() {
  busy.value = true
  error.value = null
  try {
    await client.logout()
  } catch {
    error.value = { message: t('auth.logoutFailed') }
  } finally {
    session.value = null
    operator.value = null
    password.value = ''
    busy.value = false
  }
}

// 刷新凭证被拒绝时 SDK 清除会话，面板随之回到登录表单。
const unsubscribe = client.onSessionChange((value) => {
  if (value) return
  session.value = null
  operator.value = null
})
onUnmounted(unsubscribe)
onMounted(async () => {
  if (!session.value) return
  try {
    await loadOperator()
  } catch (cause) {
    showError(cause)
    session.value = client.session()
  }
})
</script>

<template>
  <section class="login-panel" :aria-label="t('auth.title')">
    <h2>{{ t('auth.title') }}</h2>
    <el-alert v-if="error" :title="error.message" type="error" :closable="false" role="alert">
      <p v-if="error.traceId" class="trace">
        {{ t('error.traceId') }}：<code>{{ error.traceId }}</code>
      </p>
    </el-alert>
    <el-form v-if="!session" label-position="top" @submit.prevent="login">
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
    <div v-else>
      <p>{{ t('auth.operator') }}：{{ session.operatorName }}</p>
      <dl v-if="operator">
        <dt>{{ t('auth.operatorId') }}</dt>
        <dd>{{ operator.operatorId }}</dd>
      </dl>
      <el-button :loading="busy" @click="logout">{{ t('auth.logout') }}</el-button>
    </div>
  </section>
</template>

<style scoped>
.login-panel {
  max-width: 560px;
}
.trace {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
}
dd {
  margin: 4px 0 14px;
  overflow-wrap: anywhere;
}
</style>
