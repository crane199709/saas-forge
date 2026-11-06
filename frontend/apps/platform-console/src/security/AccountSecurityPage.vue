<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@crane199709/sdk'
import { ErrorAlert, RecoveryCodeList, type ErrorTip } from '@crane199709/ui'
import { errorTip } from '../error-tip'
import { usePlatformClient } from '../shell'
import { createAccountSecurityApi } from './account-security-api'
import MfaBindForm from './MfaBindForm.vue'

const { t } = useI18n()
const api = createAccountSecurityApi(usePlatformClient())
const error = ref<ErrorTip | null>(null)
const busy = ref(false)

const currentPassword = ref('')
const newPassword = ref('')
const passwordChanged = ref(false)

// 更换验证器的步骤：起点 → 已绑定时验证旧动态码 → 扫码确认。
const step = ref<'idle' | 'verify' | 'bind'>('idle')
const replaceCode = ref('')
const otpauthUri = ref('')
const mfaUpdated = ref(false)

const regenerateCode = ref('')
const recoveryCodes = ref<string[] | null>(null)

const hasCode = (cause: unknown, code: string) => cause instanceof ApiError && cause.code === code

async function run(task: () => Promise<void>) {
  busy.value = true
  error.value = null
  try {
    await task()
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}

function changePassword() {
  passwordChanged.value = false
  return run(async () => {
    await api.changePassword(currentPassword.value, newPassword.value)
    currentPassword.value = ''
    newPassword.value = ''
    passwordChanged.value = true
  })
}

// 接口不提供绑定状态查询，先尝试开始绑定：未绑定（如被重置后）直接扫码，已绑定时才需要旧动态码。
function startChange() {
  mfaUpdated.value = false
  return run(async () => {
    try {
      otpauthUri.value = (await api.startBinding()).otpauthUri
      step.value = 'bind'
    } catch (cause) {
      if (!hasCode(cause, 'mfa.alreadyBound')) throw cause
      step.value = 'verify'
    }
  })
}

function startReplacement() {
  return run(async () => {
    otpauthUri.value = (await api.startReplacement(replaceCode.value.trim())).otpauthUri
    replaceCode.value = ''
    step.value = 'bind'
  })
}

function bindFailed(cause: unknown) {
  error.value = errorTip(cause, t)
  // 进行中的绑定已失效，这张二维码不能再用。
  if (hasCode(cause, 'mfa.bindingExpired')) step.value = 'idle'
}

function bound() {
  step.value = 'idle'
  mfaUpdated.value = true
}

function cancelChange() {
  step.value = 'idle'
  replaceCode.value = ''
  error.value = null
}

function regenerate() {
  return run(async () => {
    recoveryCodes.value = (
      await api.regenerateRecoveryCodes(regenerateCode.value.trim())
    ).recoveryCodes
    regenerateCode.value = ''
  })
}
</script>

<template>
  <section class="account-security" :aria-label="t('account.title')">
    <h2>{{ t('account.title') }}</h2>
    <ErrorAlert v-if="error" :tip="error" />

    <el-card>
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
    </el-card>

    <el-card data-testid="mfa-section">
      <h3>{{ t('account.mfa.title') }}</h3>
      <template v-if="step === 'bind'">
        <MfaBindForm
          :otpauth-uri="otpauthUri"
          :confirm="api.confirmBinding"
          @bound="bound"
          @submitting="error = null"
          @error="bindFailed"
        />
        <el-button link @click="cancelChange">{{ t('account.mfa.cancel') }}</el-button>
      </template>
      <el-form
        v-else-if="step === 'verify'"
        label-position="top"
        data-testid="replace-form"
        @submit.prevent="startReplacement"
      >
        <p>{{ t('account.mfa.verifyHint') }}</p>
        <el-form-item :label="t('account.mfa.code')" for="replace-code">
          <el-input
            id="replace-code"
            v-model="replaceCode"
            name="replace-code"
            autocomplete="one-time-code"
            inputmode="numeric"
            maxlength="6"
            required
          />
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('account.mfa.next')
        }}</el-button>
        <el-button link @click="cancelChange">{{ t('account.mfa.cancel') }}</el-button>
      </el-form>
      <template v-else>
        <el-alert
          v-if="mfaUpdated"
          type="success"
          :closable="false"
          :title="t('account.mfa.updated')"
        />
        <p>{{ t('account.mfa.changeHint') }}</p>
        <el-button data-testid="mfa-change" :loading="busy" @click="startChange">{{
          t('account.mfa.change')
        }}</el-button>
      </template>
    </el-card>

    <el-card>
      <h3>{{ t('account.recovery.title') }}</h3>
      <RecoveryCodeList v-if="recoveryCodes" :codes="recoveryCodes" @done="recoveryCodes = null" />
      <el-form
        v-else
        label-position="top"
        data-testid="regenerate-form"
        @submit.prevent="regenerate"
      >
        <p>{{ t('account.recovery.hint') }}</p>
        <el-form-item :label="t('account.mfa.code')" for="regenerate-code">
          <el-input
            id="regenerate-code"
            v-model="regenerateCode"
            name="regenerate-code"
            autocomplete="one-time-code"
            inputmode="numeric"
            maxlength="6"
            required
          />
        </el-form-item>
        <el-button native-type="submit" :loading="busy">{{
          t('account.recovery.submit')
        }}</el-button>
      </el-form>
    </el-card>
  </section>
</template>

<style scoped>
.account-security {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 640px;
}
</style>
