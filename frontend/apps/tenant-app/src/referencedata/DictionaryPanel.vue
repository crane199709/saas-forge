<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, PageToolbar, overlaySize } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import {
  CUSTOM_ITEM_PREFIX,
  type Dictionary,
  type DictionaryItem,
  type ReferenceDataApi,
} from './reference-data-api'

const props = defineProps<{ api: ReferenceDataApi; dictionary: Dictionary; canManage: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const showDisabled = ref(false)
// 新建与编辑共用一个对话框：editing 为 null 时新建自定义项，编码创建后不可修改。
const dialogOpen = ref(false)
const editing = ref<DictionaryItem | null>(null)
const form = ref({ code: '', name: '', sort: 0 })
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

// 停用与下线的项默认隐藏；它们仍需可见，以便核对历史数据或重新启用。
const items = computed(() =>
  props.dictionary.items.filter((item) => showDisabled.value || item.status === 'enabled'),
)

// 服务新版本新增的内置项可能与已有可选项重名，服务端只标记不处理；按名称导入会因此出错，提示租户改名。
const hasNameConflict = computed(() => props.dictionary.items.some((item) => item.nameConflict))

// 固定字典只读；已下线的项不再出现在当前声明中，服务端按不存在处理。
const editable = (item: DictionaryItem) =>
  props.canManage && props.dictionary.extensible && item.status !== 'retired'

async function run(action: () => Promise<unknown>) {
  busy.value = true
  error.value = null
  try {
    await action()
    emit('changed')
    return true
  } catch (cause) {
    error.value = errorTip(cause, t)
    return false
  } finally {
    busy.value = false
  }
}

function open(item: DictionaryItem | null) {
  error.value = null
  editing.value = item
  form.value = item
    ? { code: item.code, name: item.name, sort: item.sort }
    : { code: '', name: '', sort: 0 }
  dialogOpen.value = true
}

async function save() {
  const name = form.value.name.trim()
  const target = editing.value
  if (busy.value || !name || (!target && !form.value.code.trim())) return
  const done = await run(() =>
    target
      ? props.api.updateItem(props.dictionary, target.code, name, form.value.sort)
      : props.api.createItem(props.dictionary, form.value.code.trim(), name),
  )
  if (done) dialogOpen.value = false
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
    <ErrorAlert v-if="error && !dialogOpen" class="page-error" :tip="error" />
    <PageToolbar>
      <el-checkbox
        v-model="showDisabled"
        data-testid="show-disabled"
        :label="t('referenceData.dictionary.showDisabled')"
      />
      <template v-if="canManage && dictionary.extensible" #actions>
        <el-button type="primary" data-testid="new-item-create" @click="open(null)">
          {{ t('referenceData.dictionary.create') }}
        </el-button>
      </template>
    </PageToolbar>
    <el-table :data="items" row-key="code">
      <el-table-column :label="t('referenceData.dictionary.code')" min-width="160">
        <template #default="{ row }">
          <code :data-testid="`item-${row.code}`">{{ row.code }}</code>
        </template>
      </el-table-column>
      <el-table-column
        prop="name"
        :label="t('referenceData.dictionary.name')"
        min-width="160"
        show-overflow-tooltip
      />
      <el-table-column
        prop="sort"
        :label="t('referenceData.dictionary.sort')"
        width="80"
        align="right"
      />
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
        v-if="canManage && dictionary.extensible"
        :label="t('common.actions')"
        width="112"
        fixed="right"
      >
        <template #default="{ row }">
          <template v-if="editable(row as DictionaryItem)">
            <el-button
              link
              type="primary"
              :data-testid="`item-edit-${row.code}`"
              @click="open(row as DictionaryItem)"
            >
              {{ t('referenceData.dictionary.edit') }}
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
              type="primary"
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
    <el-dialog
      v-model="dialogOpen"
      :width="overlaySize.dialogSm"
      :title="editing ? t('referenceData.dictionary.edit') : t('referenceData.dictionary.create')"
    >
      <ErrorAlert v-if="error" class="page-error" :tip="error" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('referenceData.dictionary.code')">
          <el-input v-if="editing" :model-value="editing.code" disabled />
          <div v-else class="code-field" data-testid="item-code-field">
            <el-input v-model="form.code" data-testid="item-code" maxlength="57">
              <template #prepend>{{ CUSTOM_ITEM_PREFIX }}</template>
            </el-input>
          </div>
        </el-form-item>
        <el-form-item :label="t('referenceData.dictionary.name')">
          <el-input v-model="form.name" data-testid="item-name" maxlength="120" />
        </el-form-item>
        <el-form-item v-if="editing" :label="t('referenceData.dictionary.sort')">
          <el-input-number
            v-model="form.sort"
            data-testid="item-sort"
            :step="1"
            step-strictly
            controls-position="right"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">{{
          t('referenceData.dictionary.cancel')
        }}</el-button>
        <el-button
          type="primary"
          :disabled="busy || !form.name.trim() || (!editing && !form.code.trim())"
          data-testid="item-save"
          @click="save"
        >
          {{ t('referenceData.dictionary.save') }}
        </el-button>
      </template>
    </el-dialog>
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
  width: 100%;
}
.status {
  display: inline-flex;
  gap: var(--forge-space-2);
  align-items: center;
}
</style>
