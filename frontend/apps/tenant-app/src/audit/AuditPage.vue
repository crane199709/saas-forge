<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import { createAuditApi, type AuditEvent } from './audit-api'

const { t } = useI18n()
const client = inject(tenantClientKey)
if (!client) throw new Error('Shell must provide a TenantClient')
const api = createAuditApi(client)
const size = 20
const events = ref<AuditEvent[]>([])
const page = ref(1)
const total = ref(0)
// el-date-picker 的 datetimerange 值：[开始, 结束]
const range = ref<[Date, Date] | null>(null)
const actor = ref('')
const action = ref('')
const resourceType = ref('')
const resourceId = ref('')
const loaded = ref(false)
const error = ref<ErrorTip | null>(null)

async function load(number = page.value) {
  error.value = null
  try {
    const result = await api.events({
      page: number,
      size,
      from: range.value?.[0].toISOString(),
      to: range.value?.[1].toISOString(),
      actor: actor.value.trim(),
      action: action.value.trim(),
      resourceType: resourceType.value.trim(),
      resourceId: resourceId.value.trim(),
    })
    events.value = result.items
    page.value = result.page.number
    total.value = result.page.totalElements
    loaded.value = true
  } catch (cause) {
    error.value = errorTip(cause, t)
  }
}

function actorOf(event: AuditEvent) {
  if (event.actorName) return `${event.actorName}（${event.actorAccount}）`
  return event.actorId ?? t('audit.system')
}

function resourceOf(event: AuditEvent) {
  return [event.resourceType, event.resourceId].filter(Boolean).join(' / ')
}

onMounted(() => void load(1))
</script>

<template>
  <section class="audit">
    <h2>{{ t('audit.title') }}</h2>
    <ErrorAlert v-if="error" :tip="error" />
    <template v-if="loaded">
      <div class="toolbar">
        <el-date-picker
          v-model="range"
          type="datetimerange"
          :start-placeholder="t('audit.from')"
          :end-placeholder="t('audit.to')"
          data-testid="audit-range"
        />
        <el-input
          v-model="actor"
          :placeholder="t('audit.actor')"
          clearable
          data-testid="audit-actor"
        />
        <el-input
          v-model="action"
          :placeholder="t('audit.action')"
          clearable
          data-testid="audit-action"
        />
        <el-input
          v-model="resourceType"
          :placeholder="t('audit.resourceType')"
          clearable
          data-testid="audit-resource-type"
        />
        <el-input
          v-model="resourceId"
          :placeholder="t('audit.resourceId')"
          clearable
          data-testid="audit-resource-id"
        />
        <el-button data-testid="audit-search" @click="load(1)">{{ t('audit.search') }}</el-button>
      </div>
      <table>
        <thead>
          <tr>
            <th>{{ t('audit.time') }}</th>
            <th>{{ t('audit.categoryLabel') }}</th>
            <th>{{ t('audit.actor') }}</th>
            <th>{{ t('audit.action') }}</th>
            <th>{{ t('audit.resource') }}</th>
            <th>{{ t('audit.outcomeLabel') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="event in events" :key="event.id" :data-testid="`audit-${event.id}`">
            <td>{{ new Date(event.occurredAt).toLocaleString() }}</td>
            <td>{{ t(`audit.category.${event.category}`) }}</td>
            <td>{{ actorOf(event) }}</td>
            <td>{{ event.action }}</td>
            <td>{{ resourceOf(event) }}</td>
            <td>
              <el-tag :type="event.outcome === 'success' ? 'success' : 'danger'" size="small">
                {{ t(`audit.outcome.${event.outcome}`) }}
              </el-tag>
              <span v-if="event.errorCode" class="error-code">{{ event.errorCode }}</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="events.length === 0">{{ t('audit.empty') }}</p>
      <el-pagination
        v-if="total > size"
        layout="prev, pager, next"
        :current-page="page"
        :page-size="size"
        :total="total"
        @current-change="load"
      />
    </template>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.toolbar .el-input {
  width: 180px;
}
table {
  width: 100%;
  border-collapse: collapse;
}
th,
td {
  padding: 6px 8px;
  text-align: left;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.error-code {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
