<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import QRCode from 'qrcode'
import { useTenantClient } from '@crane199709/sdk'
import RecoveryCodeList from './RecoveryCodeList.vue'

/**
 * 绑定验证器的三步：扫码、提交首个动态码、保存恢复码。已登录时凭 Access Token、尚无 Token 时凭登录会话调用，由 SDK 选择。
 * 保存恢复码后才发出 `bound`，避免调用方随即跳转使恢复码来不及保存。
 */
const emit = defineEmits<{
  bound: []
  error: [cause: unknown]
  // 提交前通知父组件清除上一次的错误提示。
  submitting: []
}>()
const { t } = useI18n()
const client = useTenantClient()
const qr = ref('')
const secret = ref('')
const code = ref('')
const busy = ref(false)
const recoveryCodes = ref<string[] | null>(null)

onMounted(async () => {
  try {
    const { otpauthUri } = await client.startMfaBinding()
    secret.value = new URL(otpauthUri).searchParams.get('secret') ?? ''
    // SVG 渲染不依赖 canvas；以 data URL 作为图片展示，不插入 HTML。
    const svg = await QRCode.toString(otpauthUri, { type: 'svg', margin: 1 })
    qr.value = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
  } catch (cause) {
    emit('error', cause)
  }
})

async function confirm() {
  emit('submitting')
  busy.value = true
  try {
    recoveryCodes.value = (await client.confirmMfaBinding(code.value)).recoveryCodes
  } catch (cause) {
    emit('error', cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <RecoveryCodeList v-if="recoveryCodes" :codes="recoveryCodes" @done="emit('bound')" />
  <section v-else :aria-label="t('mfa.bindTitle')">
    <p>{{ t('mfa.bindScan') }}</p>
    <img v-if="qr" :src="qr" :alt="t('mfa.qrAlt')" class="qr" data-testid="mfa-qr" />
    <p v-if="secret">
      {{ t('mfa.secret') }}：<code data-testid="mfa-secret">{{ secret }}</code>
    </p>
    <el-form label-position="top" @submit.prevent="confirm">
      <el-form-item :label="t('mfa.bindCode')" for="bind-code">
        <el-input
          id="bind-code"
          v-model="code"
          name="bind-code"
          autocomplete="one-time-code"
          inputmode="numeric"
          maxlength="6"
          required
        />
      </el-form-item>
      <el-button native-type="submit" type="primary" :loading="busy" :disabled="!secret">{{
        t('mfa.bindConfirm')
      }}</el-button>
    </el-form>
  </section>
</template>

<style scoped>
.qr {
  width: 180px;
  height: 180px;
}
</style>
