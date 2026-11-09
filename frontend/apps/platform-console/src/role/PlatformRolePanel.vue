<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@crane199709/sdk'
import { ErrorAlert, PageHeader, overlaySize, type ErrorTip } from '@crane199709/ui'
import type {
  DataPolicy,
  PlatformDataResource,
  PlatformPermission,
  PlatformRole,
  PlatformRoleApi,
} from './platform-role-api'

const props = defineProps<{ api: PlatformRoleApi }>()
const { t } = useI18n()
const roles = ref<PlatformRole[]>([])
const catalog = ref<PlatformPermission[]>([])
const resources = ref<PlatformDataResource[]>([])
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

// 「负责区域」是最常用的自定义条件：资源的区域等于 Operator 的负责区域，编辑器单独提供，保存为自定义条件。
const regionCondition = {
  attr: 'resource.region',
  op: 'eq',
  value: { ref: 'subject.attributes.region' },
}
const NONE = 'none'
const REGION = 'region'
const CUSTOM = 'custom'

// 每个数据资源一项选择：未配置（不可见）、某个预设、负责区域或自定义条件（JSON 表达式树）
interface ScopeDraft {
  choice: string
  condition: string
}

const dialogOpen = ref(false)
const editing = ref<PlatformRole | null>(null)
const emptyDraft = () => ({
  code: '',
  name: '',
  permissions: [] as string[],
  scopes: {} as Record<string, ScopeDraft>,
})
const draft = ref(emptyDraft())

interface PermissionGroup {
  key: string
  title: string
  permissions: PlatformPermission[]
}

const groups = computed<PermissionGroup[]>(() => {
  const result = new Map<string, PermissionGroup>()
  for (const permission of catalog.value) {
    const key = `${permission.service}/${permission.group ?? ''}`
    let group = result.get(key)
    if (!group) {
      group = {
        key,
        title: `${permission.service} · ${permission.group ?? t('platformRole.ungrouped')}`,
        permissions: [],
      }
      result.set(key, group)
    }
    group.permissions.push(permission)
  }
  return [...result.values()]
})

const hasRegion = (resource: PlatformDataResource) =>
  resource.attributes.some((attribute) => attribute.name === 'resource.region')
const isRegion = (condition: unknown) =>
  JSON.stringify(condition) === JSON.stringify(regionCondition)

function describe(role: PlatformRole) {
  if (role.builtin) return t('platformRole.allData')
  const parts = role.dataPolicies.map((policy) => {
    const name =
      resources.value.find((item) => item.code === policy.resource)?.name ?? policy.resource
    const scope =
      policy.scope !== CUSTOM
        ? t(`platformRole.scopes.${policy.scope}`)
        : isRegion(policy.condition)
          ? t('platformRole.scopes.region')
          : t('platformRole.scopes.custom')
    return `${name}：${scope}`
  })
  return parts.length > 0 ? parts.join('；') : t('platformRole.noData')
}

async function run(action: () => Promise<void>) {
  busy.value = true
  error.value = null
  try {
    await action()
    return true
  } catch (cause) {
    error.value = {
      message: cause instanceof ApiError && cause.userTip ? cause.userTip : t('error.unknown'),
      traceId: cause instanceof ApiError ? cause.traceId : undefined,
    }
    return false
  } finally {
    busy.value = false
  }
}

async function fetchRoles() {
  roles.value = await props.api.list()
}

function open(role: PlatformRole | null) {
  error.value = null
  editing.value = role
  const scopes: Record<string, ScopeDraft> = {}
  for (const resource of resources.value) {
    const policy = role?.dataPolicies.find((item) => item.resource === resource.code)
    if (!policy) scopes[resource.code] = { choice: NONE, condition: '' }
    else if (policy.scope !== CUSTOM)
      scopes[resource.code] = { choice: policy.scope, condition: '' }
    else if (isRegion(policy.condition) && hasRegion(resource))
      scopes[resource.code] = { choice: REGION, condition: '' }
    else
      scopes[resource.code] = {
        choice: CUSTOM,
        condition: JSON.stringify(policy.condition, null, 2),
      }
  }
  draft.value = role
    ? { code: role.code, name: role.name, permissions: [...role.permissions], scopes }
    : { ...emptyDraft(), scopes }
  dialogOpen.value = true
}

function policies(): DataPolicy[] {
  const result: DataPolicy[] = []
  for (const [resource, scope] of Object.entries(draft.value.scopes)) {
    if (scope.choice === NONE) continue
    if (scope.choice === REGION)
      result.push({ resource, scope: CUSTOM, condition: regionCondition })
    else if (scope.choice === CUSTOM) {
      // 只在本地解析为 JSON，条件是否合法由服务端按属性目录校验
      result.push({ resource, scope: CUSTOM, condition: JSON.parse(scope.condition) })
    } else result.push({ resource, scope: scope.choice })
  }
  return result
}

async function save() {
  const role = editing.value
  let dataPolicies: DataPolicy[]
  try {
    dataPolicies = policies()
  } catch {
    error.value = { message: t('platformRole.invalidJson') }
    return
  }
  const content = {
    name: draft.value.name.trim(),
    permissions: draft.value.permissions,
    dataPolicies,
  }
  const done = await run(async () => {
    if (role) await props.api.update(role.id, content)
    else await props.api.create({ code: draft.value.code.trim(), ...content })
    await fetchRoles()
  })
  if (done) dialogOpen.value = false
}

