<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import RestoreView from '../resource/RestoreView.vue'
import { ErrorAlert, overlaySize } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import type { OrganizationApi, Position } from './org-api'

const props = defineProps<{ api: OrganizationApi; positions: Position[] }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const dialogOpen = ref(false)
// 新建与编辑共用一个对话框：editing 为 null 时新建。
const editing = ref<Position | null>(null)
const name = ref('')
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

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

function open(position: Position | null) {
  error.value = null
  editing.value = position
  name.value = position?.name ?? ''
  dialogOpen.value = true
}

async function save() {
  const value = name.value.trim()
  if (!value || busy.value) return
  const target = editing.value
  const done = await run(() =>
    target ? props.api.renamePosition(target.id, value) : props.api.createPosition(value),
  )
  if (done) dialogOpen.value = false
}
</script>

<template>
  <RestoreView
    :load-deleted="() => api.positions(true)"
    :restore="api.restorePosition"
    :disabled="busy"
    @restored="emit('changed')"
  >
    <template #actions>
      <el-button type="primary" data-testid="position-create" @click="open(null)">
        {{ t('org.position.create') }}
      </el-button>
    </template>
    <ErrorAlert v-if="error && !dialogOpen" class="page-error" :tip="error" />
    <el-table :data="positions" row-key="id" :empty-text="t('org.position.empty')">
      <el-table-column :label="t('org.position.name')" min-width="240">
        <template #default="{ row }">
          <span :data-testid="`position-${row.id}`">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="112" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :data-testid="`position-edit-${row.id}`"
            @click="open(row as Position)"
            >{{ t('org.position.edit') }}</el-button
          >
          <el-button
            link
            type="danger"
            :disabled="busy"
            :data-testid="`position-delete-${row.id}`"
            @click="run(() => api.deletePosition(row.id))"
            >{{ t('org.position.delete') }}</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      v-model="dialogOpen"
      :width="overlaySize.dialogSm"
      :title="editing ? t('org.position.edit') : t('org.position.create')"
    >
      <ErrorAlert v-if="error" class="page-error" :tip="error" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('org.position.name')">
          <el-input v-model="name" maxlength="120" data-testid="position-name" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">{{ t('org.position.cancel') }}</el-button>
        <el-button
          type="primary"
          :disabled="busy || !name.trim()"
          data-testid="position-save"
          @click="save"
          >{{ t('org.position.save') }}</el-button
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
