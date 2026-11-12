<script setup lang="ts">
import { computed, inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey, useAccessControl } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, PageToolbar, overlaySize } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import {
  createDataSecurityApi,
  type MaskingRule,
  type SensitiveField,
  type MaskingPreview,
} from './data-security-api'

const { t } = useI18n()
const client = inject(tenantClientKey)
if (!client) throw new Error('Shell must provide a TenantClient')
const api = createDataSecurityApi(client)
const access = useAccessControl()
const canManage = computed(() => access.has('iam:data-security:manage'))
const editing = ref<SensitiveField | null>(null)
const selectedRule = ref('')
const saving = ref(false)
const editError = ref<ErrorTip | null>(null)
const previewing = ref<SensitiveField | null>(null)
const sample = ref('')
const previewResult = ref<MaskingPreview | null>(null)
const previewError = ref<ErrorTip | null>(null)
const previewBusy = ref(false)
const fields = ref<SensitiveField[]>([])
const rules = ref<MaskingRule[]>([])
const error = ref<ErrorTip | null>(null)
const loading = ref(false)
const loaded = ref(false)
const ruleName = (code: string | null) =>
  code ? (rules.value.find((rule) => rule.code === code)?.name ?? code) : '—'
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = null
  try {
    ;[fields.value, rules.value] = await Promise.all([api.fields(), api.rules()])
    loaded.value = true
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    loading.value = false
  }
}
function edit(field: SensitiveField) {
  editing.value = field
  selectedRule.value = field.overrideRule ?? field.effectiveRule ?? ''
  editError.value = null
}
async function save() {
  if (saving.value || !editing.value || !selectedRule.value || !canManage.value) return
  saving.value = true
  editError.value = null
  try {
    const saved = await api.setRule(editing.value, selectedRule.value)
    fields.value = fields.value.map((field) =>
      field.service === saved.service && field.path === saved.path ? saved : field,
    )
    editing.value = null
  } catch (cause) {
    editError.value = errorTip(cause, t)
  } finally {
    saving.value = false
  }
}
async function reset(field: SensitiveField) {
  if (saving.value || !canManage.value) return
  saving.value = true
  error.value = null
  try {
    await api.resetRule(field)
    await load()
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    saving.value = false
  }
}
function clearPreview() {
  previewResult.value = null
  previewError.value = null
}
function preview(field: SensitiveField) {
  previewing.value = field
  sample.value = ''
  previewResult.value = null
  previewError.value = null
}
async function runPreview() {
  if (previewBusy.value || !previewing.value?.effectiveRule || !sample.value) return
  previewBusy.value = true
  previewResult.value = null
  previewError.value = null
  try {
    const result = await api.preview(previewing.value.effectiveRule, sample.value)
    if (result.success) previewResult.value = result
    else
      previewError.value = {
        message: t(
          `dataSecurity.previewErrors.${result.errorCode === 'field-masking.invalidRule' ? 'invalidRule' : result.errorCode === 'field-masking.unknownStrategy' ? 'unknownStrategy' : result.errorCode === 'field-masking.unchanged' ? 'unchanged' : 'unknown'}`,
        ),
      }
  } catch (cause) {
    previewError.value = errorTip(cause, t)
  } finally {
    previewBusy.value = false
  }
}
onMounted(load)
</script>
<template>
  <section>
    <PageHeader :title="t('dataSecurity.title')" />
    <ErrorAlert v-if="error" :tip="error" />
    <PageToolbar
      ><template #actions
        ><el-button
          :loading="loading"
          :disabled="saving"
          data-testid="data-security-reload"
          @click="load"
          >{{ t(error ? 'dataSecurity.retry' : 'dataSecurity.refresh') }}</el-button
        ></template
      ></PageToolbar
    >
    <el-table
      v-if="loaded"
      v-loading="loading"
      :data="fields"
      :empty-text="t('dataSecurity.empty')"
    >
      <el-table-column
        prop="service"
        :label="t('dataSecurity.service')"
        min-width="140"
        show-overflow-tooltip
      />
      <el-table-column
        prop="path"
        :label="t('dataSecurity.path')"
        min-width="220"
        show-overflow-tooltip
      />
      <el-table-column :label="t('dataSecurity.grade')" min-width="105"
        ><template #default="{ row }">{{
          t(`dataSecurity.grades.${row.grade}`)
        }}</template></el-table-column
      >
      <el-table-column
        :label="t('dataSecurity.effectiveRule')"
        min-width="130"
        show-overflow-tooltip
        ><template #default="{ row }"
          >{{ ruleName(row.effectiveRule)
          }}<el-tag v-if="row.overrideRule" size="small" type="info">{{
            t('dataSecurity.overridden')
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column :label="t('dataSecurity.status')" min-width="100"
        ><template #default="{ row }"
          ><el-tag :type="row.status === 'active' ? 'success' : 'danger'">{{
            t(`dataSecurity.statuses.${row.status}`)
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column :label="t('dataSecurity.export')" min-width="100"
        ><template #default="{ row }">{{
          t(row.exportAllowed ? 'dataSecurity.allowed' : 'dataSecurity.blocked')
        }}</template></el-table-column
      >
      <el-table-column :label="t('common.actions')" :width="canManage ? 235 : 80" fixed="right"
        ><template #default="{ row }"
          ><el-button
            v-if="canManage"
            link
            type="primary"
            data-testid="field-edit"
            :disabled="saving"
            @click="edit(row as SensitiveField)"
            >{{ t('dataSecurity.setRule') }}</el-button
          ><el-button
            v-if="canManage && row.overrideRule"
            link
            data-testid="field-reset"
            :disabled="saving"
            @click="reset(row as SensitiveField)"
            >{{ t('dataSecurity.reset') }}</el-button
          ><el-button
            link
            data-testid="field-preview"
            :disabled="!row.effectiveRule"
            @click="preview(row as SensitiveField)"
            >{{ t('dataSecurity.preview') }}</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <el-dialog
      :model-value="!!editing"
      :title="t('dataSecurity.setRule')"
      :width="overlaySize.dialogSm"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      :before-close="
        (done: () => void) => {
          if (!saving) done()
        }
      "
      @close="editing = null"
    >
      <ErrorAlert v-if="editError" :tip="editError" class="dialog-error" />
      <el-form label-position="top" :disabled="saving" @submit.prevent="save">
        <el-form-item :label="t('dataSecurity.path')"
          ><span class="field-path">{{ editing?.path }}</span></el-form-item
        >
        <el-form-item :label="t('dataSecurity.rule')"
          ><el-select
            v-model="selectedRule"
            data-testid="field-rule"
            :aria-label="t('dataSecurity.rule')"
            filterable
            ><el-option
              v-for="rule in rules"
              :key="rule.code"
              :value="rule.code"
              :label="`${rule.name}（${rule.code}）`" /></el-select
        ></el-form-item>
      </el-form>
      <template #footer
        ><el-button :disabled="saving" @click="editing = null">{{
          t('dataSecurity.cancel')
        }}</el-button
        ><el-button
          type="primary"
          :loading="saving"
          :disabled="!selectedRule"
          data-testid="field-save"
          @click="save"
          >{{ t('dataSecurity.save') }}</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      :model-value="!!previewing"
      :title="t('dataSecurity.previewTitle')"
      :width="overlaySize.dialogSm"
      :close-on-click-modal="false"
      :close-on-press-escape="!previewBusy"
      :show-close="!previewBusy"
      :before-close="
        (done: () => void) => {
          if (!previewBusy) done()
        }
      "
      @close="previewing = null"
    >
      <el-form label-position="top" @submit.prevent="runPreview">
        <el-form-item :label="t('dataSecurity.rule')">{{
          ruleName(previewing?.effectiveRule ?? null)
        }}</el-form-item>
        <el-form-item :label="t('dataSecurity.sample')"
          ><el-input
            v-model="sample"
            :disabled="previewBusy"
            data-testid="preview-value"
            :aria-label="t('dataSecurity.sample')"
            :maxlength="1000"
            @input="clearPreview"
        /></el-form-item>
      </el-form>
      <ErrorAlert v-if="previewError" :tip="previewError" data-testid="preview-error" />
      <el-alert
        v-if="previewResult"
        :title="t('dataSecurity.result')"
        type="success"
        :closable="false"
        ><output data-testid="preview-result" class="preview-result">{{
          previewResult.value ?? '—'
        }}</output></el-alert
      >
      <template #footer
        ><el-button :disabled="previewBusy" @click="previewing = null">{{
          t('dataSecurity.close')
        }}</el-button
        ><el-button
          type="primary"
          :loading="previewBusy"
          :disabled="!sample"
          data-testid="preview-run"
          @click="runPreview"
          >{{ t('dataSecurity.preview') }}</el-button
        ></template
      >
    </el-dialog>
  </section>
</template>

<style scoped>
.dialog-error {
  margin-bottom: var(--forge-space-3);
}
.preview-result,
.field-path {
  overflow-wrap: anywhere;
}
.el-tag {
  margin-left: var(--forge-space-2);
}
</style>