const remove = (role: PlatformRole) =>
  run(async () => {
    await props.api.remove(role.id)
    await fetchRoles()
  })

onMounted(() =>
  run(async () => {
    const [list, permissions, dataResources] = await Promise.all([
      props.api.list(),
      props.api.permissions(),
      props.api.dataResources(),
    ])
    roles.value = list
    catalog.value = permissions
    resources.value = dataResources
  }),
)
</script>

<template>
  <section :aria-label="t('platformRole.title')">
    <PageHeader :title="t('platformRole.title')">
      <template #actions>
        <el-button
          type="primary"
          :loading="busy"
          data-testid="platform-role-create"
          @click="open(null)"
          >{{ t('platformRole.create') }}</el-button
        >
      </template>
    </PageHeader>
    <ErrorAlert v-if="error && !dialogOpen" class="page-error" :tip="error" />

    <el-table :data="roles" row-key="id" :empty-text="t('platformRole.empty')">
      <el-table-column :label="t('platformRole.name')" min-width="160">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="row.builtin" size="small" type="info">{{
            t('platformRole.builtin')
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="code"
        :label="t('platformRole.code')"
        min-width="160"
        show-overflow-tooltip
      />
      <el-table-column :label="t('platformRole.permissions')" width="120">
        <template #default="{ row }">{{
          row.builtin
            ? t('platformRole.allPermissions')
            : t('platformRole.permissionCount', { count: row.permissions.length })
        }}</template>
      </el-table-column>
      <el-table-column :label="t('platformRole.dataScope')" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ describe(row as PlatformRole) }}</template>
      </el-table-column>
      <el-table-column
        prop="operatorCount"
        :label="t('platformRole.operatorCount')"
        width="100"
        align="right"
      />
      <el-table-column :label="t('platformRole.actions')" width="160" fixed="right">
        <template #default="{ row }">
          <template v-if="!row.builtin">
            <el-button
              link
              type="primary"
              :disabled="busy"
              :data-testid="`platform-role-edit-${row.code}`"
              @click="open(row as PlatformRole)"
              >{{ t('platformRole.edit') }}</el-button
            >
            <el-button
              link
              type="danger"
              :disabled="busy"
              :data-testid="`platform-role-delete-${row.code}`"
              @click="remove(row as PlatformRole)"
              >{{ t('platformRole.delete') }}</el-button
            >
          </template>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer
      v-model="dialogOpen"
      :size="overlaySize.drawerLg"
      :title="
        editing ? t('platformRole.editTitle', { name: editing.name }) : t('platformRole.create')
      "
    >
      <ErrorAlert v-if="error" :tip="error" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('platformRole.code')" for="platform-role-code">
          <el-input
            id="platform-role-code"
            v-model="draft.code"
            :disabled="editing !== null"
            :placeholder="t('platformRole.codeHint')"
            maxlength="64"
            required
          />
        </el-form-item>
        <el-form-item :label="t('platformRole.name')" for="platform-role-name">
          <el-input id="platform-role-name" v-model="draft.name" maxlength="120" required />
        </el-form-item>
        <el-form-item :label="t('platformRole.permissions')">
          <el-checkbox-group v-model="draft.permissions" class="groups">
            <div v-for="group in groups" :key="group.key">
              <h4>{{ group.title }}</h4>
              <el-checkbox
                v-for="permission in group.permissions"
                :key="permission.code"
                :value="permission.code"
                :data-testid="`platform-permission-${permission.code}`"
                >{{ permission.name }}</el-checkbox
              >
            </div>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item
          v-for="resource in resources"
          :key="resource.code"
          :label="t('platformRole.dataScopeOf', { name: resource.name })"
        >
          <el-radio-group
            v-model="draft.scopes[resource.code].choice"
            :data-testid="`platform-scope-${resource.code}`"
          >
            <el-radio :value="NONE">{{ t('platformRole.scopes.none') }}</el-radio>
            <el-radio v-for="scope in resource.scopes" :key="scope" :value="scope">{{
              t(`platformRole.scopes.${scope}`)
            }}</el-radio>
            <el-radio v-if="hasRegion(resource)" :value="REGION">{{
              t('platformRole.scopes.region')
            }}</el-radio>
            <el-radio :value="CUSTOM">{{ t('platformRole.scopes.custom') }}</el-radio>
          </el-radio-group>
          <template v-if="draft.scopes[resource.code].choice === CUSTOM">
            <el-input
              v-model="draft.scopes[resource.code].condition"
              type="textarea"
              :rows="5"
              :aria-label="t('platformRole.condition')"
              :data-testid="`platform-condition-${resource.code}`"
            />
            <p class="hint">{{ t('platformRole.conditionHint') }}</p>
          </template>
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('platformRole.save')
        }}</el-button>
      </el-form>
    </el-drawer>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.hint {
  margin: var(--forge-space-1) 0 0;
  color: var(--el-text-color-secondary);
}
.groups h4 {
  margin: var(--forge-space-2) 0 var(--forge-space-1);
}
</style>
