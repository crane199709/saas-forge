<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Page } from '@crane199709/sdk'
import { ErrorAlert, ListPage, PageHeader, overlaySize, type ErrorTip } from '@crane199709/ui'
import { errorTip } from '../error-tip'
import type { DeadEvent, DeadEventApi, DeadEventStatus, OpsServiceStatus } from './dead-event-api'

const props = defineProps<{ api: DeadEventApi; canManage: boolean }>()
const { t } = useI18n()
const statusTag: Record<DeadEventStatus, 'warning' | 'success' | 'info'> = {
  PENDING: 'warning',
  REPLAYED: 'success',
  ABANDONED: 'info',
}
const services = ref<OpsServiceStatus[]>([])
const service = ref('')
const status = ref<DeadEventStatus>('PENDING')
const page = ref<Page<DeadEvent> | null>(null)
const loading = ref(false)
const error = ref<ErrorTip | null>(null)
const confirming = ref(false)
const target = ref<{ service: string; event: DeadEvent; action: 'replay' | 'abandon' } | null>(null)
const reason = ref('')
const submitting = ref(false)
const actionError = ref<ErrorTip | null>(null)
const done = ref('')
const viewing = ref(false)
const detail = ref<DeadEvent | null>(null)
const detailService = ref('')
const detailLoading = ref(false)
const detailError = ref<ErrorTip | null>(null)
let detailGeneration = 0
let generation = 0

async function view(event: DeadEvent) {
  const started = ++detailGeneration
  detail.value = null
  detailService.value = service.value
  detailError.value = null
  detailLoading.value = true
  viewing.value = true
  try {
    const result = await props.api.get(detailService.value, event.id)
    if (started === detailGeneration) detail.value = result
  } catch (cause) {
    if (started === detailGeneration) detailError.value = errorTip(cause, t)
  } finally {
    if (started === detailGeneration) detailLoading.value = false
  }
}

function openAction(event: DeadEvent, action: 'replay' | 'abandon') {
  target.value = { service: service.value, event, action }
  reason.value = ''
  actionError.value = null
  done.value = ''
  confirming.value = true
}

async function resolve() {
  const current = target.value
  if (!current || !reason.value.trim() || submitting.value || !props.canManage) return
  submitting.value = true
  actionError.value = null
  try {
    await props.api[current.action](current.service, current.event.id, reason.value.trim())
    confirming.value = false
    if (service.value === current.service) await load(page.value?.page.number ?? 1)
    done.value = t(`deadEvent.${current.action}Done`)
  } catch (cause) {
    actionError.value = errorTip(cause, t)
    if (service.value === current.service) await load(page.value?.page.number ?? 1)
  } finally {
    submitting.value = false
  }
}

