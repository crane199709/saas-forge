<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Page } from '@crane199709/sdk'
import { ErrorAlert, ListPage, type ErrorTip } from '@crane199709/ui'
import { errorTip } from '../error-tip'
import type { OpsServiceStatus } from './dead-event-api'
import type { RunView, TaskDefinition, TaskRun, TaskRunApi } from './task-run-api'

const props = defineProps<{ api: TaskRunApi; canManage: boolean }>()
const { t } = useI18n()
const services = ref<OpsServiceStatus[]>([])
const service = ref('')
const tasks = ref<TaskDefinition[]>([])
const loading = ref(false)
const error = ref<ErrorTip | null>(null)
const historyOpen = ref(false)
const task = ref<TaskDefinition | null>(null)
const historyService = ref('')
const view = ref<RunView>('recent')
const runs = ref<TaskRun[]>([])
const historyLoading = ref(false)
const historyError = ref<ErrorTip | null>(null)
const detailOpen = ref(false)
const detail = ref<TaskRun | null>(null)
const detailService = ref('')
const confirming = ref(false)
const target = ref<{ service: string; name: string; tenantId?: string } | null>(null)
const submitting = ref(false)
const actionError = ref<ErrorTip | null>(null)
const done = ref('')
let generation = 0
let historyGeneration = 0

onUnmounted(() => {
  generation++
  historyGeneration++
})

function closeHistory() {
  historyGeneration++
  historyLoading.value = false
  detailOpen.value = false
}

function changeService() {
  done.value = ''
  historyOpen.value = false
  closeHistory()
  void load()
}

function openTrigger(selectedService: string, name: string, tenantId?: string) {
  target.value = { service: selectedService, name, tenantId }
  actionError.value = null
  done.value = ''
  confirming.value = true
}

async function trigger() {
  const current = target.value
  if (!current || submitting.value || !props.canManage) return
  submitting.value = true
  actionError.value = null
  try {
    const result = await props.api.trigger(current.service, current.name, current.tenantId)
    confirming.value = false
    if (service.value === current.service) await load()
    if (
      historyOpen.value &&
      historyService.value === current.service &&
      task.value?.name === current.name
    )
      await loadRuns()
    if (service.value === current.service) done.value = t('taskRun.accepted', { id: result.runId })
  } catch (cause) {
    actionError.value = errorTip(cause, t)
  } finally {
    submitting.value = false
  }
}

// 运维接口返回完整集合，列表仅展示这一页，不构造服务端不存在的翻页。
function page<T>(items: T[]): Page<T> {
  return {
    items,
    page: {
      number: 1,
      size: Math.max(1, items.length),
      totalElements: items.length,
      totalPages: 1,
    },
  }
}
const taskPage = computed(() => page(tasks.value))
const runPage = computed(() => page(runs.value))
const omittedFailures = computed(() =>
  Math.max(0, (detail.value?.failureCount ?? 0) - (detail.value?.failedTenants.length ?? 0)),
)

async function load() {
  const started = ++generation
  tasks.value = []
  error.value = null
  loading.value = false
  if (!service.value) return
  if (services.value.find((item) => item.service === service.value)?.available === false) {
    error.value = { message: t('deadEvent.serviceUnavailable', { service: service.value }) }
    return
  }
  loading.value = true
  try {
    const result = await props.api.list(service.value)
    if (started === generation) tasks.value = result
  } catch (cause) {
    if (started === generation) error.value = errorTip(cause, t)
  } finally {
    if (started === generation) loading.value = false
  }
}

async function loadRuns() {
  const started = ++historyGeneration
  runs.value = []
  historyError.value = null
  if (!task.value) return
  historyLoading.value = true
  try {
    const result = await props.api.runs(historyService.value, task.value.name, view.value)
    if (started === historyGeneration) runs.value = result
  } catch (cause) {
    if (started === historyGeneration) historyError.value = errorTip(cause, t)
  } finally {
    if (started === historyGeneration) historyLoading.value = false
  }
}

function openHistory(selected: TaskDefinition) {
  done.value = ''
  task.value = selected
  historyService.value = service.value
  view.value = 'recent'
  historyOpen.value = true
  void loadRuns()
}
function openDetail(run: TaskRun) {
  detail.value = run
  detailService.value = historyService.value
  detailOpen.value = true
}

