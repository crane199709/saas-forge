<script setup lang="ts">
import { ref } from 'vue'
import { useTenantClient } from '@crane199709/sdk'

// 开发用宿主的外壳：只提供登录与租户选择，便于 Sub-App 调用需要 Token 的接口；界面文案不进入任何产物。
defineProps<{ appCode: string }>()
const client = useTenantClient()
const session = ref(client.session())
client.onSessionChange((value) => (session.value = value))
const account = ref('')
const password = ref('')
const error = ref('')

async function login() {
  error.value = ''
  try {
    const result = await client.login(account.value, password.value)
    if (result.status === 'selectTenant')
      await client.selectTenant(result.memberships[0].membershipId)
    else if (result.status === 'noTenant') error.value = '该身份没有可进入的租户'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  }
}
</script>

<template>
  <header class="dev-host">
    <strong>Sub-App 开发宿主 · {{ appCode }}</strong>
    <template v-if="session">
      <span>{{ session.identityName }} @ {{ session.tenantName }}</span>
      <el-button size="small" @click="client.logout()">退出</el-button>
    </template>
    <form v-else @submit.prevent="login">
      <el-input v-model="account" size="small" placeholder="账号" aria-label="账号" />
      <el-input
        v-model="password"
        size="small"
        type="password"
        placeholder="密码"
        aria-label="密码"
      />
      <el-button size="small" native-type="submit">登录</el-button>
      <span v-if="error" role="alert">{{ error }}</span>
    </form>
  </header>
  <main>
    <router-view />
  </main>
</template>

<style scoped>
.dev-host,
form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--forge-space-2);
}
.dev-host {
  padding: var(--forge-space-2) var(--forge-space-4);
  border-bottom: 1px solid var(--el-border-color);
}
main {
  padding: var(--forge-space-4);
}
</style>
