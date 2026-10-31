<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import ConditionGroup from './ConditionGroup.vue'
import ConditionPreview from './ConditionPreview.vue'
import {
  emptyGroup,
  fromConditionJson,
  isComplete,
  toConditionJson,
  type GroupNode,
} from './condition'
import {
  groupPermissions,
  type ConditionOptions,
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
  conditionOptions: ConditionOptions
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
// scopes 按数据资源记录所选预设或 custom，空串表示未配置；custom 的条件树在 conditions 中。
type ScopeChoice = DataScopeName | 'custom' | ''
interface Scopes {
  scopes: Record<string, ScopeChoice>
  conditions: Record<string, GroupNode>
}
const dialogOpen = ref(false)
const editing = ref<Role | null>(null)
const draft = ref({
  code: '',
  name: '',
  permissions: [] as string[],
  scopes: {} as Record<string, ScopeChoice>,
  conditions: {} as Record<string, GroupNode>,
})
// 正在预览的数据资源
const previewing = ref<string | null>(null)

function scopesOf(policies: DataPolicy[]): Scopes {
  const scopes: Record<string, ScopeChoice> = {}
  const conditions: Record<string, GroupNode> = {}
  for (const resource of props.dataResources) scopes[resource.code] = ''
  // 已从清单下线的资源不在编辑范围内，后端整体替换时也保留其上的策略
  for (const policy of policies) {
    if (!(policy.resource in scopes)) continue
    scopes[policy.resource] = policy.scope
    if (policy.scope === 'custom') conditions[policy.resource] = fromConditionJson(policy.condition)
  }
  return { scopes, conditions }
}

function policyOf(resource: string, { scopes, conditions }: Scopes): DataPolicy | null {
  const scope = scopes[resource]
  if (!scope) return null
  if (scope !== 'custom') return { resource, scope }
  return { resource, scope, condition: toConditionJson(conditions[resource]!) }
}

function policiesOf(value: Scopes): DataPolicy[] {
  return Object.keys(value.scopes)
    .map((resource) => policyOf(resource, value))
    .filter((policy): policy is DataPolicy => policy !== null)
    .sort((a, b) => a.resource.localeCompare(b.resource))
}

// 选中自定义条件时补一个空条件组
function choose(resource: string, scope: ScopeChoice) {
  draft.value.scopes[resource] = scope
  if (scope === 'custom' && !draft.value.conditions[resource])
    draft.value.conditions[resource] = emptyGroup()
  if (scope !== 'custom' && previewing.value === resource) previewing.value = null
}

const incomplete = computed(() =>
  Object.entries(draft.value.scopes).some(
    ([resource, scope]) => scope === 'custom' && !isComplete(draft.value.conditions[resource]!),
  ),
)

function openCreate() {
  editing.value = null
  previewing.value = null
  draft.value = { code: '', name: '', permissions: [], ...scopesOf([]) }
  dialogOpen.value = true
}

function openEdit(role: Role) {
  editing.value = role
  previewing.value = null
  draft.value = {
    code: role.code,
    name: role.name,
    permissions: [...role.permissions],
    ...scopesOf(role.dataPolicies),
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
  const policies = policiesOf(draft.value)
  const done = await run(async () => {
    if (!role) {
      // 授权与数据范围随新建一次提交，后端同一事务写入
      await props.api.createRole(draft.value.code.trim(), name, draft.value.permissions, policies)
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
      width="820px"
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
            <div v-for="resource in dataResources" :key="resource.code" class="scope-resource">
              <div class="scope-row">
                <span class="scope-name">{{ resource.name }}</span>
                <el-radio-group
                  :model-value="draft.scopes[resource.code]"
                  @update:model-value="(scope) => choose(resource.code, scope as ScopeChoice)"
                >
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
                  <el-radio value="custom" :data-testid="`scope-${resource.code}-custom`">
                    {{ t('role.scope.custom') }}
                  </el-radio>
                </el-radio-group>
              </div>
              <div
                v-if="draft.scopes[resource.code] === 'custom'"
                class="condition"
                :data-testid="`condition-${resource.code}`"
              >
                <ConditionGroup
                  :node="draft.conditions[resource.code]!"
                  :resource-attributes="resource.attributes"
                  :options="conditionOptions"
                  root
                />
                <div class="condition-actions">
                  <span v-if="!isComplete(draft.conditions[resource.code]!)" class="group-title">
                    {{ t('role.condition.incomplete') }}
                  </span>
                  <el-button
                    v-else
                    link
                    type="primary"
                    :data-testid="`preview-${resource.code}`"
                    @click="previewing = previewing === resource.code ? null : resource.code"
                  >
                    {{ t('role.preview.toggle') }}
                  </el-button>
                </div>
                <ConditionPreview
                  v-if="
                    previewing === resource.code && isComplete(draft.conditions[resource.code]!)
                  "
                  :api="api"
                  :policy="policyOf(resource.code, draft)!"
                  :attributes="resource.attributes"
                  :options="conditionOptions"
                />
              </div>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <ErrorAlert v-if="error" :tip="error" />
      <template #footer>
        <el-button @click="dialogOpen = false">{{ t('role.dialog.cancel') }}</el-button>
        <el-button
          type="primary"
          :disabled="busy || !draft.name.trim() || !draft.code.trim() || incomplete"
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
.condition {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 4px 0 8px 76px;
}
.condition-actions {
  display: flex;
  gap: 8px;
}
</style>
