<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, PageToolbar } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import type { AttributeType, ExtendedAttribute, OrganizationApi } from './org-api'

const props = defineProps<{ api: OrganizationApi; attributes: ExtendedAttribute[] }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const types: AttributeType[] = ['string', 'number', 'boolean']
const form = ref({ code: '', name: '', type: 'string' as AttributeType, required: false })
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

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

function create() {
  return run(async () => {
    await props.api.createAttribute({ ...form.value, name: form.value.name.trim() })
    form.value = { code: '', name: '', type: 'string', required: false }
  })
}
</script>

<template>
  <div>
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <PageToolbar>
      <el-input
        v-model="form.code"
        class="code-input"
        :placeholder="t('org.attribute.code')"
        maxlength="64"
      />
      <el-input
        v-model="form.name"
        class="name-input"
        :placeholder="t('org.attribute.name')"
        maxlength="120"
      />
      <el-select v-model="form.type" class="type-input" :aria-label="t('org.attribute.type')">
        <el-option
          v-for="type in types"
          :key="type"
          :label="t(`org.attribute.types.${type}`)"
          :value="type"
        />
      </el-select>
      <el-checkbox v-model="form.required" :label="t('org.attribute.required')" />
      <template #actions>
        <el-button
          type="primary"
          :disabled="busy || !form.code || !form.name.trim()"
          @click="create"
        >
          {{ t('org.attribute.create') }}
        </el-button>
      </template>
    </PageToolbar>
    <el-table :data="attributes" row-key="id">
      <el-table-column :label="t('org.attribute.codeColumn')" min-width="200">
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
          <el-checkbox
            :model-value="row.required"
            :disabled="busy"
            :aria-label="t('org.attribute.required')"
            @update:model-value="
              (value: string | number | boolean) =>
                run(() => api.updateAttribute(row.id, row.name, value === true))
            "
          />
        </template>
      </el-table-column>
      <el-table-column :label="t('common.actions')" width="88" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="danger"
            :disabled="busy"
            @click="run(() => api.deleteAttribute(row.id))"
          >
            {{ t('org.attribute.delete') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.code-input {
  width: 220px;
}
.name-input {
  width: 180px;
}
.type-input {
  width: 120px;
}
</style>
