<script setup lang="ts">
import { computed, inject, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey, useAccessControl } from '@crane199709/sdk'
import { ErrorAlert, overlaySize } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import { createOrganizationApi, type Department } from '../organization/org-api'
import { createRoleApi, type Role } from '../role/role-api'
import { createMemberApi } from './member-api'
import {
  MAX_ROWS,
  OUTCOMES,
  failureWorkbook,
  parseInvitations,
  readRows,
  saveFile,
  sendInvitations,
  templateWorkbook,
  type BulkResult,
  type BulkRow,
  type FailedRow,
  type Outcome,
  type TemplateText,
} from './bulk-invite'

const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ finished: [] }>()

const { t } = useI18n()
const client = inject(tenantClientKey)
if (!client) throw new Error('Shell must provide a TenantClient')
const api = createMemberApi(client)
const access = useAccessControl()

type Step = 'select' | 'preview' | 'sending' | 'done'
const step = ref<Step>('select')
const rejected = ref('')
const loadError = ref<ErrorTip | null>(null)
const loading = ref(false)
const roles = ref<Role[] | null>(null)
const departments = ref<Department[] | null>(null)
const rows = ref<BulkRow[]>([])
const results = ref<BulkResult[]>([])
const settled = ref(0)
const stopping = ref(false)
const fileInput = ref<HTMLInputElement>()

const headers = computed(
  () =>
    [
      t('member.bulk.columns.contact'),
      t('member.bulk.columns.roles'),
      t('member.bulk.columns.department'),
    ] as const,
)
const template = computed<TemplateText>(() => ({
  headers: headers.value,
  guide: [
    t('member.bulk.guide.contact'),
    t('member.bulk.guide.roles'),
    t('member.bulk.guide.department'),
  ],
  sheets: { data: t('member.bulk.dataSheet'), guide: t('member.bulk.guideSheet') },
}))
const valid = computed(() => rows.value.filter((row) => row.input))
const invalid = computed(() => rows.value.filter((row) => row.problem))

// 本地校验错误、接口失败与停止后未处理的行都进入失败清单，按行号排在一起便于对照原文件修改。
const failures = computed<FailedRow[]>(() =>
  [
    ...invalid.value.map((row) => ({ row, reason: problemText(row) })),
    ...results.value
      .filter((result) => result.outcome === 'failed' || result.outcome === 'skipped')
      .map((result) => ({
        row: result.row,
        reason:
          result.outcome === 'skipped' ? t('member.bulk.skippedReason') : (result.reason ?? ''),
      })),
  ].sort((a, b) => a.row.line - b.row.line),
)
const counts = computed(() => {
  const count = Object.fromEntries(OUTCOMES.map((outcome) => [outcome, 0])) as Record<
    Outcome,
    number
  >
  for (const result of results.value) count[result.outcome]++
  count.failed += invalid.value.length
  return count
})

function problemText(row: BulkRow) {
  return row.problem ? t(`member.bulk.problem.${row.problem.key}`, row.problem.params ?? {}) : ''
}

// 角色与部门只在有对应管理权限时加载，与单个邀请对话框一致；没有权限时填了该列的行报错。
async function prepare() {
  step.value = 'select'
  rejected.value = ''
  loadError.value = null
  rows.value = []
  results.value = []
  settled.value = 0
  loading.value = true
  try {
    const [roleList, departmentList] = await Promise.all([
      access.has('iam:role:manage') ? createRoleApi(client!).roles() : Promise.resolve(null),
      access.has('iam:org:manage')
        ? createOrganizationApi(client!).departments()
        : Promise.resolve(null),
    ])
    roles.value = roleList
    departments.value = departmentList
  } catch (cause) {
    loadError.value = errorTip(cause, t)
  } finally {
    loading.value = false
  }
}

watch(open, (value) => {
  if (value) void prepare()
})

async function downloadTemplate() {
  saveFile(await templateWorkbook(template.value), t('member.bulk.templateFile'))
}

async function downloadFailures() {
  const blob = await failureWorkbook(template.value, t('member.bulk.reasonColumn'), failures.value)
  saveFile(blob, t('member.bulk.failureFile'))
}

async function choose(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 清空后再次选择同一个文件也会触发 change。
  input.value = ''
  if (!file) return
  rejected.value = ''
  rows.value = []
  step.value = 'select'
  if (!file.name.toLowerCase().endsWith('.xlsx')) {
    rejected.value = t('member.bulk.rejected.type')
    return
  }
  let data
  try {
    data = await readRows(file)
  } catch {
    rejected.value = t('member.bulk.rejected.unreadable')
    return
  }
  const parsed = parseInvitations(data, {
    headers: headers.value,
    reasonHeader: t('member.bulk.reasonColumn'),
    roles: roles.value,
    departments: departments.value,
  })
  if (!parsed.ok) {
    rejected.value = t(`member.bulk.rejected.${parsed.reason}`, { max: MAX_ROWS })
    return
  }
  rows.value = parsed.rows
  step.value = 'preview'
}

// 发送中关闭标签页会中断剩余行，且无法得知已发出请求的结果，交给浏览器弹出离开提示。
function preventLeave(event: BeforeUnloadEvent) {
  event.preventDefault()
}

async function send() {
  step.value = 'sending'
  settled.value = 0
  stopping.value = false
  window.addEventListener('beforeunload', preventLeave)
  try {
    results.value = await sendInvitations(
      valid.value,
      (input) => api.invite(input),
      (cause) => errorTip(cause, t).message,
      () => settled.value++,
      () => stopping.value,
    )
  } finally {
    window.removeEventListener('beforeunload', preventLeave)
  }
  step.value = 'done'
  emit('finished')
}

