<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, PageHeader, PageToolbar, overlaySize } from '@crane199709/ui'
import { permissions } from '../access'
import { useConsoleAccess, usePlatformClient } from '../shell'
import { errorTip } from '../error-tip'
import type { ErrorTip } from '@crane199709/ui'
import {
  createDataSecurityApi,
  type MaskingRule,
  type DataGrade,
  type Strategy,
  type MaskingPreview,
} from './data-security-api'

const { t } = useI18n()
const api = createDataSecurityApi(usePlatformClient())
const access = useConsoleAccess()
const canManage = computed(() => access.has(permissions.dataSecurityManage))
const previewing = ref<MaskingRule | null>(null)
const sample = ref('')
const previewResult = ref<MaskingPreview | null>(null)
const previewError = ref<ErrorTip | null>(null)
const previewBusy = ref(false)
const deleting = ref<MaskingRule | null>(null)
const deleteError = ref<ErrorTip | null>(null)
const editingGrade = ref<DataGrade | null>(null)
const gradeRule = ref('')
const gradeError = ref<ErrorTip | null>(null)
const editing = ref(false)
const existingCode = ref<string | null>(null)
const draft = ref<MaskingRule>({
  code: '',
  name: '',
  rule: { strategy: 'phone', keepFirst: null, replacement: null },
})
const saving = ref(false)
const editError = ref<ErrorTip | null>(null)
const strategies: Strategy[] = [
  'mask_middle',
  'mask_tail4',
  'keepFirst',
  'replace',
  'email',
  'phone',
  'idcard',
  'bankcard',
  'name',
]
const valid = computed(
  () =>
    !!draft.value.code.trim() &&
    !!draft.value.name.trim() &&
    (draft.value.rule.strategy !== 'keepFirst' ||
      (Number.isInteger(draft.value.rule.keepFirst) && draft.value.rule.keepFirst! >= 0)) &&
    (draft.value.rule.strategy !== 'replace' || !!draft.value.rule.replacement?.trim()),
)
const tab = ref('rules')
const rules = ref<MaskingRule[]>([])
const grades = ref<DataGrade[]>([])
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
    ;[rules.value, grades.value] = await Promise.all([api.rules(), api.grades()])
    loaded.value = true
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    loading.value = false
  }
}
function edit(rule?: MaskingRule) {
  existingCode.value = rule?.code ?? null
  draft.value = rule
    ? { ...rule, rule: { ...rule.rule } }
    : { code: '', name: '', rule: { strategy: 'phone', keepFirst: null, replacement: null } }
  editError.value = null
  editing.value = true
}
function setStrategy(strategy: Strategy) {
  draft.value.rule = {
    strategy,
    keepFirst: strategy === 'keepFirst' ? 0 : null,
    replacement: strategy === 'replace' ? '' : null,
  }
}
async function save() {
  if (saving.value || !canManage.value || !valid.value) return
  saving.value = true
  editError.value = null
  try {
    const value = { ...draft.value, code: draft.value.code.trim(), name: draft.value.name.trim() }
    const saved = await (existingCode.value ? api.update(value) : api.create(value))
    const index = rules.value.findIndex((rule) => rule.code === saved.code)
    if (index < 0) rules.value.push(saved)
    else rules.value[index] = saved
    editing.value = false
  } catch (cause) {
    editError.value = errorTip(cause, t)
  } finally {
    saving.value = false
  }
}
function editGrade(grade: DataGrade) {
  editingGrade.value = grade
  gradeRule.value = grade.defaultRule
  gradeError.value = null
}
async function saveGrade() {
  if (saving.value || !canManage.value || !editingGrade.value || !gradeRule.value) return
  saving.value = true
  gradeError.value = null
  try {
    const saved = await api.setGrade(editingGrade.value.grade, gradeRule.value)
    grades.value = grades.value.map((grade) => (grade.grade === saved.grade ? saved : grade))
    editingGrade.value = null
  } catch (cause) {
    gradeError.value = errorTip(cause, t)
  } finally {
    saving.value = false
  }
}
function askDelete(rule: MaskingRule) {
  deleting.value = rule
  deleteError.value = null
}
async function remove() {
  if (saving.value || !canManage.value || !deleting.value) return
  saving.value = true
  deleteError.value = null
  try {
    const code = deleting.value.code
    await api.remove(code)
    rules.value = rules.value.filter((rule) => rule.code !== code)
    deleting.value = null
  } catch (cause) {
    deleteError.value = errorTip(cause, t)
  } finally {
    saving.value = false
  }
}
function clearPreview() {
  previewResult.value = null
  previewError.value = null
}
function preview(rule: MaskingRule) {
  previewing.value = rule
  sample.value = ''
  previewResult.value = null
  previewError.value = null
}
async function runPreview() {
  if (previewBusy.value || !previewing.value || !sample.value) return
  previewBusy.value = true
  previewResult.value = null
  previewError.value = null
  try {
    const result = await api.preview(previewing.value.code, sample.value)
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
    <el-tabs v-model="tab">
      <el-tab-pane :label="t('dataSecurity.rules')" name="rules">
        <PageToolbar
          ><template #actions
            ><el-button
              :disabled="saving"
              :loading="loading"
              data-testid="data-security-reload"
              @click="load"
              >{{ t(error ? 'dataSecurity.retry' : 'dataSecurity.refresh') }}</el-button
            ><el-button
              v-if="canManage"
              type="primary"
              data-testid="rule-create"
              :disabled="saving || !loaded"
              @click="edit()"
              >{{ t('dataSecurity.createRule') }}</el-button
            ></template
          ></PageToolbar
        >
        <el-table v-if="loaded" :data="rules" :empty-text="t('dataSecurity.noRules')">
          <el-table-column
            prop="name"
            :label="t('dataSecurity.rule')"
            min-width="200"
            show-overflow-tooltip
          />
          <el-table-column
            prop="code"
            :label="t('dataSecurity.ruleCode')"
            min-width="180"
            show-overflow-tooltip
          />
          <el-table-column :label="t('dataSecurity.strategy')" min-width="160"
            ><template #default="{ row }">{{
              t(`dataSecurity.strategies.${row.rule.strategy}`)
            }}</template></el-table-column
          >
          <el-table-column
            :label="t('dataSecurity.actions')"
            :width="canManage ? 220 : 90"
            fixed="right"
            ><template #default="{ row }"
              ><el-button
                v-if="canManage"
                link
                type="primary"
                data-testid="rule-edit"
                :disabled="saving"
                @click="edit(row as MaskingRule)"
                >{{ t('dataSecurity.edit') }}</el-button
              ><el-button
                v-if="canManage"
                link
                type="danger"
                data-testid="rule-delete"
                :disabled="saving"
                @click="askDelete(row as MaskingRule)"
                >{{ t('dataSecurity.delete') }}</el-button
              ><el-button link data-testid="rule-preview" @click="preview(row as MaskingRule)">{{
                t('dataSecurity.preview')
              }}</el-button></template
            ></el-table-column
          >
        </el-table>
      </el-tab-pane>
      <el-tab-pane :label="t('dataSecurity.gradesTitle')" name="grades">
        <PageToolbar
          ><template #actions
            ><el-button :loading="loading" @click="load">{{
              t(error ? 'dataSecurity.retry' : 'dataSecurity.refresh')
            }}</el-button></template
          ></PageToolbar
        >
        <el-table v-if="loaded" :data="grades" :empty-text="t('dataSecurity.noGrades')">
          <el-table-column :label="t('dataSecurity.grade')" min-width="160"
            ><template #default="{ row }">{{
              t(`dataSecurity.grades.${row.grade}`)
            }}</template></el-table-column
          >
          <el-table-column
            :label="t('dataSecurity.defaultRule')"
            min-width="220"
            show-overflow-tooltip
            ><template #default="{ row }">{{
              ruleName(row.defaultRule)
            }}</template></el-table-column
          >
          <el-table-column :label="t('dataSecurity.export')" min-width="120"
            ><template #default="{ row }">{{
              t(row.exportAllowed ? 'dataSecurity.allowed' : 'dataSecurity.blocked')
            }}</template></el-table-column
          >
          <el-table-column
            v-if="canManage"
            :label="t('dataSecurity.actions')"
            width="150"
            fixed="right"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                data-testid="grade-edit"
                :disabled="saving"
                @click="editGrade(row as DataGrade)"
                >{{ t('dataSecurity.editDefaultRule') }}</el-button
              ></template
            ></el-table-column
          >
        </el-table>
      </el-tab-pane>
    </el-tabs>
    <el-dialog
      v-model="editing"
      :title="t(existingCode ? 'dataSecurity.editRule' : 'dataSecurity.createRule')"
      :width="overlaySize.dialogSm"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      :before-close="
        (done: () => void) => {
          if (!saving) done()
        }
      "
    >
      <ErrorAlert v-if="editError" :tip="editError" class="dialog-error" />
      <el-form label-position="top" :disabled="saving" @submit.prevent="save">
        <el-form-item :label="t('dataSecurity.ruleCode')" required
          ><el-input
            v-model="draft.code"
            :disabled="!!existingCode"
            data-testid="rule-code"
            :aria-label="t('dataSecurity.ruleCode')"
        /></el-form-item>
        <el-form-item :label="t('dataSecurity.ruleName')" required
          ><el-input
            v-model="draft.name"
            data-testid="rule-name"
            :aria-label="t('dataSecurity.ruleName')"
        /></el-form-item>
        <el-form-item :label="t('dataSecurity.strategy')" required
          ><el-select
            :model-value="draft.rule.strategy"
            data-testid="rule-strategy"
            :aria-label="t('dataSecurity.strategy')"
            @update:model-value="setStrategy"
            ><el-option
              v-for="strategy in strategies"
              :key="strategy"
              :value="strategy"
              :label="t(`dataSecurity.strategies.${strategy}`)" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="draft.rule.strategy === 'keepFirst'"
          :label="t('dataSecurity.keepFirst')"
          required
          ><el-input-number
            v-model="draft.rule.keepFirst"
            :min="0"
            :max="2147483647"
            :precision="0"
            data-testid="rule-keep-first"
            :aria-label="t('dataSecurity.keepFirst')"
        /></el-form-item>
        <el-form-item
          v-if="draft.rule.strategy === 'replace'"
          :label="t('dataSecurity.replacement')"
          required
          ><el-input
            v-model="draft.rule.replacement"
            data-testid="rule-replacement"
            :aria-label="t('dataSecurity.replacement')"
        /></el-form-item>
      </el-form>
      <template #footer
        ><el-button :disabled="saving" @click="editing = false">{{
          t('dataSecurity.cancel')
        }}</el-button
        ><el-button
          type="primary"
          :disabled="!valid"
          :loading="saving"
          data-testid="rule-save"
          @click="save"
          >{{ t('dataSecurity.save') }}</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      :model-value="!!editingGrade"
      :title="t('dataSecurity.editDefaultRule')"
      :width="overlaySize.dialogSm"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      :before-close="
        (done: () => void) => {
          if (!saving) done()
        }
      "
      @close="editingGrade = null"
    >
      <ErrorAlert v-if="gradeError" :tip="gradeError" class="dialog-error" />
      <el-form label-position="top" :disabled="saving" @submit.prevent="saveGrade">
        <el-form-item :label="t('dataSecurity.grade')">{{
          editingGrade ? t(`dataSecurity.grades.${editingGrade.grade}`) : '—'
        }}</el-form-item>
        <el-form-item :label="t('dataSecurity.defaultRule')" required
          ><el-select
            v-model="gradeRule"
            data-testid="grade-rule"
            :aria-label="t('dataSecurity.defaultRule')"
            filterable
            ><el-option
              v-for="rule in rules"
              :key="rule.code"
              :value="rule.code"
              :label="`${rule.name}（${rule.code}）`" /></el-select
        ></el-form-item>
      </el-form>
      <template #footer
        ><el-button :disabled="saving" @click="editingGrade = null">{{
          t('dataSecurity.cancel')
        }}</el-button
        ><el-button
          type="primary"
          :loading="saving"
          :disabled="!gradeRule"
          data-testid="grade-save"
          @click="saveGrade"
          >{{ t('dataSecurity.save') }}</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      :model-value="!!deleting"
      :title="t('dataSecurity.deleteRule')"
      :width="overlaySize.dialogSm"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      :before-close="
        (done: () => void) => {
          if (!saving) done()
        }
      "
      @close="deleting = null"
    >
      <ErrorAlert v-if="deleteError" :tip="deleteError" class="dialog-error" />
      <el-alert
        :title="t('dataSecurity.deleteImpact', { name: deleting?.name })"
        type="warning"
        :closable="false"
      />
      <template #footer
        ><el-button :disabled="saving" @click="deleting = null">{{
          t('dataSecurity.cancel')
        }}</el-button
        ><el-button
          type="primary"
          :loading="saving"
          data-testid="rule-delete-confirm"
          @click="remove"
          >{{ t('dataSecurity.confirmDelete') }}</el-button
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
        <el-form-item :label="t('dataSecurity.rule')">{{ previewing?.name }}</el-form-item>
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
.preview-result {
  overflow-wrap: anywhere;
}
.dialog-error {
  margin-bottom: var(--forge-space-3);
}
</style>
