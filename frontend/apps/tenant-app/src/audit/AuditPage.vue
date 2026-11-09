<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, PageToolbar } from '@crane199709/ui'
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
  <section>
    <PageHeader :title="t('audit.title')" />
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <template v-if="loaded">
      <PageToolbar>
        <el-date-picker
          v-model="range"
          class="range"
          type="datetimerange"
          :start-placeholder="t('audit.from')"
          :end-placeholder="t('audit.to')"
          data-testid="audit-range"
        />
        <el-input
          v-model="actor"
          class="filter"
          :placeholder="t('audit.actor')"
          clearable
          data-testid="audit-actor"
        />
        <el-input
          v-model="action"
          class="filter"
          :placeholder="t('audit.action')"
          clearable
          data-testid="audit-action"
        />
        <el-input
          v-model="resourceType"
          class="filter"
          :placeholder="t('audit.resourceType')"
          clearable
          data-testid="audit-resource-type"
        />
        <el-input
          v-model="resourceId"
          class="filter"
          :placeholder="t('audit.resourceId')"
          clearable
          data-testid="audit-resource-id"
        />
        <el-button type="primary" data-testid="audit-search" @click="load(1)">{{
          t('audit.search')
        }}</el-button>
      </PageToolbar>
      <el-table :data="events" row-key="id" :empty-text="t('audit.empty')">
        <el-table-column :label="t('audit.time')" min-width="170">
          <template #default="{ row }">
            <span :data-testid="`audit-${row.id}`">{{
              new Date(row.occurredAt).toLocaleString()
            }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('audit.categoryLabel')" width="96">
          <template #default="{ row }">{{ t(`audit.category.${row.category}`) }}</template>
        </el-table-column>
        <el-table-column :label="t('audit.actor')" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ actorOf(row as AuditEvent) }}</template>
        </el-table-column>
        <el-table-column
          prop="action"
          :label="t('audit.action')"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column :label="t('audit.resource')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ resourceOf(row as AuditEvent) }}</template>
        </el-table-column>
        <el-table-column
          :label="t('audit.outcomeLabel')"
          width="220"
          fixed="right"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <el-tag :type="row.outcome === 'success' ? 'success' : 'danger'" size="small">
              {{ t(`audit.outcome.${row.outcome}`) }}
            </el-tag>
            <span v-if="row.errorCode" class="error-code">{{ row.errorCode }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="total > size"
        class="pagination"
        layout="total, prev, pager, next"
        :current-page="page"
        :page-size="size"
        :total="total"
        @current-change="load"
      />
    </template>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.range.el-date-editor {
  flex: none;
  width: 360px;
}
.filter {
  width: 160px;
}
.error-code {
  margin-left: var(--forge-space-2);
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-xs);
}
.pagination {
  justify-content: flex-end;
  margin-top: var(--forge-space-4);
}
</style>