onBeforeUnmount(() => window.removeEventListener('beforeunload', preventLeave))
</script>

<template>
  <el-dialog
    v-model="open"
    :width="overlaySize.dialogMd"
    :title="t('member.bulk.title')"
    :show-close="step !== 'sending'"
    :close-on-click-modal="step !== 'sending'"
    :close-on-press-escape="step !== 'sending'"
  >
    <ErrorAlert v-if="loadError" :tip="loadError" />
    <template v-else-if="step === 'select' || step === 'preview'">
      <div class="actions">
        <el-button :disabled="loading" data-testid="member-bulk-choose" @click="fileInput?.click()">
          {{ step === 'preview' ? t('member.bulk.rechoose') : t('member.bulk.choose') }}
        </el-button>
        <el-button link type="primary" data-testid="member-bulk-template" @click="downloadTemplate">
          {{ t('member.bulk.template') }}
        </el-button>
        <input
          ref="fileInput"
          type="file"
          accept=".xlsx"
          hidden
          data-testid="member-bulk-file"
          @change="choose"
        />
      </div>
      <el-alert
        v-if="rejected"
        type="error"
        :title="rejected"
        :closable="false"
        show-icon
        data-testid="member-bulk-rejected"
      />
      <div v-if="step === 'preview'" class="preview" data-testid="member-bulk-preview">
        <el-alert
          :type="invalid.length ? 'warning' : 'success'"
          :closable="false"
          :title="t('member.bulk.summary', { count: valid.length })"
          :description="
            invalid.length ? t('member.bulk.problemSummary', { count: invalid.length }) : ''
          "
          show-icon
        />
        <el-table v-if="invalid.length" :data="invalid" max-height="320" row-key="line">
          <el-table-column :label="t('member.bulk.line')" prop="line" width="72" align="right" />
          <el-table-column
            :label="t('member.bulk.columns.contact')"
            min-width="200"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.contact || '—' }}</template>
          </el-table-column>
          <el-table-column :label="t('member.bulk.reason')" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ problemText(row as BulkRow) }}</template>
          </el-table-column>
        </el-table>
      </div>
    </template>
    <div v-else-if="step === 'sending'" class="progress" data-testid="member-bulk-progress">
      <el-progress :percentage="Math.round((settled / valid.length) * 100)" />
      <span>{{ t('member.bulk.progress', { done: settled, total: valid.length }) }}</span>
    </div>
    <div v-else class="result" data-testid="member-bulk-result">
      <dl class="counts">
        <div v-for="outcome in OUTCOMES" :key="outcome" :data-testid="`member-bulk-${outcome}`">
          <dt>{{ t(`member.bulk.outcome.${outcome}`) }}</dt>
          <dd :class="{ danger: outcome === 'failed' && counts.failed }">{{ counts[outcome] }}</dd>
        </div>
      </dl>
      <template v-if="failures.length">
        <div class="section-head">
          <h4 class="section-title">{{ t('member.bulk.failedRows') }}</h4>
          <el-button
            link
            type="primary"
            data-testid="member-bulk-download"
            @click="downloadFailures"
          >
            {{ t('member.bulk.downloadFailures') }}
          </el-button>
        </div>
        <el-table :data="failures" max-height="320" :row-key="(item) => String(item.row.line)">
          <el-table-column :label="t('member.bulk.line')" width="72" align="right">
            <template #default="{ row }">{{ row.row.line }}</template>
          </el-table-column>
          <el-table-column
            :label="t('member.bulk.columns.contact')"
            min-width="200"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.row.contact || '—' }}</template>
          </el-table-column>
          <el-table-column
            :label="t('member.bulk.reason')"
            prop="reason"
            min-width="240"
            show-overflow-tooltip
          />
        </el-table>
      </template>
    </div>
    <template #footer>
      <template v-if="step === 'done'">
        <el-button type="primary" data-testid="member-bulk-done" @click="open = false">
          {{ t('member.bulk.done') }}
        </el-button>
      </template>
      <template v-else-if="step === 'sending'">
        <el-button :loading="stopping" data-testid="member-bulk-stop" @click="stopping = true">
          {{ stopping ? t('member.bulk.stopping') : t('member.bulk.stop') }}
        </el-button>
        <el-button type="primary" loading>{{ t('member.bulk.send') }}</el-button>
      </template>
      <template v-else>
        <el-button @click="open = false">
          {{ t('member.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :disabled="step !== 'preview' || !valid.length"
          data-testid="member-bulk-send"
          @click="send"
        >
          {{ t('member.bulk.send') }}
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.actions {
  display: flex;
  gap: var(--forge-space-3);
  align-items: center;
  margin-bottom: var(--forge-space-3);
}
.preview {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-3);
}
.progress {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-2);
  color: var(--el-text-color-secondary);
}
.counts {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: var(--forge-space-3);
  margin: 0;
}
.counts dt {
  color: var(--el-text-color-secondary);
}
.counts dd {
  margin: 0;
  font-size: var(--forge-font-size-lg);
  color: var(--el-text-color-primary);
}
.counts dd.danger {
  color: var(--el-color-danger);
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: var(--forge-space-4) 0 var(--forge-space-2);
}
.section-title {
  margin: 0;
  font-size: var(--forge-font-size-sm);
  font-weight: 600;
}
</style>
