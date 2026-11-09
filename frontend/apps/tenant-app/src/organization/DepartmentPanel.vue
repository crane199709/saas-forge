<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import RestoreView from '../resource/RestoreView.vue'
import { ErrorAlert } from '@crane199709/ui'
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
  <RestoreView
    :load-deleted="() => api.departments(true)"
    :restore="api.restoreDepartment"
    :disabled="busy"
    @restored="emit('changed')"
  >
    <ErrorAlert v-if="error" class="page-error" :tip="error" />
    <div class="layout">
      <section>
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
        <p v-else class="hint">{{ t('org.department.empty') }}</p>
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
        <p class="hint path">{{ t('org.department.path') }}：{{ selected.path }}</p>
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
          plain
          type="danger"
          :disabled="busy"
          data-testid="dept-delete"
          @click="remove(selected.id)"
        >
          {{ t('org.department.delete') }}
        </el-button>
      </section>
    </div>
  </RestoreView>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.layout {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  gap: var(--forge-space-5);
}
.row {
  display: flex;
  gap: var(--forge-space-2);
  margin-top: var(--forge-space-3);
}
.detail {
  padding-left: var(--forge-space-5);
  border-left: 1px solid var(--el-border-color-lighter);
}
.detail h3 {
  margin: 0;
  font-size: var(--forge-font-size-md);
  font-weight: 600;
}
.hint {
  color: var(--el-text-color-secondary);
}
.path {
  margin: var(--forge-space-1) 0 var(--forge-space-4);
  font-size: var(--forge-font-size-xs);
  overflow-wrap: anywhere;
}
.detail > .el-button {
  margin-top: var(--forge-space-4);
}
</style>
