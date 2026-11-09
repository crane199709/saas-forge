<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, overlaySize, type ErrorTip } from '@crane199709/ui'
import { errorTip } from '../error-tip'
import type { IdentityApi, IdentityLookup } from './identity-api'

const props = defineProps<{ api: IdentityApi }>()
const { t } = useI18n()
const identifier = ref('')
const identity = ref<IdentityLookup | null>(null)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const confirming = ref(false)
const resetDone = ref(false)

async function search() {
  busy.value = true
  error.value = null
  resetDone.value = false
  identity.value = null
  try {
    identity.value = await props.api.lookup(identifier.value.trim())
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}

async function reset() {
  const target = identity.value
  if (!target) return
  busy.value = true
  error.value = null
  try {
    await props.api.resetMfa(target.identityId)
    target.mfaBound = false
    resetDone.value = true
  } catch (cause) {
    error.value = errorTip(cause, t)
    // 页面展示的状态已过期：对方在此期间已解绑或被他人重置。
    if (cause instanceof ApiError && cause.code === 'mfa.notBound') target.mfaBound = false
  } finally {
    busy.value = false
    confirming.value = false
  }
}
</script>

<template>
  <section :aria-label="t('identity.title')">
    <PageHeader :title="t('identity.title')" :description="t('identity.hint')" />
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <el-form
      class="lookup"
      label-position="top"
      data-testid="identity-lookup"
      @submit.prevent="search"
    >
      <el-form-item :label="t('identity.identifier')" for="identity-identifier">
        <el-input
          id="identity-identifier"
          v-model="identifier"
          name="identifier"
          maxlength="254"
          required
        />
      </el-form-item>
      <el-button native-type="submit" type="primary" :loading="busy">{{
        t('identity.search')
      }}</el-button>
    </el-form>

    <el-descriptions v-if="identity" :column="1" border class="result">
      <el-descriptions-item :label="t('identity.displayName')">{{
        identity.displayName
      }}</el-descriptions-item>
      <el-descriptions-item :label="t('identity.mfa')">
        <el-tag :type="identity.mfaBound ? 'success' : 'info'" data-testid="identity-mfa-status">{{
          identity.mfaBound ? t('identity.bound') : t('identity.unbound')
        }}</el-tag>
        <el-button
          v-if="identity.mfaBound"
          link
          type="danger"
          class="reset"
          data-testid="identity-mfa-reset"
          @click="confirming = true"
          >{{ t('identity.reset') }}</el-button
        >
      </el-descriptions-item>
    </el-descriptions>
    <el-alert
      v-if="resetDone"
      class="result"
      type="success"
      :closable="false"
      :title="t('identity.resetDone')"
    />

    <el-dialog
      v-model="confirming"
      :width="overlaySize.dialogSm"
      :title="t('identity.resetTitle', { name: identity?.displayName ?? '' })"
    >
      <p>{{ t('identity.resetHint') }}</p>
      <template #footer>
        <el-button
          type="danger"
          :loading="busy"
          data-testid="identity-mfa-reset-confirm"
          @click="reset"
          >{{ t('identity.reset') }}</el-button
        >
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.lookup,
.result {
  max-width: 640px;
}
.result {
  margin-top: var(--forge-space-4);
}
.reset {
  margin-left: var(--forge-space-3);
}
</style>
