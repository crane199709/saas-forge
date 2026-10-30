<script setup lang="ts">
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { tenantClientKey } from '@forge/sdk'
import AuthCard from './AuthCard.vue'
import { signOut } from './login-flow'

const { t } = useI18n()
const router = useRouter()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const busy = ref(false)

// 没有 Membership 时不签发 Token，但 IAM 登录会话已建立，换账号前先退出。
async function loginAgain() {
  busy.value = true
  await signOut(client)
  busy.value = false
  await router.replace('/login')
}
</script>

<template>
  <AuthCard :title="t('auth.title')">
    <h3>{{ t('tenant.noneTitle') }}</h3>
    <p>{{ t('tenant.noneHint') }}</p>
    <el-button :loading="busy" @click="loginAgain">{{ t('auth.loginAgain') }}</el-button>
  </AuthCard>
</template>
