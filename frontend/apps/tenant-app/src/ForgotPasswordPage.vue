<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTenantClient, type PasswordResetTicket } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
import AuthCard from './AuthCard.vue'
import { errorTip, type ErrorTip } from './error-tip'
import SendCodeButton from './SendCodeButton.vue'

const { t } = useI18n()
const client = useTenantClient()
const target = ref('')
const code = ref('')
const password = ref('')
const mfaCode = ref('')
const recoveryCode = ref('')
const useRecovery = ref(false)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
// 验证码校验通过后得到的一次性凭证；新密码或 MFA 不通过时仍可在有效期内重试，过期则回到第一步。
const ticket = ref<PasswordResetTicket | null>(null)
const done = ref(false)

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

function verify() {
  return run(async () => {
    ticket.value = await client.verifyPasswordReset(target.value, code.value)
  })
}

function reset() {
  return run(async () => {
    const current = ticket.value!
    try {
      await client.resetPassword({
        resetToken: current.resetToken,
        password: password.value,
        ...(current.mfaRequired
          ? useRecovery.value
            ? { recoveryCode: recoveryCode.value.trim() }
            : { code: mfaCode.value.trim() }
          : {}),
      })
    } catch (cause) {
      if (cause instanceof Error && cause.message === 'password.resetExpired') {
        ticket.value = null
        code.value = ''
      }
      throw cause
    }
    done.value = true
  })
}
</script>

<template>
  <AuthCard :title="t('forgot.title')">
    <ErrorAlert v-if="error" :tip="error" />
    <template v-if="done">
      <el-alert type="success" :closable="false" :title="t('forgot.done')" />
    </template>
    <el-form v-else-if="ticket" label-position="top" @submit.prevent="reset">
      <el-form-item :label="t('forgot.password')" for="new-password">
        <el-input
          id="new-password"
          v-model="password"
          name="new-password"
          type="password"
          autocomplete="new-password"
          required
          show-password
        />
      </el-form-item>
      <template v-if="ticket.mfaRequired">
        <p>{{ t('forgot.mfaHint') }}</p>
        <el-form-item v-if="useRecovery" :label="t('mfa.recoveryCode')" for="recovery-code">
          <el-input
            id="recovery-code"
            v-model="recoveryCode"
            name="recovery-code"
            autocomplete="off"
            required
          />
        </el-form-item>
        <el-form-item v-else :label="t('mfa.code')" for="mfa-code">
          <el-input
            id="mfa-code"
            v-model="mfaCode"
            name="mfa-code"
            autocomplete="one-time-code"
            inputmode="numeric"
            maxlength="6"
            required
          />
        </el-form-item>
        <el-button link data-testid="use-recovery-code" @click="useRecovery = !useRecovery">{{
          useRecovery ? t('mfa.useCode') : t('mfa.useRecoveryCode')
        }}</el-button>
      </template>
      <div class="actions">
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('forgot.submit')
        }}</el-button>
      </div>
    </el-form>
    <el-form v-else label-position="top" @submit.prevent="verify">
      <el-form-item :label="t('forgot.target')" for="reset-target">
        <el-input
          id="reset-target"
          v-model="target"
          name="reset-target"
          autocomplete="username"
          required
        />
      </el-form-item>
      <el-form-item :label="t('forgot.code')" for="reset-code">
        <div class="code-row">
          <el-input
            id="reset-code"
            v-model="code"
            name="reset-code"
            autocomplete="one-time-code"
            inputmode="numeric"
            required
          />
          <SendCodeButton
            :target="target"
            :send="client.sendPasswordResetCode"
            @error="showError"
          />
        </div>
      </el-form-item>
      <el-button native-type="submit" type="primary" :loading="busy">{{
        t('forgot.next')
      }}</el-button>
    </el-form>
    <p class="back">
      <router-link to="/login">{{ t('auth.backToLogin') }}</router-link>
    </p>
  </AuthCard>
</template>

<style scoped>
.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.actions {
  margin-top: 16px;
}
.back {
  margin-top: 16px;
}
</style>
