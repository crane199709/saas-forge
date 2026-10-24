<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createTenantClient, type TenantContextResponse } from '@forge/sdk'

const { t } = useI18n()
const client = createTenantClient(sessionStorage)
const session = ref(client.session())
const context = ref<TenantContextResponse | null>(null)
const account = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

async function loadContext() {
  const response = await client.request('/demo/context')
  if (!response.ok) throw new Error('auth.contextFailed')
  context.value = (await response.json()) as TenantContextResponse
  session.value = client.session()
}
async function login() {
  busy.value = true
  error.value = ''
  try {
    session.value = await client.login(account.value, password.value)
    password.value = ''
    await loadContext()
  } catch (cause) {
    error.value =
      cause instanceof Error && cause.message === 'auth.contextFailed'
        ? 'auth.contextFailed'
        : 'auth.failed'
    session.value = client.session()
  } finally {
    busy.value = false
  }
}
function reset() {
  client.clear()
  session.value = null
  context.value = null
  password.value = ''
  error.value = ''
}
onMounted(async () => {
  if (!session.value) return
  try {
    await loadContext()
  } catch {
    error.value = 'auth.contextFailed'
    session.value = client.session()
  }
})
</script>

<template>
  <section class="login-panel" :aria-label="t('auth.title')">
    <h2>{{ t('auth.title') }}</h2>
    <el-alert v-if="error" :title="t(error)" type="error" :closable="false" role="alert" />
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
      <el-button @click="reset">{{ t('auth.loginAgain') }}</el-button>
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
