<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, PageToolbar, formatDateTime } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'

interface DeletedRecord {
  id: string
  name: string
  code?: string
  updatedAt?: string | null
  updatedBy?: string | null
}

const props = defineProps<{
  loadDeleted: () => Promise<DeletedRecord[]>
  restore: (id: string) => Promise<unknown>
  showCode?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ restored: [] }>()
const { t } = useI18n()
const deleted = ref(false)
const records = ref<DeletedRecord[]>([])
const busy = ref(false)
const pending = ref<DeletedRecord | null>(null)
const error = ref<ErrorTip | null>(null)
const succeeded = ref(false)

async function changeView(value: boolean) {
  if (busy.value || props.disabled || deleted.value === value) return
  deleted.value = value
  error.value = null
  succeeded.value = false
  if (!value) return
  busy.value = true
  records.value = []
  try {
    records.value = await props.loadDeleted()
  } catch (cause) {
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}

async function confirmRestore() {
  if (busy.value || !pending.value) return
  busy.value = true
  error.value = null
  succeeded.value = false
  try {
    await props.restore(pending.value.id)
    pending.value = null
    succeeded.value = true
    emit('restored')
    records.value = await props.loadDeleted()
  } catch (cause) {
    pending.value = null
    error.value = errorTip(cause, t)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section :aria-busy="busy">
    <PageToolbar>
      <el-button-group role="group" :aria-label="t('restore.view')">
        <el-button
          plain
          :type="deleted ? 'default' : 'primary'"
          :aria-pressed="!deleted"
          :disabled="busy || disabled"
          data-testid="restore-view-active"
          @click="changeView(false)"
          >{{ t('restore.active') }}</el-button
        >
        <el-button
          plain
          :type="deleted ? 'primary' : 'default'"
          :aria-pressed="deleted"
          :disabled="busy || disabled"
          data-testid="restore-view-deleted"
          @click="changeView(true)"
          >{{ t('restore.deleted') }}</el-button
        >
      </el-button-group>
      <template v-if="!deleted && $slots.actions" #actions><slot name="actions" /></template>
    </PageToolbar>
    <slot v-if="!deleted" />
    <template v-else>
      <ErrorAlert v-if="error" :tip="error" />
      <p v-if="succeeded" role="status">{{ t('restore.succeeded') }}</p>
      <el-table v-loading="busy" :data="records" :empty-text="t('restore.empty')">
        <el-table-column prop="name" :label="t('restore.name')" min-width="140" />
        <el-table-column v-if="showCode" prop="code" :label="t('role.list.code')" min-width="140" />
        <el-table-column :label="t('restore.deletedAt')" min-width="180">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('restore.deletedBy')" min-width="160">
          <template #default="{ row }">{{ row.updatedBy ?? t('restore.system') }}</template>
        </el-table-column>
        <el-table-column :label="t('restore.action')" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="busy"
              :data-testid="`restore-${row.id}`"
              @click="pending = row as DeletedRecord"
              >{{ t('restore.action') }}</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </template>
    <el-dialog
      :model-value="pending !== null"
      :title="t('restore.confirmTitle')"
      width="min(480px, calc(100vw - 32px))"
      :close-on-click-modal="!busy"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      @close="pending = null"
    >
      <p>{{ t('restore.confirmMessage', { name: pending?.name }) }}</p>
      <template #footer>
        <el-button :disabled="busy" @click="pending = null">{{ t('restore.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="busy"
          data-testid="restore-confirm"
          @click="confirmRestore"
          >{{ t('restore.confirmTitle') }}</el-button
        >
      </template>
    </el-dialog>
  </section>
</template>
