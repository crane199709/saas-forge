<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import ErrorAlert from '../ErrorAlert.vue'
import { errorTip, type ErrorTip } from '../error-tip'
import {
  groupPermissions,
  type DataPolicy,
  type DataResource,
  type DataScopeName,
  type GrantablePermission,
  type Role,
  type RoleApi,
} from './role-api'

const props = defineProps<{
  api: RoleApi
  roles: Role[]
  permissions: GrantablePermission[]
  dataResources: DataResource[]
}>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const groups = computed(() => groupPermissions(props.permissions))
const names = computed(() => new Map(props.permissions.map((item) => [item.code, item.name])))
const resourceNames = computed(
  () => new Map(props.dataResources.map((item) => [item.code, item.name])),
)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

// 新建与编辑共用一个对话框：editing 为 null 时新建，否则编辑该角色的名称、授权与数据范围。
// scopes 按数据资源记录所选预设，空串表示未配置。
const dialogOpen = ref(false)
const editing = ref<Role | null>(null)
const draft = ref({
  code: '',
  name: '',
  permissions: [] as string[],
  scopes: {} as Record<string, DataScopeName | ''>,
})

function scopesOf(policies: DataPolicy[]) {
  const scopes: Record<string, DataScopeName | ''> = {}
  for (const resource of props.dataResources) scopes[resource.code] = ''
  // 已从清单下线的资源不在编辑范围内，后端整体替换时也保留其上的策略
  for (const policy of policies)
    if (policy.resource in scopes) scopes[policy.resource] = policy.scope
  return scopes
}

function policiesOf(scopes: Record<string, DataScopeName | ''>): DataPolicy[] {
  return Object.entries(scopes)
    .filter((entry): entry is [string, DataScopeName] => entry[1] !== '')
    .map(([resource, scope]) => ({ resource, scope }))
    .sort((a, b) => a.resource.localeCompare(b.resource))
}

function openCreate() {
  editing.value = null
  draft.value = { code: '', name: '', permissions: [], scopes: scopesOf([]) }
  dialogOpen.value = true
}

function openEdit(role: Role) {
  editing.value = role
  draft.value = {
    code: role.code,
    name: role.name,
    permissions: [...role.permissions],
    scopes: scopesOf(role.dataPolicies),
  }
  dialogOpen.value = true
}

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

async function save() {
  const role = editing.value
  const name = draft.value.name.trim()
  const policies = policiesOf(draft.value.scopes)
  const done = await run(async () => {
    if (!role) {
      const created = await props.api.createRole(
        draft.value.code.trim(),
        name,
        draft.value.permissions,
      )
      if (policies.length > 0) await props.api.replaceDataPolicies(created.id, policies)
      return
    }
    if (name !== role.name) await props.api.renameRole(role.id, name)
    await props.api.replacePermissions(role.id, draft.value.permissions)
    // 数据范围变化会让持有者的 Token 失效，未改动时不提交
    if (JSON.stringify(policies) !== JSON.stringify(policiesOf(scopesOf(role.dataPolicies))))
      await props.api.replaceDataPolicies(role.id, policies)
  })
  if (done) dialogOpen.value = false
}

const summary = (role: Role) =>
  role.builtin
    ? t('role.list.allPermissions')
    : role.permissions.map((code) => names.value.get(code) ?? code).join('、')

const scopeSummary = (role: Role) =>
  role.builtin
    ? t('role.list.allData')
    : role.dataPolicies
        // 下线资源上的策略不再生效，不展示
        .filter((policy) => resourceNames.value.has(policy.resource))
        .map(
          (policy) =>
            `${resourceNames.value.get(policy.resource)}：${t(`role.scope.${policy.scope}`)}`,
        )
        .join('、')
</script>

