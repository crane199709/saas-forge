<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
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
</script>

<template>
  <section class="parameters">
    <h2>{{ t('referenceData.parameter.title') }}</h2>
    <ErrorAlert v-if="error" :tip="error" />
    <p v-if="loaded && !parameters.length">{{ t('referenceData.parameter.empty') }}</p>
    <table v-if="loaded && parameters.length" class="list">
      <thead>
        <tr>
          <th>{{ t('referenceData.parameter.service') }}</th>
          <th>{{ t('referenceData.parameter.name') }}</th>
          <th>{{ t('referenceData.parameter.value') }}</th>
          <th>{{ t('referenceData.parameter.defaultValue') }}</th>
          <th>{{ t('referenceData.parameter.constraints') }}</th>
          <th />
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="parameter in parameters"
          :key="keyOf(parameter)"
          :data-testid="`parameter-${keyOf(parameter)}`"
        >
          <td>{{ parameter.service }}</td>
          <td>
            {{ parameter.name }} <code>{{ parameter.code }}</code>
          </td>
          <td>
            {{ display(parameter, parameter.value) }}
            <el-tag v-if="parameter.overridden" size="small">
              {{ t('referenceData.parameter.overridden') }}
            </el-tag>
          </td>
          <td>{{ display(parameter, parameter.defaultValue) }}</td>
          <td>{{ constraints(parameter) }}</td>
          <td class="edit">
            <el-checkbox
              v-if="parameter.type === 'boolean'"
              v-model="drafts[keyOf(parameter)]"
              :data-testid="`value-${keyOf(parameter)}`"
              :aria-label="parameter.name"
            />
            <el-select
              v-else-if="parameter.type === 'dictionary'"
              v-model="drafts[keyOf(parameter)]"
              :data-testid="`value-${keyOf(parameter)}`"
              :aria-label="parameter.name"
            >
              <el-option
                v-for="item in itemsOf(parameter).filter((item) => item.status === 'enabled')"
                :key="item.code"
                :label="item.name"
                :value="item.code"
              />
            </el-select>
            <el-input
              v-else
              v-model="drafts[keyOf(parameter)] as string"
              :data-testid="`value-${keyOf(parameter)}`"
              :aria-label="parameter.name"
              :maxlength="parameter.maxLength ?? undefined"
            />
            <el-button
              type="primary"
              :data-testid="`override-${keyOf(parameter)}`"
              :disabled="busy"
              @click="run(() => api.override(parameter, valueOf(parameter)))"
            >
              {{ t('referenceData.parameter.override') }}
            </el-button>
            <el-button
              v-if="parameter.overridden"
              :data-testid="`reset-${keyOf(parameter)}`"
              :disabled="busy"
              @click="run(() => api.reset(parameter))"
            >
              {{ t('referenceData.parameter.reset') }}
            </el-button>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.list th,
.list td {
  padding: 4px 12px 4px 0;
  text-align: left;
}
.edit {
  display: flex;
  gap: 8px;
  align-items: center;
}
</style>
