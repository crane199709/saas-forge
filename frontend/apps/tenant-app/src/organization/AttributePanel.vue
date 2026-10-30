<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert } from '@forge/ui'
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
    <table class="attributes">
      <tbody>
        <tr
          v-for="attribute in attributes"
          :key="attribute.id"
          :data-testid="`attribute-${attribute.code}`"
        >
          <td>
            <code>{{ attribute.code }}</code>
          </td>
          <td>{{ attribute.name }}</td>
          <td>{{ t(`org.attribute.types.${attribute.type}`) }}</td>
          <td>
            <el-checkbox
              :model-value="attribute.required"
              :disabled="busy"
              :label="t('org.attribute.required')"
              @update:model-value="
                (value: string | number | boolean) =>
                  run(() => api.updateAttribute(attribute.id, attribute.name, value === true))
              "
            />
          </td>
          <td>
            <el-button
              link
              type="danger"
              :disabled="busy"
              @click="run(() => api.deleteAttribute(attribute.id))"
            >
              {{ t('org.attribute.delete') }}
            </el-button>
          </td>
        </tr>
      </tbody>
    </table>
    <div class="row">
      <el-input v-model="form.code" :placeholder="t('org.attribute.code')" maxlength="64" />
      <el-input v-model="form.name" :placeholder="t('org.attribute.name')" maxlength="120" />
      <el-select v-model="form.type" :aria-label="t('org.attribute.type')">
        <el-option
          v-for="type in types"
          :key="type"
          :label="t(`org.attribute.types.${type}`)"
          :value="type"
        />
      </el-select>
      <el-checkbox v-model="form.required" :label="t('org.attribute.required')" />
      <el-button type="primary" :disabled="busy || !form.code || !form.name.trim()" @click="create">
        {{ t('org.attribute.create') }}
      </el-button>
    </div>
    <ErrorAlert v-if="error" :tip="error" />
  </div>
</template>

<style scoped>
.row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 8px 0;
}
.attributes td {
  padding: 4px 12px 4px 0;
}
</style>
