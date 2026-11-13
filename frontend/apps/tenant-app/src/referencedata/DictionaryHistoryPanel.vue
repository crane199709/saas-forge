<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, formatDateTime } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import type {
  Dictionary,
  DictionaryHistoryDiff,
  DictionaryHistoryItem,
  DictionaryHistoryDetail,
  DictionaryHistoryMetadata,
  ReferenceDataApi,
} from './reference-data-api'

const props = defineProps<{ api: ReferenceDataApi; dictionary: Dictionary }>()
const { t } = useI18n()
const versions = ref<DictionaryHistoryMetadata[]>([])
const detail = ref<DictionaryHistoryDetail | null>(null)
const error = ref<ErrorTip | null>(null)
const loading = ref(false)
const from = ref<number>()
const to = ref<number>()
const diff = ref<DictionaryHistoryDiff | null>(null)
const comparing = ref(false)
let diffRequest = 0
let generation = 0
let detailRequest = 0
onBeforeUnmount(() => {
  generation++
  detailRequest++
  diffRequest++
})

watch(
  () => props.dictionary,
  async () => {
    const request = ++generation
    detailRequest++
    diffRequest++
    diff.value = null
    comparing.value = false
    from.value = undefined
    to.value = undefined
    versions.value = []
    detail.value = null
    error.value = null
    loading.value = true
    try {
      const result = await props.api.history(props.dictionary)
      if (request === generation) {
        versions.value = result
        from.value = result[1]?.version ?? result[0]?.version
        to.value = result[0]?.version
      }
    } catch (cause) {
      if (request === generation) error.value = errorTip(cause, t)
    } finally {
      if (request === generation) loading.value = false
    }
  },
  { immediate: true },
)

async function showVersion(version: number) {
  const request = ++detailRequest
  error.value = null
  detail.value = null
  try {
    const result = await props.api.historyVersion(props.dictionary, version)
    if (request === detailRequest) detail.value = result
  } catch (cause) {
    if (request === detailRequest) error.value = errorTip(cause, t)
  }
}
async function compare() {
  if (from.value === undefined || to.value === undefined) return
  const request = ++diffRequest
  error.value = null
  diff.value = null
  comparing.value = true
  try {
    const result = await props.api.historyDiff(props.dictionary, from.value, to.value)
    if (request === diffRequest) diff.value = result
  } catch (cause) {
    if (request === diffRequest) error.value = errorTip(cause, t)
  } finally {
    if (request === diffRequest) comparing.value = false
  }
}

function resetDiff() {
  diff.value = null
  diffRequest++
  comparing.value = false
}

function describe(item: DictionaryHistoryItem) {
  return `${t('referenceData.dictionary.name')}：${item.name ?? t('referenceData.history.inherited')}；${t('referenceData.dictionary.sort')}：${item.sort ?? t('referenceData.history.inherited')}；${t(`referenceData.history.${item.enabled ? 'enabled' : 'disabled'}`)}`
}
</script>

