<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ErrorAlert, overlaySize } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import RestoreView from '../resource/RestoreView.vue'
import ConditionGroup from './ConditionGroup.vue'
import ConditionPreview from './ConditionPreview.vue'
import PermissionTreeEditor from './PermissionTreeEditor.vue'
import RoleImpactDialog from './RoleImpactDialog.vue'
import { menuRows } from './permission-selection'
import {
  emptyGroup,
  fromConditionJson,
  isComplete,
  toConditionJson,
  type GroupNode,
} from './condition'
import {
  type ConditionOptions,
  type DataPolicy,
  type DataResource,
  type DataScopeName,
  type ImpactPreview,
  type PermissionTree,
  type Role,
  type RoleApi,
  type RoleDataPolicyImpact,
  type RolePermissionImpact,
} from './role-api'

const props = defineProps<{
  api: RoleApi
  roles: Role[]
  permissionTree: PermissionTree
  dataResources: DataResource[]
  conditionOptions: ConditionOptions
}>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n()
const names = computed(
  () =>
    new Map(
      [
        ...props.permissionTree.others,
        ...menuRows(props.permissionTree.menus).flatMap(({ menu }) => [
          ...(menu.permission ? [menu.permission] : []),
          ...menu.actions,
        ]),
      ].map((item) => [item.code, item.name]),
    ),
)
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
// 每次打开编辑框或保存时递增，预览返回时据此丢弃已作废的保存
let saveAttempt = 0

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
  saveAttempt++
  editing.value = null
  previewing.value = null
  draft.value = { code: '', name: '', permissions: [], ...scopesOf([]) }
  dialogOpen.value = true
}

