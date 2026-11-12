<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, PageHeader, PageToolbar, formatDateTime, type ErrorTip } from '@crane199709/ui'
import { errorTip } from '../error-tip'
import type { OnboardingApi, OnboardingDiagnostics, ServiceDiagnostic } from './onboarding-api'
const props = defineProps<{ api: OnboardingApi }>()
const { t } = useI18n()
const data = ref<OnboardingDiagnostics | null>(null)
const loading = ref(false)
const error = ref<ErrorTip | null>(null)
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = null
  try {
    data.value = await props.api.read()
  } catch (cause) {
    data.value = null
    error.value = errorTip(cause, t)
  } finally {
    loading.value = false
  }
}
function label(value?: string | null) {
  return value ? t(`onboarding.states.${value}`) : '—'
}
function missing(row: ServiceDiagnostic) {
  const values = [...row.configuration.missing]
  if (row.runtime.reporting && row.runtime.reporting.phase !== 'REPORTED')
    values.push(row.runtime.reporting.phase)
  if (row.runtime.status !== 'LOADED') values.push(row.runtime.status)
  if (row.runtime.reporting?.lastFailure && row.runtime.reporting.phase !== 'REPORTED')
    values.push(row.runtime.reporting.lastFailure)
  if (row.request.detail) values.push(row.request.detail)
  return [...new Set(values)].map((value) => label(value)).join('；') || '—'
}
const rowClass = ({ row }: { row: ServiceDiagnostic }) => `onboarding-${row.service}`
onMounted(load)
</script>
<template>
  <section :aria-label="t('onboarding.title')">
    <PageHeader :title="t('onboarding.title')" />
    <PageToolbar
      ><template #actions
        ><el-button type="primary" :loading="loading" @click="load">{{
          t('onboarding.refresh')
        }}</el-button></template
      ></PageToolbar
    >
    <ErrorAlert v-if="error" :tip="error" />
    <el-alert
      v-if="data && !data.directoryAvailable"
      type="warning"
      :title="t('onboarding.directoryUnavailable')"
      :closable="false"
    />
    <el-table
      v-loading="loading"
      :data="data?.services ?? []"
      row-key="service"
      :row-class-name="rowClass"
      :empty-text="t('onboarding.empty')"
    >
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-descriptions :column="2" border class="diagnostic-detail">
            <el-descriptions-item :label="t('onboarding.version')">{{
              row.runtime.reporting?.reportedVersion || '—'
            }}</el-descriptions-item>
            <el-descriptions-item :label="t('onboarding.reporting')">{{
              label(row.runtime.reporting?.phase)
            }}</el-descriptions-item>
            <el-descriptions-item :label="t('onboarding.lastSuccess')">{{
              formatDateTime(row.runtime.reporting?.lastSuccessAt)
            }}</el-descriptions-item>
            <el-descriptions-item :label="t('onboarding.lastFailure')"
              >{{ label(row.runtime.reporting?.lastFailure) }} ·
              {{ formatDateTime(row.runtime.reporting?.lastFailureAt) }}</el-descriptions-item
            >
            <el-descriptions-item :label="t('onboarding.configChecked')">{{
              formatDateTime(row.configuration.checkedAt)
            }}</el-descriptions-item>
            <el-descriptions-item :label="t('onboarding.runtimeChecked')">{{
              formatDateTime(row.runtime.checkedAt)
            }}</el-descriptions-item>
            <el-descriptions-item :label="t('onboarding.requestChecked')">{{
              formatDateTime(row.request.checkedAt)
            }}</el-descriptions-item>
            <el-descriptions-item :label="t('onboarding.source')">{{
              t('onboarding.nacosSource')
            }}</el-descriptions-item>
          </el-descriptions>
        </template>
      </el-table-column>
      <el-table-column
        prop="service"
        :label="t('onboarding.service')"
        min-width="180"
        show-overflow-tooltip
      />
      <el-table-column :label="t('onboarding.configuration')" min-width="115"
        ><template #default="{ row }"
          ><el-tag :type="row.configuration.status === 'CONFIGURED' ? 'success' : 'warning'">{{
            label(row.configuration.status)
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column :label="t('onboarding.runtime')" min-width="125"
        ><template #default="{ row }"
          ><el-tag :type="row.runtime.status === 'LOADED' ? 'success' : 'info'">{{
            label(row.runtime.status)
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column :label="t('onboarding.request')" min-width="125"
        ><template #default="{ row }"
          ><el-tag :type="row.request.status === 'VERIFIED' ? 'success' : 'info'">{{
            label(row.request.status)
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column :label="t('onboarding.missing')" min-width="220" show-overflow-tooltip
        ><template #default="{ row }">{{
          missing(row as ServiceDiagnostic)
        }}</template></el-table-column
      >
    </el-table>
  </section>
</template>
<style scoped>
.diagnostic-detail {
  margin: var(--forge-space-3);
}
</style>
