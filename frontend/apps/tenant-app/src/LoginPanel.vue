<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey, type TenantClient, type TenantContextResponse } from '@forge/sdk'

const { t } = useI18n()
const client = inject<TenantClient>(tenantClientKey)!
if (!client) throw new Error('Shell must provide a TenantClient')
const session = ref(client.session())
const context = ref<TenantContextResponse | null>(null)
const account = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
let contextGeneration = 0

async function loadContext() {
  const started = contextGeneration
  const response = await client.request('/demo/context')
  if (!response.ok) throw new Error('auth.contextFailed')
  const value = (await response.json()) as TenantContextResponse
  if (started !== contextGeneration) return
  context.value = value
  session.value = client.session()
}
async function login() {
  contextGeneration += 1
  busy.value = true
  error.value = ''
  try {
    session.value = await client.login(account.value, password.value)
    password.value = ''
    await loadContext()
  } catch (cause) {
    error.value =
      cause instanceof Error &&
      ['auth.contextFailed', 'auth.membershipSelectionRequired'].includes(cause.message)
        ? cause.message
        : 'auth.failed'
    session.value = client.session()
  } finally {
    busy.value = false
  }
}
async function reset() {
  contextGeneration += 1
  busy.value = true
  error.value = ''
  try {
    await client.logout()
  } catch {
    error.value = 'auth.logoutFailed'
  } finally {
    session.value = null
    context.value = null
    password.value = ''
    busy.value = false
  }
}
onMounted(async () => {
  if (!session.value) return
  const started = contextGeneration
  try {
    await loadContext()
  } catch {
    if (started !== contextGeneration) return
    error.value = 'auth.contextFailed'
    session.value = client.session()
  }
})
</script>

<template>
  <section class="login-panel" :aria-label="t('auth.title')">
    <h2>{{ t('auth.title') }}</h2>
    <el-alert v-if="error" :title="t(error)" type="error" :closable="false" role="alert" />
    <el-button v-if="error === 'auth.logoutFailed'" :loading="busy" @click="reset">{{
      t('auth.retryLogout')
    }}</el-button>
    <el-form v-if="!session" label-position="top" @submit.prevent="login">
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
dd {
  margin: 4px 0 14px;
  overflow-wrap: anywhere;
}
</style>