<template>
  <div>
    <el-button type="primary" data-testid="role-create" @click="openCreate">
      {{ t('role.list.create') }}
    </el-button>
    <table class="roles">
      <thead>
        <tr>
          <th>{{ t('role.list.name') }}</th>
          <th>{{ t('role.list.code') }}</th>
          <th>{{ t('role.list.members') }}</th>
          <th>{{ t('role.list.permissions') }}</th>
          <th>{{ t('role.list.dataScopes') }}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="role in roles" :key="role.id" :data-testid="`role-${role.code}`">
          <td>
            {{ role.name }}
            <el-tag v-if="role.builtin" size="small">{{ t('role.list.builtin') }}</el-tag>
          </td>
          <td>{{ role.code }}</td>
          <td>{{ role.memberCount }}</td>
          <td>{{ summary(role) }}</td>
          <td :data-testid="`role-scopes-${role.code}`">{{ scopeSummary(role) }}</td>
          <td>
            <template v-if="!role.builtin">
              <el-button
                link
                type="primary"
                :data-testid="`role-edit-${role.code}`"
                @click="openEdit(role)"
              >
                {{ t('role.list.edit') }}
              </el-button>
              <el-button
                link
                type="danger"
                :disabled="busy"
                :data-testid="`role-delete-${role.code}`"
                @click="run(() => api.deleteRole(role.id))"
              >
                {{ t('role.list.delete') }}
              </el-button>
            </template>
          </td>
        </tr>
      </tbody>
    </table>
    <ErrorAlert v-if="error && !dialogOpen" :tip="error" />
    <el-dialog
      v-model="dialogOpen"
      :title="editing ? t('role.dialog.edit', { name: editing.name }) : t('role.dialog.create')"
    >
      <el-form label-width="96px">
        <el-form-item :label="t('role.list.code')">
          <el-input
            v-model="draft.code"
            :disabled="editing !== null"
            :placeholder="t('role.dialog.codeHint')"
            maxlength="64"
            data-testid="role-code"
          />
        </el-form-item>
        <el-form-item :label="t('role.list.name')">
          <el-input v-model="draft.name" maxlength="120" data-testid="role-name" />
        </el-form-item>
        <el-form-item :label="t('role.list.permissions')">
          <el-checkbox-group v-model="draft.permissions" class="permissions">
            <div v-for="group in groups" :key="group.key" class="permission-group">
              <div class="group-title">
                {{ group.service }} · {{ group.group ?? t('role.dialog.ungrouped') }}
              </div>
              <el-checkbox
                v-for="permission in group.permissions"
                :key="permission.code"
                :value="permission.code"
                :data-testid="`perm-${permission.code}`"
              >
                {{ permission.name }}（{{ permission.code }}）
              </el-checkbox>
            </div>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item v-if="dataResources.length > 0" :label="t('role.dialog.dataScopes')">
          <div class="scopes">
            <div class="group-title">{{ t('role.dialog.dataScopeHint') }}</div>
            <div v-for="resource in dataResources" :key="resource.code" class="scope-row">
              <span class="scope-name">{{ resource.name }}</span>
              <el-radio-group v-model="draft.scopes[resource.code]">
                <el-radio value="" :data-testid="`scope-${resource.code}-none`">
                  {{ t('role.dialog.noScope') }}
                </el-radio>
                <el-radio
                  v-for="scope in resource.scopes"
                  :key="scope"
                  :value="scope"
                  :data-testid="`scope-${resource.code}-${scope}`"
                >
                  {{ t(`role.scope.${scope}`) }}
                </el-radio>
              </el-radio-group>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <ErrorAlert v-if="error" :tip="error" />
      <template #footer>
        <el-button @click="dialogOpen = false">{{ t('role.dialog.cancel') }}</el-button>
        <el-button
          type="primary"
          :disabled="busy || !draft.name.trim() || !draft.code.trim()"
          data-testid="role-save"
          @click="save"
        >
          {{ t('role.dialog.save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.roles {
  width: 100%;
  margin-top: 12px;
  border-collapse: collapse;
}
.roles th,
.roles td {
  padding: 6px 8px;
  text-align: left;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.permissions {
  display: block;
}
.permission-group {
  margin-bottom: 8px;
}
.group-title {
  color: var(--el-text-color-secondary);
}
.scopes {
  display: block;
}
.scope-row {
  display: flex;
  gap: 12px;
  align-items: center;
}
.scope-name {
  min-width: 64px;
}
</style>