onMounted(async () => {
  loading.value = true
  try {
    services.value = await props.api.services()
    service.value =
      (services.value.find((item) => item.available) ?? services.value[0])?.service ?? ''
  } catch (cause) {
    error.value = errorTip(cause, t)
    loading.value = false
    return
  }
  await load()
})
</script>

<template>
  <section :aria-label="t('taskRun.title')" class="task-runs">
    <h2>{{ t('taskRun.title') }}</h2>
    <el-alert type="info" :title="t('taskRun.retention')" :closable="false" class="notice" />
    <ErrorAlert v-if="error" :tip="error" />
    <el-alert
      v-if="done && !historyOpen && !detailOpen"
      type="success"
      :title="done"
      :closable="false"
      class="notice"
    />
    <ListPage :page="taskPage" :loading="loading" :empty-text="t('taskRun.empty')">
      <template #search>
        <el-form label-position="top" class="filters" @submit.prevent="load">
          <el-form-item :label="t('taskRun.service')" for="task-run-service">
            <el-select id="task-run-service" v-model="service" @change="changeService">
              <el-option
                v-for="item in services"
                :key="item.service"
                :value="item.service"
                :label="
                  item.available
                    ? item.service
                    : t('deadEvent.unavailableOption', { service: item.service })
                "
              />
            </el-select>
          </el-form-item>
          <el-button native-type="submit" :disabled="!service" :loading="loading">{{
            t('taskRun.refresh')
          }}</el-button>
        </el-form>
      </template>
      <el-table-column prop="name" :label="t('taskRun.name')" min-width="160" />
      <el-table-column :label="t('taskRun.type')" min-width="110">
        <template #default="{ row }">{{
          row.type ? t(`taskRun.types.${row.type}`) : '—'
        }}</template>
      </el-table-column>
      <el-table-column :label="t('taskRun.rule')" min-width="220">
        <template #default="{ row }">
          <span v-if="row.cron">{{ row.cron }} · {{ row.zone }}</span>
          <span v-else>{{ t('taskRun.fixedDelay') }} {{ row.fixedDelay }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('taskRun.latest')" min-width="220">
        <template #default="{ row }">
          <template v-if="row.latestRun"
            >{{ t(`taskRun.statuses.${row.latestRun.status}`) }}<br />{{
              row.latestRun.startedAt
            }}</template
          >
          <span v-else>{{ t('taskRun.neverRun') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('taskRun.actions')" :width="canManage ? 200 : 110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openHistory(row as TaskDefinition)">{{
            t('taskRun.history')
          }}</el-button>
          <el-button v-if="canManage" link type="primary" @click="openTrigger(service, row.name)">{{
            t('taskRun.manualTrigger')
          }}</el-button>
        </template>
      </el-table-column>
    </ListPage>
    <el-dialog
      v-model="historyOpen"
      @close="closeHistory"
      width="min(1100px, 94vw)"
      :title="t('taskRun.historyTitle', { name: task?.name ?? '' })"
    >
      <p>{{ historyService }}</p>
      <el-alert
        v-if="done && !detailOpen"
        type="success"
        :title="done"
        :closable="false"
        class="notice"
      />
      <ErrorAlert v-if="historyError" :tip="historyError" />
      <ListPage :page="runPage" :loading="historyLoading" :empty-text="t('taskRun.noRuns')">
        <template #search>
          <el-form label-position="top" class="filters" @submit.prevent="loadRuns">
            <el-form-item :label="t('taskRun.view')" for="task-run-view">
              <el-select id="task-run-view" v-model="view" @change="loadRuns">
                <el-option value="recent" :label="t('taskRun.recent')" />
                <el-option value="problems" :label="t('taskRun.problems')" />
              </el-select>
            </el-form-item>
            <el-button native-type="submit" :loading="historyLoading">{{
              t('taskRun.refresh')
            }}</el-button>
          </el-form>
        </template>
        <el-table-column prop="id" :label="t('taskRun.id')" min-width="200" />
        <el-table-column :label="t('taskRun.status')" min-width="100">
          <template #default="{ row }">{{
            row.status ? t(`taskRun.statuses.${row.status}`) : '—'
          }}</template>
        </el-table-column>
        <el-table-column :label="t('taskRun.trigger')" min-width="100">
          <template #default="{ row }">{{
            row.trigger ? t(`taskRun.triggers.${row.trigger}`) : '—'
          }}</template>
        </el-table-column>
        <el-table-column prop="startedAt" :label="t('taskRun.startedAt')" min-width="190" />
        <el-table-column :label="t('taskRun.counts')" min-width="140">
          <template #default="{ row }">{{
            row.type === 'TENANT'
              ? t('taskRun.resultCounts', { success: row.successCount, failure: row.failureCount })
              : '—'
          }}</template>
        </el-table-column>
        <el-table-column :label="t('taskRun.actions')" width="80" fixed="right">
          <template #default="{ row }"
            ><el-button link type="primary" @click="openDetail(row as TaskRun)">{{
              t('taskRun.detail')
            }}</el-button></template
          >
        </el-table-column>
      </ListPage>
      <template #footer
        ><el-button @click="historyOpen = false">{{ t('taskRun.close') }}</el-button></template
      >
    </el-dialog>
    <el-dialog
      v-model="detailOpen"
      width="min(800px, 94vw)"
      :title="t('taskRun.detailTitle')"
      append-to-body
    >
      <template v-if="detail">
        <el-alert v-if="done" type="success" :title="done" :closable="false" class="notice" />
        <el-descriptions :column="1" border class="detail">
          <el-descriptions-item :label="t('taskRun.id')">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item :label="t('taskRun.name')">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item :label="t('taskRun.status')">{{
            t(`taskRun.statuses.${detail.status}`)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('taskRun.trigger')">{{
            t(`taskRun.triggers.${detail.trigger}`)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('taskRun.operatorId')">{{
            detail.operatorId ?? '—'
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('taskRun.targetTenantId')">{{
            detail.targetTenantId ?? '—'
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('taskRun.startedAt')">{{
            detail.startedAt
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('taskRun.finishedAt')">{{
            detail.finishedAt ?? '—'
          }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.type === 'TENANT'" :label="t('taskRun.counts')">{{
            t('taskRun.resultCounts', {
              success: detail.successCount,
              failure: detail.failureCount,
            })
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('taskRun.errorSummary')">{{
            detail.errorSummary ?? '—'
          }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="detail.type === 'TENANT'">
          <h3>{{ t('taskRun.failedTenants') }}</h3>
          <el-alert
            v-if="omittedFailures"
            type="warning"
            :closable="false"
            :title="
              t('taskRun.omittedFailures', {
                listed: detail.failedTenants.length,
                omitted: omittedFailures,
              })
            "
            class="notice"
          />
          <el-table
            :data="detail.failedTenants"
            max-height="320"
            :empty-text="t('taskRun.noFailedTenants')"
          >
            <el-table-column prop="tenantId" :label="t('taskRun.tenantId')" min-width="220" />
            <el-table-column
              prop="errorSummary"
              :label="t('taskRun.errorSummary')"
              min-width="240"
            />
            <el-table-column
              v-if="canManage"
              :label="t('taskRun.actions')"
              width="100"
              fixed="right"
            >
              <template #default="{ row }"
                ><el-button
                  link
                  type="primary"
                  @click="openTrigger(detailService, detail.name, row.tenantId)"
                  >{{ t('taskRun.retryTenant') }}</el-button
                ></template
              >
            </el-table-column>
          </el-table>
        </template>
      </template>
      <template #footer
        ><el-button @click="detailOpen = false">{{ t('taskRun.close') }}</el-button></template
      >
    </el-dialog>
    <el-dialog
      v-model="confirming"
      width="min(480px, 94vw)"
      append-to-body
      :title="t(target?.tenantId ? 'taskRun.confirmRetry' : 'taskRun.confirmTrigger')"
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
    >
      <div class="detail">
        <p>{{ target?.service }} · {{ target?.name }}</p>
        <p v-if="target?.tenantId">{{ t('taskRun.targetTenantId') }}：{{ target.tenantId }}</p>
        <p>{{ t(target?.tenantId ? 'taskRun.retryHint' : 'taskRun.triggerHint') }}</p>
      </div>
      <ErrorAlert v-if="actionError" :tip="actionError" />
      <template #footer>
        <el-button :disabled="submitting" @click="confirming = false">{{
          t('taskRun.cancel')
        }}</el-button>
        <el-button type="primary" :loading="submitting" @click="trigger">{{
          t('taskRun.confirm')
        }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.filters {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px;
}
.filters .el-form-item {
  width: 240px;
  max-width: 100%;
  margin-bottom: 0;
}
.notice {
  margin-bottom: 16px;
}
.detail {
  overflow-wrap: anywhere;
}
</style>
