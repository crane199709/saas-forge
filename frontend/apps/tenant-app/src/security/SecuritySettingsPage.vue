<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTenantClient } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import { createSecurityApi } from './security-api'

const { t } = useI18n()
const api = createSecurityApi(useTenantClient())
const mfaRequired = ref<boolean | null>(null)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

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

onMounted(() =>
  run(async () => {
    mfaRequired.value = (await api.get()).mfaRequired
  }),
)

// 开关以服务端返回的值为准，保存失败时保持原状态。
function toggle(next: string | number | boolean) {
  return run(async () => {
    mfaRequired.value = (await api.update({ mfaRequired: next === true })).mfaRequired
  })
}
</script>

<template>
  <section class="security-settings" :aria-label="t('securitySettings.title')">
    <h2>{{ t('securitySettings.title') }}</h2>
    <ErrorAlert v-if="error" :tip="error" />
    <el-card v-if="mfaRequired !== null">
      <div class="row">
        <span>{{ t('securitySettings.mfaRequired') }}</span>
        <el-switch
          :model-value="mfaRequired"
          :loading="busy"
          data-testid="mfa-required"
          @update:model-value="toggle"
        />
      </div>
      <p class="hint">{{ t('securitySettings.mfaRequiredHint') }}</p>
    </el-card>
  </section>
</template>

<style scoped>
.security-settings {
  max-width: 640px;
}
.row {
  display: flex;
  gap: 16px;
  align-items: center;
}
.hint {
  color: var(--el-text-color-secondary);
}
</style>
