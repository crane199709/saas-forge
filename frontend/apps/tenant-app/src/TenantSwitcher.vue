<script setup lang="ts">
import { inject, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@forge/sdk'
import type { TenantMembership } from '@forge/sdk'
import ErrorAlert from './ErrorAlert.vue'
import { errorTip, type ErrorTip } from './error-tip'

const props = defineProps<{ reload?: () => void }>()
const { t } = useI18n()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const session = ref(client.session())
const memberships = ref<TenantMembership[]>([])
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
let unsubscribe: (() => void) | undefined

async function load() {
  error.value = null
  if (!session.value) {
    memberships.value = []
    return
  }
  try {
    memberships.value = await client.memberships()
  } catch (cause) {
    if (!(cause instanceof Error && cause.message === 'auth.sessionChanged')) {
      error.value = errorTip(cause, t)
    }
  }
}

async function change(membershipId: string) {
  if (membershipId === session.value?.membershipId) return
  busy.value = true
  error.value = null
  try {
    await client.switchTenant(membershipId)
    // 整页回到首页，丢弃 Shell 与 Sub-App 中属于旧租户的内存状态和路由，再以新 Token 重新加载。
    ;(props.reload ?? (() => location.assign('/')))()
  } catch (cause) {
    error.value = errorTip(cause, t)
    session.value = client.session()
    busy.value = false
  }
}

onMounted(() => {
  unsubscribe = client.onSessionChange((value) => {
    const tenantChanged = value?.membershipId !== session.value?.membershipId
    session.value = value
    if (tenantChanged) void load()
  })
  void load()
})
onUnmounted(() => unsubscribe?.())
</script>

<template>
  <div v-if="session" class="tenant-switcher">
    <el-select
      :model-value="session.membershipId"
      :aria-label="t('tenant.switcher')"
      :disabled="busy || memberships.length < 2"
      :loading="busy"
      data-testid="tenant-switcher"
      @change="change"
    >
      <el-option
        v-for="membership in memberships"
        :key="membership.membershipId"
        :label="membership.tenantName"
        :value="membership.membershipId"
      />
    </el-select>
    <ErrorAlert v-if="error" :tip="error" />
  </div>
</template>

<style scoped>
.tenant-switcher {
  display: flex;
  align-items: center;
  gap: 12px;
  max-width: 560px;
}
</style>
