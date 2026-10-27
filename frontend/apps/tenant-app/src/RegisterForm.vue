<script setup lang="ts">
import { inject, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@forge/sdk'
import SendCodeButton from './SendCodeButton.vue'

const emit = defineEmits<{
  registered: [account: string, password: string]
  error: [cause: unknown]
  // 提交前通知父组件清除上一次的错误提示。
  submitting: []
}>()
const { t } = useI18n()
const providedClient = inject(tenantClientKey)
if (!providedClient) throw new Error('Shell must provide a TenantClient')
const client = providedClient
const account = ref('')
const password = ref('')
const displayName = ref('')
const target = ref('')
const code = ref('')
const busy = ref(false)

async function submit() {
  emit('submitting')
  busy.value = true
  try {
    await client.register({
      account: account.value,
      password: password.value,
      target: target.value,
      code: code.value,
      ...(displayName.value ? { displayName: displayName.value } : {}),
    })
    // 注册不建立登录会话，交给父组件用同一账号密码登录，结果与普通登录一致。
    emit('registered', account.value, password.value)
  } catch (cause) {
    emit('error', cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-form label-position="top" @submit.prevent="submit">
    <el-form-item :label="t('register.account')" for="register-account">
      <el-input
        id="register-account"
        v-model="account"
        name="register-account"
        autocomplete="username"
        required
      />
    </el-form-item>
    <el-form-item :label="t('register.password')" for="register-password">
      <el-input
        id="register-password"
        v-model="password"
        name="register-password"
        type="password"
        autocomplete="new-password"
        minlength="8"
        required
        show-password
      />
    </el-form-item>
    <el-form-item :label="t('register.displayName')" for="register-display-name">
      <el-input id="register-display-name" v-model="displayName" name="register-display-name" />
    </el-form-item>
    <el-form-item :label="t('code.target')" for="register-target">
      <el-input id="register-target" v-model="target" name="register-target" required />
    </el-form-item>
    <el-form-item :label="t('code.code')" for="register-code">
      <div class="code-row">
        <el-input
          id="register-code"
          v-model="code"
          name="register-code"
          autocomplete="one-time-code"
          inputmode="numeric"
          required
        />
        <SendCodeButton
          :target="target"
          :send="client.sendRegistrationCode"
          @error="emit('error', $event)"
        />
      </div>
    </el-form-item>
    <el-button native-type="submit" type="primary" :loading="busy">{{
      t('register.submit')
    }}</el-button>
  </el-form>
</template>

<style scoped>
.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
</style>
