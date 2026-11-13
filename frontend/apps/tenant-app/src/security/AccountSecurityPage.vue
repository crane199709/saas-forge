<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, RecoveryCodeList } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import LeaveTenantSection from './LeaveTenantSection.vue'
import MfaBindForm from './MfaBindForm.vue'

// 密码与验证器属于 Identity，当前会话信息随 Active Tenant 变化。
const { t } = useI18n()
const client = useTenantClient()
const session = ref(client.session())
const unsubscribe = client.onSessionChange((value) => {
  session.value = value
})
onUnmounted(unsubscribe)
const identityId = computed(() => {
  // IAM 签发的当前 Access Token 以 sub 标识 Identity；这里只展示，不据此判断权限。
  const payload = session.value?.accessToken.split('.')[1]
  if (!payload) return null
  try {
    const { sub } = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
    return typeof sub === 'string' && sub ? sub : null
  } catch {
    return null
  }
})
const error = ref<ErrorTip | null>(null)
const busy = ref(false)

const currentPassword = ref('')
const newPassword = ref('')
const passwordChanged = ref(false)

const bound = ref<boolean | null>(null)
const binding = ref(false)
const manageCode = ref('')
const recoveryCodes = ref<string[] | null>(null)

function showError(cause: unknown) {
  error.value = errorTip(cause, t)
}

async function run(task: () => Promise<void>) {
  busy.value = true
  error.value = null
  try {
    await task()
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}

const loadStatus = () =>
  run(async () => {
    bound.value = (await client.mfaStatus()).bound
  })

onMounted(loadStatus)

function changePassword() {
  passwordChanged.value = false
  return run(async () => {
    await client.changePassword(currentPassword.value, newPassword.value)
    currentPassword.value = ''
    newPassword.value = ''
    passwordChanged.value = true
  })
}

function bindDone() {
  binding.value = false
  return loadStatus()
}

function regenerate() {
  return run(async () => {
    recoveryCodes.value = (
      await client.regenerateRecoveryCodes(manageCode.value.trim())
    ).recoveryCodes
    manageCode.value = ''
  })
}

function unbind() {
  return run(async () => {
    await client.unbindMfa(manageCode.value.trim())
    manageCode.value = ''
    bound.value = (await client.mfaStatus()).bound
  })
}
</script>

<template>
  <section class="account-security" :aria-label="t('account.title')">
    <PageHeader :title="t('account.title')" />
    <ErrorAlert v-if="error" :tip="error" />

    <section class="block">
      <h3>{{ t('account.password.title') }}</h3>
      <el-alert
        v-if="passwordChanged"
        type="success"
        :closable="false"
        :title="t('account.password.done')"
      />
      <el-form label-position="top" data-testid="password-form" @submit.prevent="changePassword">
        <el-form-item :label="t('account.password.current')" for="current-password">
          <el-input
            id="current-password"
            v-model="currentPassword"
            name="current-password"
            type="password"
            autocomplete="current-password"
            required
            show-password
          />
        </el-form-item>
        <el-form-item :label="t('account.password.new')" for="new-password">
          <el-input
            id="new-password"
            v-model="newPassword"
            name="new-password"
            type="password"
            autocomplete="new-password"
            required
            show-password
          />
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('account.password.submit')
        }}</el-button>
      </el-form>
    </section>

    <section class="block" data-testid="mfa-section">
      <h3>{{ t('account.mfa.title') }}</h3>
      <RecoveryCodeList v-if="recoveryCodes" :codes="recoveryCodes" @done="recoveryCodes = null" />
      <MfaBindForm
        v-else-if="binding"
        @bound="bindDone"
        @submitting="error = null"
        @error="showError"
      />
      <template v-else-if="bound === false">
        <p>{{ t('account.mfa.unbound') }}</p>
        <el-button type="primary" data-testid="mfa-bind" @click="binding = true">{{
          t('account.mfa.bind')
        }}</el-button>
      </template>
      <template v-else-if="bound">
        <p>{{ t('account.mfa.bound') }}</p>
        <el-form label-position="top" @submit.prevent>
          <el-form-item :label="t('account.mfa.code')" for="mfa-manage-code">
            <el-input
              id="mfa-manage-code"
              v-model="manageCode"
              name="mfa-manage-code"
              autocomplete="one-time-code"
              inputmode="numeric"
              maxlength="6"
            />
          </el-form-item>
        </el-form>
        <p class="hint">{{ t('account.mfa.regenerateHint') }} {{ t('account.mfa.unbindHint') }}</p>
        <el-button
          data-testid="mfa-regenerate"
          :loading="busy"
          :disabled="!manageCode"
          @click="regenerate"
          >{{ t('account.mfa.regenerate') }}</el-button
        >
        <el-button
          type="danger"
          data-testid="mfa-unbind"
          :loading="busy"
          :disabled="!manageCode"
          @click="unbind"
          >{{ t('account.mfa.unbind') }}</el-button
        >
      </template>
    </section>

    <section v-if="session" class="block current-session" :aria-label="t('account.session.title')">
      <h3>{{ t('account.session.title') }}</h3>
      <el-descriptions :column="1" border>
        <el-descriptions-item :label="t('account.session.identityName')">
          {{ session.identityName || '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('account.session.tenantName')">
          {{ session.tenantName || '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('account.session.identityId')">
          {{ identityId || '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('account.session.tenantId')">
          {{ session.tenantId || '—' }}
        </el-descriptions-item>
        <el-descriptions-item :label="t('account.session.membershipId')">
          {{ session.membershipId || '—' }}
        </el-descriptions-item>
      </el-descriptions>
    </section>

    <LeaveTenantSection class="block" />
  </section>
</template>

<style scoped>
.account-security {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-4);
  max-width: 640px;
}
.block + .block {
  padding-top: var(--forge-space-4);
  border-top: 1px solid var(--el-border-color-lighter);
}
h3 {
  margin: 0 0 var(--forge-space-3);
  font-size: var(--forge-font-size-md);
  font-weight: 600;
}
.current-session :deep(.el-descriptions__table) {
  table-layout: fixed;
}
.current-session :deep(.el-descriptions__label) {
  width: 140px;
  white-space: nowrap;
}
.current-session :deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}
.hint {
  color: var(--el-text-color-secondary);
}
</style>