function openEdit(role: Role) {
  saveAttempt++
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

// 提交时的快照：预览期间编辑框仍可操作，确认后保存的必须是预览过的内容。
interface RoleChange {
  name: string
  permissions: string[]
  policies: DataPolicy[]
}

// 待确认的修改；只保留需要提示的预览，没有影响的一项不出现在弹窗中。
const pendingImpact = ref<{
  role: Role
  change: RoleChange
  permissions: ImpactPreview<RolePermissionImpact> | null
  dataPolicies: ImpactPreview<RoleDataPolicyImpact> | null
} | null>(null)

const policiesChanged = (role: Role, policies: DataPolicy[]) =>
  JSON.stringify(policies) !== JSON.stringify(policiesOf(scopesOf(role.dataPolicies)))

const samePermissions = (role: Role, permissions: string[]) =>
  role.permissions.length === permissions.length &&
  permissions.every((code) => role.permissions.includes(code))

const previewOf = async <T,>(load: () => Promise<T>): Promise<ImpactPreview<T>> => {
  try {
    return { ok: true, value: await load() }
  } catch (cause) {
    return { ok: false, tip: errorTip(cause, t) }
  }
}

const needsConfirmation = (preview: ImpactPreview<{ affectedCount: number }> | null) =>
  preview !== null && (!preview.ok || preview.value.affectedCount > 0)

async function commit(role: Role, change: RoleChange) {
  const done = await run(async () => {
    if (change.name !== role.name) await props.api.renameRole(role.id, change.name)
    await props.api.replacePermissions(role.id, change.permissions)
    // 数据范围变化会让持有者的 Token 失效，未改动时不提交
    if (policiesChanged(role, change.policies))
      await props.api.replaceDataPolicies(role.id, change.policies)
  })
  if (done) dialogOpen.value = false
}

async function save() {
  const role = editing.value
  const change: RoleChange = {
    name: draft.value.name.trim(),
    permissions: [...new Set(draft.value.permissions)],
    policies: policiesOf(draft.value),
  }
  if (!role) {
    // 授权与数据范围随新建一次提交，后端同一事务写入
    const done = await run(() =>
      props.api.createRole(
        draft.value.code.trim(),
        change.name,
        change.permissions,
        change.policies,
      ),
    )
    if (done) dialogOpen.value = false
    return
  }
  const attempt = ++saveAttempt
  busy.value = true
  error.value = null
  const [permissions, dataPolicies] = await Promise.all([
    samePermissions(role, change.permissions)
      ? null
      : previewOf(() => props.api.permissionImpact(role.id, change.permissions)),
    policiesChanged(role, change.policies)
      ? previewOf(() => props.api.dataPolicyImpact(role.id, change.policies))
      : null,
  ])
  busy.value = false
  // 预览期间用户可能已关闭编辑框或打开了其他角色，这次保存已作废
  if (attempt !== saveAttempt || !dialogOpen.value) return
  if (needsConfirmation(permissions) || needsConfirmation(dataPolicies)) {
    pendingImpact.value = {
      role,
      change,
      permissions: needsConfirmation(permissions) ? permissions : null,
      dataPolicies: needsConfirmation(dataPolicies) ? dataPolicies : null,
    }
    return
  }
  await commit(role, change)
}

function confirmImpact() {
  const { role, change } = pendingImpact.value!
  pendingImpact.value = null
  return commit(role, change)
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
  <RestoreView
    :load-deleted="() => api.roles(true)"
    :restore="api.restoreRole"
    :disabled="busy"
    show-code
    @restored="emit('changed')"
  >
    <template #actions>
      <el-button type="primary" data-testid="role-create" @click="openCreate">
        {{ t('role.list.create') }}
      </el-button>
    </template>
    <ErrorAlert v-if="error && !dialogOpen" class="list-error" :tip="error" />
    <el-table :data="roles" row-key="id">
      <el-table-column :label="t('role.list.name')" min-width="160">
        <template #default="{ row }">
          <span class="role-name">
            <span class="role-name-text">{{ row.name }}</span>
            <el-tag v-if="row.builtin" size="small" type="info">
              {{ t('role.list.builtin') }}
            </el-tag>
          </span>
        </template>
      </el-table-column>
      <el-table-column
        prop="code"
        :label="t('role.list.code')"
        min-width="140"
        show-overflow-tooltip
      />
      <el-table-column
        prop="memberCount"
        :label="t('role.list.members')"
        width="88"
        align="right"
      />
      <el-table-column :label="t('role.list.permissions')" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span :data-testid="`role-permissions-${row.code}`">{{
            summary(row as Role) || '—'
          }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('role.list.dataScopes')" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span :data-testid="`role-scopes-${row.code}`">{{
            scopeSummary(row as Role) || '—'
          }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('role.list.actions')" width="112" fixed="right">
        <template #default="{ row }">
          <template v-if="!row.builtin">
            <el-button
              link
              type="primary"
              :data-testid="`role-edit-${row.code}`"
              @click="openEdit(row as Role)"
            >
              {{ t('role.list.edit') }}
            </el-button>
            <el-button
              link
              type="danger"
              :disabled="busy"
              :data-testid="`role-delete-${row.code}`"
              @click="run(() => api.deleteRole(row.id))"
            >
              {{ t('role.list.delete') }}
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <el-drawer
      v-model="dialogOpen"
      :size="overlaySize.drawerLg"
      :title="editing ? t('role.dialog.edit', { name: editing.name }) : t('role.dialog.create')"
    >
      <el-form class="role-form" label-position="top">
        <div class="basic-fields">
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
        </div>
        <el-form-item :label="t('role.list.permissions')">
          <PermissionTreeEditor v-model="draft.permissions" :tree="permissionTree" />
        </el-form-item>
        <el-form-item v-if="dataResources.length > 0" :label="t('role.dialog.dataScopes')">
          <div class="scopes">
            <div class="hint">{{ t('role.dialog.dataScopeHint') }}</div>
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
                  <span v-if="!isComplete(draft.conditions[resource.code]!)" class="hint">
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
    </el-drawer>
    <RoleImpactDialog
      v-if="pendingImpact"
      :role-name="pendingImpact.role.name"
      :permissions="pendingImpact.permissions"
      :data-policies="pendingImpact.dataPolicies"
      :permission-names="names"
      @confirm="confirmImpact"
      @cancel="pendingImpact = null"
    />
  </RestoreView>
</template>

<style scoped>
.list-error {
  margin-bottom: var(--forge-space-3);
}
.role-name {
  display: inline-flex;
  gap: var(--forge-space-2);
  align-items: center;
  max-width: 100%;
}
.role-name-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.role-form :deep(.el-form-item__label) {
  font-weight: 600;
}
.basic-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  column-gap: var(--forge-space-4);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--forge-font-size-xs);
}
.scopes {
  width: 100%;
}
.scope-resource {
  padding: var(--forge-space-2) 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.scope-row {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: var(--forge-space-3);
  align-items: start;
}
.scope-name {
  line-height: 32px;
}
.condition {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-2);
  margin-top: var(--forge-space-2);
  padding: var(--forge-space-3);
  background-color: var(--el-fill-color-lighter);
  border-radius: var(--forge-radius-md);
}
.condition-actions {
  display: flex;
  gap: var(--forge-space-2);
}
</style>