<template>
  <section v-loading="loading">
    <ErrorAlert v-if="error" :tip="error" />
    <el-empty
      v-if="!loading && !versions.length && !error"
      :description="t('referenceData.history.empty')"
    />
    <el-table v-if="versions.length" :data="versions" row-key="version" data-testid="history-list">
      <el-table-column :label="t('referenceData.history.version')" width="80"
        ><template #default="{ row }">v{{ row.version }}</template></el-table-column
      >
      <el-table-column
        prop="summary"
        :label="t('referenceData.history.summary')"
        min-width="180"
        show-overflow-tooltip
      />
      <el-table-column
        :label="t('referenceData.history.actor')"
        min-width="130"
        show-overflow-tooltip
        ><template #default="{ row }">{{ row.changedBy ?? '—' }}</template></el-table-column
      >
      <el-table-column :label="t('referenceData.history.time')" min-width="160"
        ><template #default="{ row }">{{
          formatDateTime(row.changedAt)
        }}</template></el-table-column
      >
      <el-table-column :label="t('common.actions')" width="80" fixed="right"
        ><template #default="{ row }"
          ><el-button
            link
            type="primary"
            :data-testid="`history-detail-${row.version}`"
            @click="showVersion(row.version)"
            >{{ t('referenceData.history.detail') }}</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <el-form v-if="versions.length" inline class="compare-form" @submit.prevent="compare">
      <el-form-item :label="t('referenceData.history.from')">
        <el-select v-model="from" data-testid="history-from" @change="resetDiff">
          <el-option
            v-for="version in versions"
            :key="version.version"
            :label="`v${version.version}`"
            :value="version.version"
          />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('referenceData.history.to')">
        <el-select v-model="to" data-testid="history-to" @change="resetDiff">
          <el-option
            v-for="version in versions"
            :key="version.version"
            :label="`v${version.version}`"
            :value="version.version"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        ><el-button
          type="primary"
          data-testid="history-compare"
          :loading="comparing"
          @click="compare"
          >{{ t('referenceData.history.compare') }}</el-button
        ></el-form-item
      >
    </el-form>
    <section v-if="diff" class="diff">
      <h3>v{{ diff.from }} → v{{ diff.to }}</h3>
      <template v-for="kind in ['added', 'removed'] as const" :key="kind">
        <h3>{{ t(`referenceData.history.${kind}`) }} · {{ diff[kind].length }}</h3>
        <el-table :data="diff[kind]" :data-testid="`history-${kind}`">
          <el-table-column
            prop="code"
            :label="t('referenceData.dictionary.code')"
            min-width="150"
            show-overflow-tooltip
          />
          <el-table-column :label="t('referenceData.history.snapshot')" min-width="280"
            ><template #default="{ row }">{{
              describe(row as DictionaryHistoryItem)
            }}</template></el-table-column
          >
        </el-table>
      </template>
      <h3>{{ t('referenceData.history.changed') }} · {{ diff.changed.length }}</h3>
      <el-table :data="diff.changed" data-testid="history-changed">
        <el-table-column
          prop="after.code"
          :label="t('referenceData.dictionary.code')"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column :label="t('referenceData.history.before')" min-width="230"
          ><template #default="{ row }">{{ describe(row.before) }}</template></el-table-column
        >
        <el-table-column :label="t('referenceData.history.after')" min-width="230"
          ><template #default="{ row }">{{ describe(row.after) }}</template></el-table-column
        >
      </el-table>
      <el-empty
        v-if="!diff.added.length && !diff.removed.length && !diff.changed.length"
        :description="t('referenceData.history.unchanged')"
      />
    </section>
    <div v-if="detail" class="detail">
      <h3>{{ t('referenceData.history.snapshot') }} · v{{ detail.version }}</h3>
      <el-table :data="detail.snapshot" row-key="code" data-testid="history-snapshot">
        <el-table-column
          prop="code"
          :label="t('referenceData.dictionary.code')"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column
          :label="t('referenceData.dictionary.name')"
          min-width="150"
          show-overflow-tooltip
          ><template #default="{ row }">{{
            row.name ?? t('referenceData.history.inherited')
          }}</template></el-table-column
        >
        <el-table-column :label="t('referenceData.dictionary.sort')" width="90" align="right"
          ><template #default="{ row }">{{
            row.sort ?? t('referenceData.history.inherited')
          }}</template></el-table-column
        >
        <el-table-column :label="t('referenceData.dictionary.source')" width="90"
          ><template #default="{ row }">{{
            t(`referenceData.dictionary.${row.custom ? 'custom' : 'builtin'}`)
          }}</template></el-table-column
        >
        <el-table-column :label="t('referenceData.dictionary.status')" width="90"
          ><template #default="{ row }">{{
            t(`referenceData.history.${row.enabled ? 'enabled' : 'disabled'}`)
          }}</template></el-table-column
        >
      </el-table>
      <el-alert :title="t('referenceData.history.context')" type="info" :closable="false" />
      <h3>{{ t('referenceData.history.current') }}</h3>
      <el-table :data="detail.current?.items ?? []" data-testid="history-current">
        <el-table-column
          prop="code"
          :label="t('referenceData.dictionary.code')"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column
          prop="name"
          :label="t('referenceData.dictionary.name')"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column
          prop="sort"
          :label="t('referenceData.dictionary.sort')"
          width="90"
          align="right"
        />
        <el-table-column :label="t('referenceData.dictionary.status')" width="90"
          ><template #default="{ row }">{{
            t(`referenceData.dictionary.statuses.${row.status}`)
          }}</template></el-table-column
        >
      </el-table>
      <h3>{{ t('referenceData.history.manifest') }}</h3>
      <el-table :data="detail.manifest?.items ?? []" data-testid="history-manifest">
        <el-table-column
          prop="code"
          :label="t('referenceData.dictionary.code')"
          min-width="150"
          show-overflow-tooltip
        />
        <el-table-column
          prop="name"
          :label="t('referenceData.dictionary.name')"
          min-width="150"
          show-overflow-tooltip
        />
      </el-table>
      <el-empty v-if="!detail.current" :description="t('referenceData.history.unavailable')" />
    </div>
  </section>
</template>

<style scoped>
.compare-form {
  margin-top: var(--forge-space-4);
}
.compare-form .el-select {
  width: 100px;
}
h3 {
  margin: var(--forge-space-4) 0 var(--forge-space-3);
  font-size: var(--forge-font-size-md);
}
.detail .el-alert {
  margin-top: var(--forge-space-4);
}
</style>
