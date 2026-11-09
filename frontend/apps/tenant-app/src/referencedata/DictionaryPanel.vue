<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, PageToolbar } from '@crane199709/ui'
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
    <h3 class="title">
      {{ dictionary.name }} <code>{{ dictionary.code }}</code>
    </h3>
    <p v-if="!dictionary.extensible" class="hint">{{ t('referenceData.dictionary.readOnly') }}</p>
    <p v-if="hasNameConflict" class="conflict-hint" data-testid="name-conflict-hint">
      {{ t('referenceData.dictionary.nameConflictHint') }}
    </p>
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <PageToolbar>
      <el-checkbox
        v-model="showDisabled"
        data-testid="show-disabled"
        :label="t('referenceData.dictionary.showDisabled')"
      />
      <template v-if="dictionary.extensible" #actions>
        <div class="code-field" data-testid="new-item-code-field">
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
          class="name-field"
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
      </template>
    </PageToolbar>
    <el-table :data="items" row-key="code">
      <el-table-column :label="t('referenceData.dictionary.code')" min-width="130">
        <template #default="{ row }">
          <code :data-testid="`item-${row.code}`">{{ row.code }}</code>
        </template>
      </el-table-column>
      <el-table-column :label="t('referenceData.dictionary.name')" min-width="140">
        <template #default="{ row }">
          <el-input
            v-if="editable(row as DictionaryItem)"
            v-model="drafts[row.code]!.name"
            :data-testid="`item-name-${row.code}`"
            :aria-label="t('referenceData.dictionary.name')"
            maxlength="120"
          />
          <template v-else>{{ row.name }}</template>
        </template>
      </el-table-column>
      <el-table-column :label="t('referenceData.dictionary.sort')" width="120">
        <template #default="{ row }">
          <el-input-number
            v-if="editable(row as DictionaryItem)"
            v-model="drafts[row.code]!.sort"
            class="sort-input"
            :data-testid="`item-sort-${row.code}`"
            :aria-label="t('referenceData.dictionary.sort')"
            :step="1"
            step-strictly
            controls-position="right"
          />
          <template v-else>{{ row.sort }}</template>
        </template>
      </el-table-column>
      <el-table-column :label="t('referenceData.dictionary.source')" width="88">
        <template #default="{ row }">
          {{
            row.custom
              ? t('referenceData.dictionary.custom')
              : t('referenceData.dictionary.builtin')
          }}
        </template>
      </el-table-column>
      <el-table-column :label="t('referenceData.dictionary.status')" width="140">
        <template #default="{ row }">
          <span class="status">
            <span>{{ t(`referenceData.dictionary.statuses.${row.status}`) }}</span>
            <el-tag
              v-if="row.nameConflict"
              type="warning"
              size="small"
              :data-testid="`item-conflict-${row.code}`"
            >
              {{ t('referenceData.dictionary.nameConflict') }}
            </el-tag>
          </span>
        </template>
      </el-table-column>
      <el-table-column
        v-if="dictionary.extensible"
        :label="t('common.actions')"
        width="112"
        fixed="right"
      >
        <template #default="{ row }">
          <template v-if="editable(row as DictionaryItem)">
            <el-button
              link
              type="primary"
              :data-testid="`item-save-${row.code}`"
              :disabled="busy || !drafts[row.code]!.name.trim()"
              @click="save(row as DictionaryItem)"
            >
              {{ t('referenceData.dictionary.save') }}
            </el-button>
            <el-button
              v-if="row.status === 'enabled'"
              link
              type="danger"
              :data-testid="`item-disable-${row.code}`"
              :disabled="busy"
              @click="run(() => api.disableItem(dictionary, row.code))"
            >
              {{ t('referenceData.dictionary.disable') }}
            </el-button>
            <el-button
              v-else
              link
              :data-testid="`item-enable-${row.code}`"
              :disabled="busy"
              @click="run(() => api.enableItem(dictionary, row.code))"
            >
              {{ t('referenceData.dictionary.enable') }}
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.panel {
  flex: 1;
  min-width: 0;
}
.title {
  margin: 0 0 var(--forge-space-3);
  font-size: var(--forge-font-size-md);
  font-weight: 600;
}
.title code {
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-xs);
  font-weight: 400;
}
.hint {
  margin: 0 0 var(--forge-space-3);
  color: var(--el-text-color-secondary);
}
.conflict-hint {
  margin: 0 0 var(--forge-space-3);
  color: var(--el-color-warning);
}
.page-error {
  margin-bottom: var(--forge-space-3);
}
.code-field {
  width: 200px;
}
.name-field {
  width: 120px;
}
.sort-input {
  width: 100%;
}
.status {
  display: inline-flex;
  gap: var(--forge-space-2);
  align-items: center;
}
</style>
