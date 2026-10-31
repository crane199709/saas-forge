<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import type { OrganizationApi, Position } from './org-api'

const props = defineProps<{ api: OrganizationApi; positions: Position[] }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const name = ref('')
// 每行的改名草稿，未编辑的行取当前名称。
const drafts = ref<Record<string, string>>({})
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
    await props.api.createPosition(name.value.trim())
    name.value = ''
  })
}
</script>

<template>
  <div>
    <div
      v-for="position in positions"
      :key="position.id"
      class="row"
      :data-testid="`position-${position.id}`"
    >
      <el-input
        :model-value="drafts[position.id] ?? position.name"
        maxlength="120"
        @update:model-value="(value: string) => (drafts[position.id] = value)"
      />
      <el-button
        :disabled="busy || !(drafts[position.id] ?? position.name).trim()"
        @click="
          run(() => api.renamePosition(position.id, (drafts[position.id] ?? position.name).trim()))
        "
      >
        {{ t('org.position.rename') }}
      </el-button>
      <el-button type="danger" :disabled="busy" @click="run(() => api.deletePosition(position.id))">
        {{ t('org.position.delete') }}
      </el-button>
    </div>
    <div class="row">
      <el-input v-model="name" :placeholder="t('org.position.name')" maxlength="120" />
      <el-button type="primary" :disabled="busy || !name.trim()" @click="create">
        {{ t('org.position.create') }}
      </el-button>
    </div>
    <ErrorAlert v-if="error" :tip="error" />
  </div>
</template>

<style scoped>
.row {
  display: flex;
  gap: 8px;
  margin: 8px 0;
  max-width: 640px;
}
</style>
