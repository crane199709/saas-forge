<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTenantClient } from '@crane199709/sdk'
import { ErrorAlert, PageHeader } from '@crane199709/ui'
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
    <PageHeader :title="t('securitySettings.title')" />
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <div v-if="mfaRequired !== null">
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
    </div>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.security-settings {
  max-width: 640px;
}
.row {
  display: flex;
  gap: var(--forge-space-4);
  align-items: center;
}
.hint {
  margin: var(--forge-space-2) 0 0;
  color: var(--el-text-color-secondary);
}
</style>
