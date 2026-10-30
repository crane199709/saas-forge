<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert } from '@forge/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import { departmentTree, type Department, type OrganizationApi } from './org-api'

const props = defineProps<{ api: OrganizationApi; departments: Department[] }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const tree = computed(() => departmentTree(props.departments))
const selectedId = ref<string | null>(null)
const selected = computed(() => props.departments.find((item) => item.id === selectedId.value))
const name = ref('')
const rename = ref('')
// 空字符串代表移为根部门：el-select 的 clearable 值不便与「未选择」区分。
const parent = ref('')
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

// 不能移到自身或下级之下，候选中直接排除，服务端仍会校验。
const parentOptions = computed(() => {
  const own = selected.value?.path
  return props.departments.filter(
    (item) => !own || (item.path !== own && !item.path.startsWith(`${own}.`)),
  )
})

function select(department: Department) {
  selectedId.value = department.id
  rename.value = department.name
  parent.value = department.parentId ?? ''
}

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

function create(parentId: string | null) {
  return run(async () => {
    await props.api.createDepartment(name.value.trim(), parentId)
    name.value = ''
  })
}

function remove(id: string) {
  return run(async () => {
    await props.api.deleteDepartment(id)
    selectedId.value = null
  })
}
</script>

<template>
  <div class="department-panel">
    <section class="tree">
      <el-tree
        v-if="tree.length"
        :data="tree"
        node-key="id"
        :props="{ label: 'name', children: 'children' }"
        default-expand-all
        :expand-on-click-node="false"
        highlight-current
      >
        <template #default="{ data }">
          <span :data-testid="`dept-node-${data.id}`" @click="select(data)">{{ data.name }}</span>
        </template>
      </el-tree>
      <p v-else>{{ t('org.department.empty') }}</p>
      <div class="row">
        <el-input
          v-model="name"
          :placeholder="t('org.department.name')"
          maxlength="120"
          data-testid="dept-name"
        />
        <el-button :disabled="busy || !name.trim()" @click="create(null)">
          {{ t('org.department.createRoot') }}
        </el-button>
        <el-button
          type="primary"
          :disabled="busy || !name.trim() || !selected"
          data-testid="dept-create-child"
          @click="create(selected!.id)"
        >
          {{ t('org.department.createChild') }}
        </el-button>
      </div>
    </section>
    <section v-if="selected" class="detail">
      <h3>{{ t('org.department.selected') }}：{{ selected.name }}</h3>
      <p class="path">{{ t('org.department.path') }}：{{ selected.path }}</p>
      <div class="row">
        <el-input v-model="rename" maxlength="120" data-testid="dept-rename" />
        <el-button
          :disabled="busy || !rename.trim()"
          @click="run(() => api.renameDepartment(selected!.id, rename.trim()))"
        >
          {{ t('org.department.rename') }}
        </el-button>
      </div>
      <div class="row">
        <el-select
          v-model="parent"
          :aria-label="t('org.department.parent')"
          data-testid="dept-parent"
        >
          <el-option :label="t('org.department.rootOption')" value="" />
          <el-option
            v-for="item in parentOptions"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
        <el-button
          :disabled="busy"
          @click="run(() => api.moveDepartment(selected!.id, parent || null))"
        >
          {{ t('org.department.move') }}
        </el-button>
      </div>
      <el-button
        type="danger"
        :disabled="busy"
        data-testid="dept-delete"
        @click="remove(selected.id)"
      >
        {{ t('org.department.delete') }}
      </el-button>
    </section>
    <ErrorAlert v-if="error" :tip="error" />
  </div>
</template>

<style scoped>
.department-panel {
  display: grid;
  gap: 16px;
}
.row {
  display: flex;
  gap: 8px;
  margin: 8px 0;
  max-width: 640px;
}
.path {
  color: var(--el-text-color-secondary);
  overflow-wrap: anywhere;
}
</style>
