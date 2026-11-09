<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@crane199709/sdk'
import { ErrorAlert, PageHeader } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import {
  createReferenceDataApi,
  type Dictionary,
  type DictionaryItem,
  type Parameter,
  type ParameterValue,
} from './reference-data-api'

const { t } = useI18n()
const client = inject(tenantClientKey)
if (!client) throw new Error('Shell must provide a TenantClient')
const api = createReferenceDataApi(client)
const parameters = ref<Parameter[]>([])
const dictionaries = ref<Dictionary[]>([])
// 文本框的草稿为字符串，布尔参数为布尔，提交时再按类型转换。
const drafts = ref<Record<string, string | boolean>>({})
const loaded = ref(false)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

const keyOf = (parameter: Parameter) => `${parameter.service}/${parameter.code}`

// 服务端只返回 Tenant 可修改的参数；字典类型参数要按字典项名称展示与选择，一并读取字典。
async function load() {
  try {
    ;[parameters.value, dictionaries.value] = await Promise.all([
      api.parameters(),
      api.dictionaries(),
    ])
    drafts.value = Object.fromEntries(
      parameters.value.map((parameter) => [
        keyOf(parameter),
        typeof parameter.value === 'boolean' ? parameter.value : String(parameter.value),
      ]),
    )
    loaded.value = true
  } catch (cause) {
    error.value = errorTip(cause, t)
  }
}

function itemsOf(parameter: Parameter): DictionaryItem[] {
  return (
    dictionaries.value.find(
      (dictionary) =>
        dictionary.service === parameter.service && dictionary.code === parameter.dictionary,
    )?.items ?? []
  )
}

function display(parameter: Parameter, value: ParameterValue) {
  if (typeof value === 'boolean') return t(`referenceData.parameter.${value}`)
  if (parameter.type === 'dictionary') {
    return itemsOf(parameter).find((item) => item.code === value)?.name ?? String(value)
  }
  return String(value)
}

function constraints(parameter: Parameter) {
  const parts: string[] = []
  if (parameter.min !== null) parts.push(t('referenceData.parameter.min', { value: parameter.min }))
  if (parameter.max !== null) parts.push(t('referenceData.parameter.max', { value: parameter.max }))
  if (parameter.maxLength !== null) {
    parts.push(t('referenceData.parameter.maxLength', { value: parameter.maxLength }))
  }
  if (parameter.dictionary) {
    const dictionary = dictionaries.value.find(
      (item) => item.service === parameter.service && item.code === parameter.dictionary,
    )
    parts.push(
      t('referenceData.parameter.dictionary', { value: dictionary?.name ?? parameter.dictionary }),
    )
  }
  return parts.join('，')
}

// 数值类参数按 JSON 数字提交；无法解析时原样提交，由服务端返回取值不合法的提示。
function valueOf(parameter: Parameter): ParameterValue {
  const draft = drafts.value[keyOf(parameter)]!
  if (typeof draft === 'boolean') return draft
  const text = draft.trim()
  if ((parameter.type === 'integer' || parameter.type === 'decimal') && text !== '') {
    const number = Number(text)
    return Number.isFinite(number) ? number : text
  }
  return text
}

async function run(action: () => Promise<unknown>) {
  busy.value = true
  error.value = null
  try {
    await action()
    await load()
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}

onMounted(load)

const rowKey = (row: object) => keyOf(row as Parameter)
</script>

<template>
  <section>
    <PageHeader :title="t('referenceData.parameter.title')" />
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <p v-if="loaded && !parameters.length" class="hint">
      {{ t('referenceData.parameter.empty') }}
    </p>
    <el-table v-if="loaded && parameters.length" :data="parameters" :row-key="rowKey">
      <el-table-column :label="t('referenceData.parameter.name')" min-width="240">
        <template #default="{ row }">
          <div :data-testid="`parameter-${keyOf(row as Parameter)}`">{{ row.name }}</div>
          <code class="code">{{ row.service }} · {{ row.code }}</code>
        </template>
      </el-table-column>
      <el-table-column :label="t('referenceData.parameter.value')" min-width="170">
        <template #default="{ row }">
          <span class="value">
            <span class="value-text">{{ display(row as Parameter, row.value) || '—' }}</span>
            <el-tag v-if="row.overridden" size="small" type="info">
              {{ t('referenceData.parameter.overridden') }}
            </el-tag>
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('referenceData.parameter.defaultValue')" min-width="90">
        <template #default="{ row }">{{
          display(row as Parameter, row.defaultValue) || '—'
        }}</template>
      </el-table-column>
      <el-table-column
        :label="t('referenceData.parameter.constraints')"
        min-width="130"
        show-overflow-tooltip
      >
        <template #default="{ row }">{{ constraints(row as Parameter) || '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="300" fixed="right">
        <template #default="{ row }">
          <div class="edit">
            <div class="control">
              <el-checkbox
                v-if="row.type === 'boolean'"
                v-model="drafts[keyOf(row as Parameter)]"
                :data-testid="`value-${keyOf(row as Parameter)}`"
                :aria-label="row.name"
              />
              <el-select
                v-else-if="row.type === 'dictionary'"
                v-model="drafts[keyOf(row as Parameter)]"
                :data-testid="`value-${keyOf(row as Parameter)}`"
                :aria-label="row.name"
              >
                <el-option
                  v-for="item in itemsOf(row as Parameter).filter(
                    (item) => item.status === 'enabled',
                  )"
                  :key="item.code"
                  :label="item.name"
                  :value="item.code"
                />
              </el-select>
              <el-input
                v-else
                v-model="drafts[keyOf(row as Parameter)] as string"
                :data-testid="`value-${keyOf(row as Parameter)}`"
                :aria-label="row.name"
                :maxlength="row.maxLength ?? undefined"
              />
            </div>
            <el-button
              link
              type="primary"
              :data-testid="`override-${keyOf(row as Parameter)}`"
              :disabled="busy"
              @click="run(() => api.override(row as Parameter, valueOf(row as Parameter)))"
            >
              {{ t('referenceData.parameter.override') }}
            </el-button>
            <el-button
              v-if="row.overridden"
              link
              :data-testid="`reset-${keyOf(row as Parameter)}`"
              :disabled="busy"
              @click="run(() => api.reset(row as Parameter))"
            >
              {{ t('referenceData.parameter.reset') }}
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.hint {
  color: var(--el-text-color-secondary);
}
.code {
  display: block;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.value {
  display: inline-flex;
  gap: var(--forge-space-2);
  align-items: center;
  max-width: 100%;
}
.value-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.edit {
  display: flex;
  gap: var(--forge-space-2);
  align-items: center;
}
.control {
  flex: 0 0 140px;
}
</style>
