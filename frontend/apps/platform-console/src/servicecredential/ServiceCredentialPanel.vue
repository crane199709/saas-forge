<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ErrorAlert,
  PageHeader,
  PageToolbar,
  formatDateTime,
  overlaySize,
  type ErrorTip,
} from '@crane199709/ui'
import { errorTip } from '../error-tip'
import type {
  ServiceCredential,
  ServiceCredentialApi,
  IssuedServiceCredential,
} from './service-credential-api'
const props = defineProps<{ api: ServiceCredentialApi }>()
const { t } = useI18n()
const rows = ref<ServiceCredential[]>([])
const loading = ref(false)
const available = ref(true)
const error = ref<ErrorTip | null>(null)
const registering = ref(false)
const service = ref('')
const rotating = ref(false)
const issued = ref<IssuedServiceCredential | null>(null)
const submitting = ref(false)
const actionError = ref<ErrorTip | null>(null)
const invalidService = ref(false)
const copied = ref(false)
const target = ref<ServiceCredential | null>(null)
const snippet = computed(() => (issued.value ? `FORGE_MANIFEST_TOKEN=${issued.value.token}` : ''))
async function load() {
  loading.value = true
  error.value = null
  try {
    const result = await props.api.list()
    available.value = result.available
    rows.value = result.credentials
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    loading.value = false
  }
}
function register(row?: ServiceCredential) {
  service.value = row?.service ?? ''
  rotating.value = !!row
  issued.value = null
  copied.value = false
  actionError.value = null
  invalidService.value = false
  registering.value = true
}
function close() {
  issued.value = null
  registering.value = false
  copied.value = false
}
async function create() {
  if (submitting.value) return
  invalidService.value = !/^[a-z][a-z0-9-]{0,99}$/.test(service.value)
  if (invalidService.value) return
  submitting.value = true
  actionError.value = null
  try {
    issued.value = await props.api.create(service.value)
    await load()
  } catch (cause) {
    actionError.value = errorTip(cause, t)
  } finally {
    submitting.value = false
  }
}
async function copy() {
  try {
    await navigator.clipboard.writeText(snippet.value)
    copied.value = true
  } catch {
    actionError.value = { message: t('serviceCredential.copyFailed') }
  }
}
function confirmDisable(row: ServiceCredential) {
  target.value = row
  actionError.value = null
}
async function disable() {
  if (!target.value || submitting.value) return
  submitting.value = true
  actionError.value = null
  try {
    await props.api.disable(target.value.id)
    target.value = null
    await load()
  } catch (cause) {
    actionError.value = errorTip(cause, t)
  } finally {
    submitting.value = false
  }
}
onMounted(load)
</script>
<template>
  <div>
    <PageHeader :title="t('serviceCredential.title')" />
    <PageToolbar>
      <el-button :loading="loading" @click="load">{{ t('serviceCredential.refresh') }}</el-button>
      <template #actions
        ><el-button type="primary" :disabled="!available || loading" @click="register()">{{
          t('serviceCredential.register')
        }}</el-button></template
      >
    </PageToolbar>
    <ErrorAlert v-if="error" :tip="error" />
    <el-alert
      v-if="!available"
      :title="t('serviceCredential.unavailable')"
      type="warning"
      :closable="false"
      show-icon
    />
    <el-table
      v-else
      v-loading="loading"
      :data="rows"
      :empty-text="t('serviceCredential.empty')"
      :aria-busy="loading"
    >
      <el-table-column
        prop="service"
        :label="t('serviceCredential.service')"
        min-width="220"
        show-overflow-tooltip
      />
      <el-table-column :label="t('serviceCredential.status')" width="90"
        ><template #default="{ row }"
          ><el-tag :type="row.status === 'active' ? 'success' : 'info'">{{
            t(`serviceCredential.${row.status}`)
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column
        prop="fingerprint"
        :label="t('serviceCredential.fingerprint')"
        min-width="110"
      />
      <el-table-column :label="t('serviceCredential.lastUsedAt')" min-width="170"
        ><template #default="{ row }">{{
          row.lastUsedAt ? formatDateTime(row.lastUsedAt) : '—'
        }}</template></el-table-column
      >
      <el-table-column :label="t('serviceCredential.createdAt')" min-width="170"
        ><template #default="{ row }">{{
          formatDateTime(row.createdAt)
        }}</template></el-table-column
      >
      <el-table-column :label="t('serviceCredential.actions')" fixed="right" width="140"
        ><template #default="{ row }"
          ><template v-if="row.status === 'active'"
            ><el-button link type="primary" @click="register(row as ServiceCredential)">{{
              t('serviceCredential.rotate')
            }}</el-button
            ><el-button link type="danger" @click="confirmDisable(row as ServiceCredential)">{{
              t('serviceCredential.disable')
            }}</el-button></template
          ><span v-else>—</span></template
        ></el-table-column
      >
    </el-table>
    <el-dialog
      v-model="registering"
      :title="t('serviceCredential.register')"
      :width="overlaySize.dialogMd"
      destroy-on-close
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      @close="close"
    >
      <ErrorAlert v-if="actionError" :tip="actionError" />
      <template v-if="issued">
        <el-alert :title="t('serviceCredential.once')" type="warning" :closable="false" show-icon />
        <el-alert
          v-if="!issued.manifestReported"
          :title="t('serviceCredential.unreported')"
          type="info"
          :closable="false"
          show-icon
          class="credential-block"
        />
        <pre data-testid="service-credential-token" class="credential-token">{{ snippet }}</pre>
        <el-button @click="copy">{{
          t(copied ? 'serviceCredential.copied' : 'serviceCredential.copy')
        }}</el-button>
      </template>
      <template v-else>
        <el-alert
          v-if="rotating"
          :title="t('serviceCredential.rotationHint')"
          type="info"
          :closable="false"
          show-icon
          class="credential-block"
        />
        <el-form label-position="top" @submit.prevent="create"
          ><el-form-item
            :label="t('serviceCredential.service')"
            for="credential-service"
            :error="invalidService ? t('serviceCredential.invalidService') : undefined"
            ><el-input
              id="credential-service"
              v-model="service"
              maxlength="100"
              placeholder="orders-service"
              :disabled="submitting" /></el-form-item
        ></el-form>
      </template>
      <template #footer>
        <el-button v-if="issued" type="primary" @click="close">{{
          t('serviceCredential.saved')
        }}</el-button>
        <template v-else
          ><el-button :disabled="submitting" @click="close">{{
            t('serviceCredential.cancel')
          }}</el-button
          ><el-button type="primary" :loading="submitting" @click="create">{{
            t('serviceCredential.submit')
          }}</el-button></template
        >
      </template>
    </el-dialog>
    <el-dialog
      :model-value="!!target"
      :title="t('serviceCredential.confirmDisable')"
      :width="overlaySize.dialogSm"
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      @update:model-value="
        (value) => {
          if (!value) target = null
        }
      "
    >
      <ErrorAlert v-if="actionError" :tip="actionError" />
      <el-alert
        :title="
          t('serviceCredential.disableHint', {
            service: target?.service,
            fingerprint: target?.fingerprint,
          })
        "
        type="warning"
        :closable="false"
        show-icon
      />
      <template #footer
        ><el-button :disabled="submitting" @click="target = null">{{
          t('serviceCredential.cancel')
        }}</el-button
        ><el-button type="danger" :loading="submitting" @click="disable">{{
          t('serviceCredential.confirmDisable')
        }}</el-button></template
      >
    </el-dialog>
  </div>
</template>
<style scoped>
.credential-block {
  margin-top: var(--forge-space-3);
}
.credential-token {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: var(--forge-space-3);
  background: var(--el-fill-color-light);
  border-radius: var(--forge-radius-sm);
  font-size: var(--forge-font-size-sm);
}
</style>
