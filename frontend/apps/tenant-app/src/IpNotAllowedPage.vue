<script setup lang="ts">
import { computed, inject, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useTenantClient, type TenantMembership } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
import AuthCard from './AuthCard.vue'
import TenantSelect from './TenantSelect.vue'
import { errorTip, type ErrorTip } from './error-tip'
import { loginFlow, signOut } from './login-flow'
import { reloadShellKey } from './shell'

const client = useTenantClient()
const router = useRouter()
const { t } = useI18n()
const reload = inject(reloadShellKey, () => location.assign('/'))
const denial = ref(client.ipDenial())
const memberships = ref<TenantMembership[]>([])
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const unsubscribe = client.onIpNotAllowed((value) => {
  denial.value = value
})
const alternatives = computed(() =>
  memberships.value.filter(
    (item) => item.membershipId !== (denial.value?.membershipId ?? client.session()?.membershipId),
  ),
)
const canReturn = computed(() => client.session() && denial.value && !denial.value.currentTenant)

onMounted(async () => {
  try {
    memberships.value = client.session()
      ? await client.memberships()
      : (loginFlow.memberships ?? [])
  } catch (cause) {
    error.value = errorTip(cause, t)
  }
})
onUnmounted(unsubscribe)

async function select(membershipId: string) {
  busy.value = true
  error.value = null
  try {
    if (client.session()) await client.switchTenant(membershipId)
    else await client.selectTenant(membershipId)
    loginFlow.memberships = null
    reload()
  } catch (cause) {
    error.value = errorTip(cause, t)
    busy.value = false
  }
}
async function logout() {
  busy.value = true
  loginFlow.memberships = null
  await signOut(client)
  await router.replace('/login')
  busy.value = false
}
function returnToCurrent() {
  client.dismissTargetIpDenial()
  reload()
}
</script>

<template>
  <AuthCard title="当前 IP 无法访问该租户">
    <p>该租户的 IP 允许列表限制了当前访问来源。你可以进入其他租户，或退出后联系租户管理员。</p>
    <ErrorAlert v-if="denial" class="page-error" :tip="errorTip(denial.error, t)" />
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <TenantSelect
      v-if="alternatives.length"
      :memberships="alternatives"
      :busy="busy"
      @select="select"
    />
    <p v-else>暂无其他可进入的租户。</p>
    <div class="actions">
      <el-button v-if="canReturn" :disabled="busy" @click="returnToCurrent">返回当前租户</el-button>
      <el-button data-testid="ip-denied-logout" :loading="busy" @click="logout">退出登录</el-button>
    </div>
  </AuthCard>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
p {
  margin: 0 0 var(--forge-space-3);
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--forge-space-2);
  margin-top: var(--forge-space-4);
}
</style>