async function load(number = 1) {
  const started = ++generation
  page.value = null
  error.value = null
  done.value = ''
  loading.value = false
  if (!service.value) return
  if (services.value.find((item) => item.service === service.value)?.available === false) {
    error.value = { message: t('deadEvent.serviceUnavailable', { service: service.value }) }
    return
  }
  loading.value = true
  try {
    const result = await props.api.list(service.value, status.value, number)
    if (started === generation) page.value = result
  } catch (cause) {
    if (started === generation) error.value = errorTip(cause, t)
  } finally {
    if (started === generation) loading.value = false
  }
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
  <section :aria-label="t('deadEvent.title')">
    <PageHeader :title="t('deadEvent.title')" />
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <el-alert v-if="done" class="page-error" type="success" :title="done" :closable="false" />
    <ListPage
      :page="page"
      :loading="loading"
      :empty-text="t('deadEvent.empty')"
      @page-change="load"
    >
      <template #search>
        <el-form label-position="top" class="filters" @submit.prevent="load()">
          <el-form-item :label="t('deadEvent.service')" for="dead-event-service">
            <el-select id="dead-event-service" v-model="service" @change="load()">
              <el-option
                v-for="item in services"
                :key="item.service"
                :label="
                  item.available
                    ? item.service
                    : t('deadEvent.unavailableOption', { service: item.service })
                "
                :value="item.service"
              />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('deadEvent.status')" for="dead-event-status">
            <el-select id="dead-event-status" v-model="status" @change="load()">
              <el-option
                v-for="value in ['PENDING', 'REPLAYED', 'ABANDONED']"
                :key="value"
                :label="t(`deadEvent.statuses.${value}`)"
                :value="value"
              />
            </el-select>
          </el-form-item>
          <el-button native-type="submit" :disabled="!service" :loading="loading">{{
            t('deadEvent.refresh')
          }}</el-button>
        </el-form>
      </template>
      <el-table-column
        prop="topic"
        :label="t('deadEvent.topic')"
        min-width="130"
        show-overflow-tooltip
      />
      <el-table-column
        prop="tag"
        :label="t('deadEvent.tag')"
        min-width="80"
        show-overflow-tooltip
      />
      <el-table-column
        prop="tenantId"
        :label="t('deadEvent.tenantId')"
        min-width="140"
        show-overflow-tooltip
      >
        <template #default="{ row }">{{ row.tenantId ?? '—' }}</template>
      </el-table-column>
      <el-table-column
        prop="lastError"
        :label="t('deadEvent.lastError')"
        min-width="200"
        show-overflow-tooltip
      />
      <el-table-column
        prop="deliveryAttempt"
        :label="t('deadEvent.deliveryAttempt')"
        width="90"
        align="right"
      />
      <el-table-column prop="createdAt" :label="t('deadEvent.createdAt')" min-width="180" />
      <el-table-column prop="status" :label="t('deadEvent.status')" width="96">
        <template #default="{ row }">
          <el-tag v-if="row.status" size="small" :type="statusTag[row.status as DeadEventStatus]">
            {{ t(`deadEvent.statuses.${row.status}`) }}
          </el-tag>
          <template v-else>—</template>
        </template>
      </el-table-column>
      <el-table-column :label="t('deadEvent.actions')" :width="canManage ? 190 : 80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="view(row as DeadEvent)">{{
            t('deadEvent.view')
          }}</el-button>
          <template v-if="canManage && row.status === 'PENDING'">
            <el-button link type="primary" @click="openAction(row as DeadEvent, 'replay')">{{
              t('deadEvent.replay')
            }}</el-button>
            <el-button link type="danger" @click="openAction(row as DeadEvent, 'abandon')">{{
              t('deadEvent.abandon')
            }}</el-button>
          </template>
        </template>
      </el-table-column>
    </ListPage>
    <el-dialog v-model="viewing" :width="overlaySize.dialogMd" :title="t('deadEvent.detail')">
      <p>{{ detailService }}</p>
      <p v-if="detailLoading" role="status">{{ t('deadEvent.loading') }}</p>
      <ErrorAlert v-if="detailError" :tip="detailError" />
      <el-descriptions v-if="detail" :column="1" border class="detail">
        <el-descriptions-item :label="t('deadEvent.id')">{{ detail.id }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.eventId')">{{
          detail.eventId
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.consumerGroup')">{{
          detail.consumerGroup
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.topic')">{{
          detail.topic
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.tag')">{{ detail.tag }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.tenantId')">{{
          detail.tenantId ?? '—'
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.status')">{{
          t(`deadEvent.statuses.${detail.status}`)
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.lastError')">{{
          detail.lastError
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.deliveryAttempt')">{{
          detail.deliveryAttempt
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.createdAt')">{{
          detail.createdAt
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.resolvedAt')">{{
          detail.resolvedAt ?? '—'
        }}</el-descriptions-item>
        <el-descriptions-item :label="t('deadEvent.reason')">{{
          detail.resolutionReason ?? '—'
        }}</el-descriptions-item>
      </el-descriptions>
      <template #footer
        ><el-button @click="viewing = false">{{ t('deadEvent.close') }}</el-button></template
      >
    </el-dialog>
    <el-dialog
      v-model="confirming"
      :width="overlaySize.dialogSm"
      :title="t(`deadEvent.${target?.action ?? 'replay'}`)"
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
    >
      <p>{{ target?.service }} · {{ target?.event.eventId }}</p>
      <ErrorAlert v-if="actionError" :tip="actionError" />
      <el-form label-position="top" @submit.prevent="resolve">
        <el-form-item :label="t('deadEvent.reason')" for="dead-event-reason" required>
          <el-input
            id="dead-event-reason"
            v-model="reason"
            type="textarea"
            :rows="3"
            :disabled="submitting"
          />
        </el-form-item>
        <el-button
          native-type="submit"
          type="primary"
          :disabled="!reason.trim()"
          :loading="submitting"
          >{{
            t(target?.action === 'abandon' ? 'deadEvent.confirmAbandon' : 'deadEvent.confirmReplay')
          }}</el-button
        >
      </el-form>
    </el-dialog>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.detail {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.filters {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: var(--forge-space-3);
}
.filters .el-form-item {
  width: 220px;
  max-width: 100%;
  margin-bottom: 0;
}
</style>
