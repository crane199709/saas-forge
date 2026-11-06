<script setup lang="ts">
import { computed, inject, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError, tenantClientKey } from '@crane199709/sdk'
import type { TenantMembership } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
import { errorTip, type ErrorTip } from './error-tip'
import { loginFlow, needsFullLogin } from './login-flow'
import { createMemberApi } from './member/member-api'
import MfaBindForm from './security/MfaBindForm.vue'
import { reloadShellKey } from './shell'

const props = defineProps<{ reload?: () => void }>()
// Shell 提供的重载：卸载全部 Sub-App、清空共享 Pinia 状态后整页回到首页
const reloadShell = inject(reloadShellKey, () => location.assign('/'))
const reload = () => (props.reload ?? reloadShell)()
const { t } = useI18n()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const session = ref(client.session())
const memberships = ref<TenantMembership[]>([])
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const leaving = ref(false)
// 目标租户要求 MFA 而当前授权未完成时，当场绑定后重试切换（账号安全规格 2.4）。
const bindFor = ref<string | null>(null)
const bindConfirmed = ref(false)
const currentName = computed(
  () =>
    memberships.value.find((item) => item.membershipId === session.value?.membershipId)
      ?.tenantName ?? '',
)
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
    // 丢弃 Shell 与 Sub-App 中属于旧租户的内存状态和路由，再以新 Token 重新加载。
    void reload()
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === 'auth.mfaRequired') {
      bindConfirmed.value = false
      bindFor.value = membershipId
    } else {
      error.value = errorTip(cause, t)
    }
    session.value = client.session()
    busy.value = false
  }
}

function bindFailed(cause: unknown) {
  if (needsFullLogin(cause)) {
    // Token 与登录会话都走不通，或身份已在别处绑定：清除会话后路由守卫回到登录页，走完整流程。
    bindFor.value = null
    loginFlow.error = { message: t('mfa.reloginRequired') }
    client.clear()
  } else {
    error.value = errorTip(cause, t)
  }
}

async function bound() {
  const membershipId = bindFor.value!
  bindFor.value = null
  busy.value = true
  error.value = null
  try {
    await client.resumeEntry(membershipId)
    void reload()
  } catch (cause) {
    busy.value = false
    bindFailed(cause)
  }
}

// 退出后当前 Token 随版本递增失效，不能再用它换发其他租户的 Token；结束会话回到登录，有其他租户的身份重新登录后选择。
async function leave() {
  busy.value = true
  error.value = null
  try {
    await createMemberApi(client).leave()
  } catch (cause) {
    error.value = errorTip(cause, t)
    busy.value = false
    leaving.value = false
    return
  }
  try {
    await client.logout()
  } catch {
    // 本地凭证已清除；IAM 会话退出失败不影响已完成的退出租户。
  }
  void reload()
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
    <el-button
      link
      type="danger"
      :disabled="busy"
      data-testid="tenant-leave"
      @click="leaving = true"
      >{{ t('tenant.leave') }}</el-button
    >
    <ErrorAlert v-if="error" :tip="error" />
    <el-dialog
      :model-value="bindFor !== null"
      :title="t('mfa.bindTitle')"
      :show-close="!bindConfirmed"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      @close="bindFor = null"
    >
      <p>{{ t('mfa.bindRequired') }}</p>
      <MfaBindForm
        v-if="bindFor"
        @confirmed="bindConfirmed = true"
        @bound="bound"
        @submitting="error = null"
        @error="bindFailed"
      />
    </el-dialog>
    <el-dialog v-model="leaving" :title="t('tenant.leave')">
      <p>{{ t('tenant.leaveConfirm', { tenant: currentName }) }}</p>
      <template #footer>
        <el-button @click="leaving = false">{{ t('tenant.cancel') }}</el-button>
        <el-button
          type="danger"
          :disabled="busy"
          data-testid="tenant-leave-confirm"
          @click="leave"
          >{{ t('tenant.leave') }}</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tenant-switcher {
  display: flex;
  align-items: center;
  gap: 12px;
  max-width: 560px;
}
/* 顶栏是 flex 布局，选择框不设宽度会收缩到只剩箭头 */
.tenant-switcher .el-select {
  width: 200px;
}
</style>
