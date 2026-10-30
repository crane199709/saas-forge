<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@forge/sdk'
import type { AuditApi, AuditEvent } from './audit-api'

const props = defineProps<{ api: AuditApi }>()
const { t } = useI18n()
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
const error = ref<{ message: string; traceId?: string } | null>(null)

async function load(number = page.value) {
  error.value = null
  try {
    const result = await props.api.events({
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
  } catch (cause) {
    error.value = {
      message: cause instanceof ApiError && cause.userTip ? cause.userTip : t('error.unknown'),
      traceId: cause instanceof ApiError ? cause.traceId : undefined,
    }
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
  <section :aria-label="t('audit.title')">
    <h2>{{ t('audit.title') }}</h2>
    <el-alert v-if="error" :title="error.message" type="error" :closable="false" role="alert">
      <p v-if="error.traceId" class="trace">
        {{ t('error.traceId') }}：<code>{{ error.traceId }}</code>
      </p>
    </el-alert>
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
    <el-table :data="events" :empty-text="t('audit.empty')" row-key="id">
      <el-table-column :label="t('audit.time')">
        <template #default="{ row }">{{ new Date(row.occurredAt).toLocaleString() }}</template>
      </el-table-column>
      <el-table-column :label="t('audit.categoryLabel')">
        <template #default="{ row }">{{ t(`audit.category.${row.category}`) }}</template>
      </el-table-column>
      <el-table-column :label="t('audit.actor')">
        <template #default="{ row }">{{ actorOf(row as AuditEvent) }}</template>
      </el-table-column>
      <el-table-column prop="action" :label="t('audit.action')" />
      <el-table-column :label="t('audit.resource')">
        <template #default="{ row }">{{ resourceOf(row as AuditEvent) }}</template>
      </el-table-column>
      <el-table-column :label="t('audit.outcomeLabel')">
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
      layout="prev, pager, next"
      :current-page="page"
      :page-size="size"
      :total="total"
      @current-change="load"
    />
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
.error-code {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
