<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import RestoreView from '../resource/RestoreView.vue'
import { ErrorAlert, overlaySize } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import { departmentTree, type Department, type OrganizationApi } from './org-api'

const props = defineProps<{ api: OrganizationApi; departments: Department[] }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const tree = computed(() => departmentTree(props.departments))
// 对话框的三种用途：新建根部门、在某部门下新建、编辑某部门。
type DialogState =
  { mode: 'create'; parent: Department | null } | { mode: 'edit'; target: Department }
const dialog = ref<DialogState | null>(null)
const name = ref('')
// 空字符串代表移为根部门：el-select 的 clearable 值不便与「未选择」区分。
const parent = ref('')
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

const dialogTitle = computed(() => {
  if (dialog.value?.mode === 'edit') return t('org.department.edit')
  return dialog.value?.parent
    ? t('org.department.createUnder', { name: dialog.value.parent.name })
    : t('org.department.createRoot')
})

// 不能移到自身或下级之下，候选中直接排除，服务端仍会校验。
const parentOptions = computed(() => {
  const own = dialog.value?.mode === 'edit' ? dialog.value.target.path : undefined
  return props.departments.filter(
    (item) => !own || (item.path !== own && !item.path.startsWith(`${own}.`)),
  )
})

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

function openCreate(under: Department | null) {
  error.value = null
  name.value = ''
  dialog.value = { mode: 'create', parent: under }
}

function openEdit(target: Department) {
  error.value = null
  name.value = target.name
  parent.value = target.parentId ?? ''
  dialog.value = { mode: 'edit', target }
}

async function save() {
  const state = dialog.value
  const value = name.value.trim()
  if (!state || !value || busy.value) return
  const done = await run(async () => {
    if (state.mode === 'create') {
      await props.api.createDepartment(value, state.parent?.id ?? null)
      return
    }
    if (value !== state.target.name) await props.api.renameDepartment(state.target.id, value)
    if (parent.value !== (state.target.parentId ?? ''))
      await props.api.moveDepartment(state.target.id, parent.value || null)
  })
  if (done) dialog.value = null
}
</script>

<template>
  <RestoreView
    :load-deleted="() => api.departments(true)"
    :restore="api.restoreDepartment"
    :disabled="busy"
    @restored="emit('changed')"
  >
    <template #actions>
      <el-button type="primary" data-testid="dept-create-root" @click="openCreate(null)">
        {{ t('org.department.createRoot') }}
      </el-button>
    </template>
    <ErrorAlert v-if="error && !dialog" class="page-error" :tip="error" />
    <el-table :data="tree" row-key="id" default-expand-all :empty-text="t('org.department.empty')">
      <el-table-column :label="t('org.department.name')" min-width="280">
        <template #default="{ row }">
          <span :data-testid="`dept-node-${row.id}`">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="200" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :data-testid="`dept-create-child-${row.id}`"
            @click="openCreate(row as Department)"
            >{{ t('org.department.createChild') }}</el-button
          >
          <el-button
            link
            type="primary"
            :data-testid="`dept-edit-${row.id}`"
            @click="openEdit(row as Department)"
            >{{ t('org.department.edit') }}</el-button
          >
          <el-button
            link
            type="danger"
            :disabled="busy"
            :data-testid="`dept-delete-${row.id}`"
            @click="run(() => api.deleteDepartment(row.id))"
            >{{ t('org.department.delete') }}</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      :model-value="dialog !== null"
      :width="overlaySize.dialogSm"
      :title="dialogTitle"
      @close="dialog = null"
    >
      <ErrorAlert v-if="error" class="page-error" :tip="error" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('org.department.name')">
          <el-input v-model="name" maxlength="120" data-testid="dept-name" />
        </el-form-item>
        <el-form-item v-if="dialog?.mode === 'edit'" :label="t('org.department.parent')">
          <el-select v-model="parent" data-testid="dept-parent">
            <el-option :label="t('org.department.rootOption')" value="" />
            <el-option
              v-for="item in parentOptions"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = null">{{ t('org.department.cancel') }}</el-button>
        <el-button
          type="primary"
          :disabled="busy || !name.trim()"
          data-testid="dept-save"
          @click="save"
          >{{ t('org.department.save') }}</el-button
        >
      </template>
    </el-dialog>
  </RestoreView>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
</style>
