<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { MfaFactor } from '@crane199709/sdk'

// 登录第二步：动态码或恢复码二选一，提交与错误处理交给父组件，与 First Factor 走同一套进入逻辑。
defineProps<{ busy: boolean }>()
const emit = defineEmits<{ submit: [factor: MfaFactor]; cancel: [] }>()
const { t } = useI18n()
const useRecovery = ref(false)
const code = ref('')
const recoveryCode = ref('')

function submit() {
  emit(
    'submit',
    useRecovery.value ? { recoveryCode: recoveryCode.value.trim() } : { code: code.value.trim() },
  )
}
</script>

<template>
  <section :aria-label="t('mfa.title')">
    <h3>{{ t('mfa.title') }}</h3>
    <p>{{ useRecovery ? t('mfa.recoveryHint') : t('mfa.codeHint') }}</p>
    <el-form label-position="top" @submit.prevent="submit">
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
          v-model="code"
          name="mfa-code"
          autocomplete="one-time-code"
          inputmode="numeric"
          maxlength="6"
          required
        />
      </el-form-item>
      <div class="actions">
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('mfa.verify')
        }}</el-button>
        <el-button link data-testid="use-recovery-code" @click="useRecovery = !useRecovery">{{
          useRecovery ? t('mfa.useCode') : t('mfa.useRecoveryCode')
        }}</el-button>
        <el-button link @click="emit('cancel')">{{ t('auth.backToLogin') }}</el-button>
      </div>
    </el-form>
  </section>
</template>

<style scoped>
.actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
</style>
