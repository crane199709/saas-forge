<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { CodeDelivery } from '@forge/sdk'

// 只负责发送与倒计时；失败交给父组件按统一方式展示。
const props = defineProps<{ target: string; send: (target: string) => Promise<CodeDelivery> }>()
const emit = defineEmits<{ error: [cause: unknown] }>()
const { t } = useI18n()
const sending = ref(false)
const remaining = ref(0)
let timer: ReturnType<typeof setInterval> | undefined

function stop() {
  if (timer) clearInterval(timer)
  timer = undefined
}
async function click() {
  sending.value = true
  try {
    const delivery = await props.send(props.target)
    // 倒计时以服务端返回的发送间隔为准，与限频规则保持一致。
    remaining.value = delivery.resendIn
    stop()
    timer = setInterval(() => {
      remaining.value -= 1
      if (remaining.value <= 0) stop()
    }, 1000)
  } catch (cause) {
    emit('error', cause)
  } finally {
    sending.value = false
  }
}
onUnmounted(stop)
</script>

<template>
  <el-button
    data-testid="send-code"
    :loading="sending"
    :disabled="!target || remaining > 0"
    @click="click"
    >{{ remaining > 0 ? t('code.resendIn', { seconds: remaining }) : t('code.send') }}</el-button
  >
</template>
