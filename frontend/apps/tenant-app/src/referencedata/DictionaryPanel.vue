<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import {
  CUSTOM_ITEM_PREFIX,
  type Dictionary,
  type DictionaryItem,
  type ReferenceDataApi,
} from './reference-data-api'

const props = defineProps<{ api: ReferenceDataApi; dictionary: Dictionary }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const showDisabled = ref(false)
const form = ref({ code: '', name: '' })
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

// 停用与下线的项默认隐藏；它们仍需可见，以便核对历史数据或重新启用。
const items = computed(() =>
  props.dictionary.items.filter((item) => showDisabled.value || item.status === 'enabled'),
)

// 服务新版本新增的内置项可能与已有可选项重名，服务端只标记不处理；按名称导入会因此出错，提示租户改名。
const hasNameConflict = computed(() => props.dictionary.items.some((item) => item.nameConflict))

// 每行的显示名与排序先在本地编辑，点保存才提交；字典重读后以服务端结果重置。
const drafts = ref<Record<string, { name: string; sort: number }>>({})
watch(
  () => props.dictionary,
  (dictionary) => {
    drafts.value = Object.fromEntries(
      dictionary.items.map((item) => [item.code, { name: item.name, sort: item.sort }]),
    )
  },
  { immediate: true },
)

// 固定字典只读；已下线的项不再出现在当前声明中，服务端按不存在处理。
const editable = (item: DictionaryItem) => props.dictionary.extensible && item.status !== 'retired'

async function run(action: () => Promise<unknown>) {
  busy.value = true
  error.value = null
  try {
    await action()
    emit('changed')
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}

function save(item: DictionaryItem) {
  const draft = drafts.value[item.code]!
  return run(() => props.api.updateItem(props.dictionary, item.code, draft.name.trim(), draft.sort))
}

function create() {
  return run(async () => {
    await props.api.createItem(props.dictionary, form.value.code.trim(), form.value.name.trim())
    form.value = { code: '', name: '' }
  })
}
</script>

<template>
  <div class="panel">
    <h3>
      {{ dictionary.name }} <code>{{ dictionary.code }}</code>
    </h3>
    <p v-if="!dictionary.extensible">{{ t('referenceData.dictionary.readOnly') }}</p>
    <el-checkbox
      v-model="showDisabled"
      data-testid="show-disabled"
      :label="t('referenceData.dictionary.showDisabled')"
    />
    <p v-if="hasNameConflict" class="conflict-hint" data-testid="name-conflict-hint">
      {{ t('referenceData.dictionary.nameConflictHint') }}
    </p>
    <table class="items">
      <tbody>
        <tr v-for="item in items" :key="item.code" :data-testid="`item-${item.code}`">
          <td>
            <code>{{ item.code }}</code>
          </td>
          <template v-if="editable(item)">
            <td>
              <el-input
                v-model="drafts[item.code]!.name"
                :data-testid="`item-name-${item.code}`"
                :aria-label="t('referenceData.dictionary.name')"
                maxlength="120"
              />
            </td>
            <td>
              <el-input-number
                v-model="drafts[item.code]!.sort"
                :data-testid="`item-sort-${item.code}`"
                :aria-label="t('referenceData.dictionary.sort')"
                :step="1"
                step-strictly
                controls-position="right"
              />
            </td>
          </template>
          <template v-else>
            <td>{{ item.name }}</td>
            <td>{{ item.sort }}</td>
          </template>
          <td>
            {{
              item.custom
                ? t('referenceData.dictionary.custom')
                : t('referenceData.dictionary.builtin')
            }}
          </td>
          <td>
            {{ t(`referenceData.dictionary.statuses.${item.status}`) }}
            <el-tag
              v-if="item.nameConflict"
              type="warning"
              size="small"
              :data-testid="`item-conflict-${item.code}`"
            >
              {{ t('referenceData.dictionary.nameConflict') }}
            </el-tag>
          </td>
          <td v-if="editable(item)">
            <el-button
              link
              type="primary"
              :data-testid="`item-save-${item.code}`"
              :disabled="busy || !drafts[item.code]!.name.trim()"
              @click="save(item)"
            >
              {{ t('referenceData.dictionary.save') }}
            </el-button>
            <el-button
              v-if="item.status === 'enabled'"
              link
              type="danger"
              :data-testid="`item-disable-${item.code}`"
              :disabled="busy"
              @click="run(() => api.disableItem(dictionary, item.code))"
            >
              {{ t('referenceData.dictionary.disable') }}
            </el-button>
            <el-button
              v-else
              link
              :data-testid="`item-enable-${item.code}`"
              :disabled="busy"
              @click="run(() => api.enableItem(dictionary, item.code))"
            >
              {{ t('referenceData.dictionary.enable') }}
            </el-button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-if="dictionary.extensible" class="row">
      <div data-testid="new-item-code-field">
        <el-input
          v-model="form.code"
          data-testid="new-item-code"
          :placeholder="t('referenceData.dictionary.newCode')"
          maxlength="57"
        >
          <template #prepend>{{ CUSTOM_ITEM_PREFIX }}</template>
        </el-input>
      </div>
      <el-input
        v-model="form.name"
        data-testid="new-item-name"
        :placeholder="t('referenceData.dictionary.newName')"
        maxlength="120"
      />
      <el-button
        type="primary"
        data-testid="new-item-create"
        :disabled="busy || !form.code.trim() || !form.name.trim()"
        @click="create"
      >
        {{ t('referenceData.dictionary.create') }}
      </el-button>
    </div>
    <ErrorAlert v-if="error" :tip="error" />
  </div>
</template>

<style scoped>
.panel {
  flex: 1;
}
.row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 8px 0;
}
.conflict-hint {
  color: var(--el-color-warning);
}
.items td {
  padding: 4px 12px 4px 0;
}
</style>
