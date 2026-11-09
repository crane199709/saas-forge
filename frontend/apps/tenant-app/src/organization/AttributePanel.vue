<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, PageToolbar, overlaySize } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import type { AttributeType, ExtendedAttribute, OrganizationApi } from './org-api'

const props = defineProps<{ api: OrganizationApi; attributes: ExtendedAttribute[] }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const types: AttributeType[] = ['string', 'number', 'boolean']
const dialogOpen = ref(false)
// 新建与编辑共用一个对话框：编码和类型创建后不可修改。
const editing = ref<ExtendedAttribute | null>(null)
const form = ref({ code: '', name: '', type: 'string' as AttributeType, required: false })
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

function open(attribute: ExtendedAttribute | null) {
  error.value = null
  editing.value = attribute
  form.value = attribute
    ? {
        code: attribute.code,
        name: attribute.name,
        type: attribute.type,
        required: attribute.required,
      }
    : { code: '', name: '', type: 'string', required: false }
  dialogOpen.value = true
}

async function save() {
  if (busy.value || !form.value.code || !form.value.name.trim()) return
  const target = editing.value
  const name = form.value.name.trim()
  const done = await run(() =>
    target
      ? props.api.updateAttribute(target.id, name, form.value.required)
      : props.api.createAttribute({ ...form.value, name }),
  )
  if (done) dialogOpen.value = false
}
</script>

<template>
  <div>
    <PageToolbar>
      <template #actions>
        <el-button type="primary" data-testid="attribute-create" @click="open(null)">
          {{ t('org.attribute.create') }}
        </el-button>
      </template>
    </PageToolbar>
    <ErrorAlert v-if="error && !dialogOpen" class="page-error" :tip="error" />
    <el-table :data="attributes" row-key="id" :empty-text="t('org.attribute.empty')">
      <el-table-column :label="t('org.attribute.code')" min-width="200">
        <template #default="{ row }">
          <code :data-testid="`attribute-${row.code}`">{{ row.code }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="name" :label="t('org.attribute.name')" min-width="200" />
      <el-table-column :label="t('org.attribute.type')" width="120">
        <template #default="{ row }">{{ t(`org.attribute.types.${row.type}`) }}</template>
      </el-table-column>
      <el-table-column :label="t('org.attribute.required')" width="88">
        <template #default="{ row }">
          <el-tag v-if="row.required" size="small" type="warning">
            {{ t('org.attribute.required') }}
          </el-tag>
          <template v-else>—</template>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="112" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :data-testid="`attribute-edit-${row.code}`"
            @click="open(row as ExtendedAttribute)"
            >{{ t('org.attribute.edit') }}</el-button
          >
          <el-button
            link
            type="danger"
            :disabled="busy"
            :data-testid="`attribute-delete-${row.code}`"
            @click="run(() => api.deleteAttribute(row.id))"
            >{{ t('org.attribute.delete') }}</el-button
          >
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      v-model="dialogOpen"
      :width="overlaySize.dialogSm"
      :title="editing ? t('org.attribute.edit') : t('org.attribute.create')"
    >
      <ErrorAlert v-if="error" class="page-error" :tip="error" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('org.attribute.code')">
          <el-input
            v-model="form.code"
            :disabled="editing !== null"
            :placeholder="t('org.attribute.codePlaceholder')"
            maxlength="64"
            data-testid="attribute-code"
          />
        </el-form-item>
        <el-form-item :label="t('org.attribute.name')">
          <el-input v-model="form.name" maxlength="120" data-testid="attribute-name" />
        </el-form-item>
        <el-form-item :label="t('org.attribute.type')">
          <el-select v-model="form.type" :disabled="editing !== null">
            <el-option
              v-for="type in types"
              :key="type"
              :label="t(`org.attribute.types.${type}`)"
              :value="type"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.required" :label="t('org.attribute.required')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">{{ t('org.attribute.cancel') }}</el-button>
        <el-button
          type="primary"
          :disabled="busy || !form.code || !form.name.trim()"
          data-testid="attribute-save"
          @click="save"
          >{{ t('org.attribute.save') }}</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
</style>
