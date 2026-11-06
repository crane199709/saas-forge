<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import QRCode from 'qrcode'
import type { RecoveryCodes } from '@crane199709/sdk'
import { RecoveryCodeList } from '@crane199709/ui'

/**
 * 绑定或更换验证器的后半程：扫码、提交首个动态码、保存恢复码。登录中与登录后走不同接口，由调用方提供。
 * 保存恢复码后才发出 `bound`，避免调用方随即跳转使恢复码来不及保存。
 */
const props = defineProps<{
  otpauthUri: string
  confirm: (code: string) => Promise<RecoveryCodes>
}>()
const emit = defineEmits<{
  bound: []
  error: [cause: unknown]
  // 提交前通知父组件清除上一次的错误提示。
  submitting: []
}>()
const { t } = useI18n()
const qr = ref('')
const secret = new URL(props.otpauthUri).searchParams.get('secret') ?? ''
const code = ref('')
const busy = ref(false)
const recoveryCodes = ref<string[] | null>(null)

onMounted(async () => {
  try {
    // SVG 渲染不依赖 canvas；以 data URL 作为图片展示，不插入 HTML。
    const svg = await QRCode.toString(props.otpauthUri, { type: 'svg', margin: 1 })
    qr.value = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
  } catch (cause) {
    emit('error', cause)
  }
})

async function submit() {
  emit('submitting')
  busy.value = true
  try {
    recoveryCodes.value = (await props.confirm(code.value.trim())).recoveryCodes
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
    <p>
      {{ t('mfa.secret') }}：<code data-testid="mfa-secret">{{ secret }}</code>
    </p>
    <el-form label-position="top" @submit.prevent="submit">
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
      <el-button native-type="submit" type="primary" :loading="busy">{{
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
