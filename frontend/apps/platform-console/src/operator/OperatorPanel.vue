<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@crane199709/sdk'
import { ErrorAlert, type ErrorTip } from '@crane199709/ui'
import type { PlatformRole, PlatformRoleApi } from '../role/platform-role-api'
import type { Operator, OperatorApi } from './operator-api'

// roleApi 只用于读取可分配的平台角色；角色本身在平台角色面板中维护
const props = defineProps<{ api: OperatorApi; roleApi: PlatformRoleApi }>()
const { t } = useI18n()
const operators = ref<Operator[]>([])
const roles = ref<PlatformRole[]>([])
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

// 新建与编辑共用一个对话框：editing 为 null 时新建；账号与初始密码只在新建时填写。
const dialogOpen = ref(false)
const editing = ref<Operator | null>(null)
const emptyDraft = () => ({
  account: '',
  displayName: '',
  password: '',
  region: '',
  roleIds: [] as string[],
})
const draft = ref(emptyDraft())

const passwordTarget = ref<Operator | null>(null)
const newPassword = ref('')

const roleNames = (ids: string[]) =>
  ids
    .map((id) => roles.value.find((role) => role.id === id)?.name)
    .filter((name) => name !== undefined)
    .join('、')

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

async function fetchOperators() {
  operators.value = await props.api.list()
}

function open(operator: Operator | null) {
  error.value = null
  editing.value = operator
  draft.value = operator
    ? {
        account: operator.account,
        displayName: operator.displayName,
        password: '',
        region: operator.region ?? '',
        roleIds: [...operator.roleIds],
      }
    : emptyDraft()
  dialogOpen.value = true
}

const sameRoles = (a: string[], b: string[]) =>
  a.length === b.length && a.every((id) => b.includes(id))

async function save() {
  const operator = editing.value
  const { account, displayName, password, region, roleIds } = draft.value
  const done = await run(async () => {
    const content = { displayName: displayName.trim(), region: region.trim() || undefined }
    if (operator) {
      await props.api.update(operator.id, content)
      // 角色变化即时生效：该运营人员的旧 Token 失效，刷新后按新权限处理
      if (!sameRoles(operator.roleIds, roleIds)) await props.api.assignRoles(operator.id, roleIds)
    } else {
      await props.api.create({ account: account.trim(), password, roleIds, ...content })
    }
    await fetchOperators()
  })
  if (done) dialogOpen.value = false
}

const toggle = (operator: Operator) =>
  run(async () => {
    if (operator.status === 'active') await props.api.disable(operator.id)
    else await props.api.enable(operator.id)
    await fetchOperators()
  })

function openPassword(operator: Operator) {
  error.value = null
  passwordTarget.value = operator
  newPassword.value = ''
}

async function resetPassword() {
  const operator = passwordTarget.value
  if (!operator) return
  const done = await run(async () => {
    await props.api.resetPassword(operator.id, newPassword.value)
  })
  if (done) passwordTarget.value = null
}

onMounted(() =>
  run(async () => {
    const [list, roleList] = await Promise.all([props.api.list(), props.roleApi.list()])
    operators.value = list
    roles.value = roleList
  }),
)
</script>

<template>
  <section :aria-label="t('operator.title')">
    <header>
      <h2>{{ t('operator.title') }}</h2>
      <el-button type="primary" :loading="busy" data-testid="operator-create" @click="open(null)">{{
        t('operator.create')
      }}</el-button>
    </header>
    <ErrorAlert v-if="error && !dialogOpen && !passwordTarget" :tip="error" />

    <el-table :data="operators" :empty-text="t('operator.empty')">
      <el-table-column prop="account" :label="t('operator.account')" />
      <el-table-column prop="displayName" :label="t('operator.displayName')" />
      <el-table-column :label="t('operator.region')">
        <template #default="{ row }">{{ row.region ?? t('operator.noRegion') }}</template>
      </el-table-column>
      <el-table-column :label="t('operator.roles')">
        <template #default="{ row }">{{ roleNames(row.roleIds) }}</template>
      </el-table-column>
      <el-table-column :label="t('operator.status')">
        <template #default="{ row }">
          <el-tag :type="row.status === 'active' ? 'success' : 'info'">{{
            t(`operator.statuses.${row.status}`)
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('operator.actions')" min-width="200">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="busy"
            :data-testid="`operator-edit-${row.account}`"
            @click="open(row as Operator)"
            >{{ t('operator.edit') }}</el-button
          >
          <el-button
            link
            :type="row.status === 'active' ? 'danger' : 'primary'"
            :disabled="busy"
            :data-testid="`operator-toggle-${row.account}`"
            @click="toggle(row as Operator)"
            >{{ row.status === 'active' ? t('operator.disable') : t('operator.enable') }}</el-button
          >
          <el-button
            link
            type="primary"
            :disabled="busy"
            :data-testid="`operator-password-${row.account}`"
            @click="openPassword(row as Operator)"
            >{{ t('operator.resetPassword') }}</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogOpen"
      width="min(560px, 92vw)"
      :title="
        editing ? t('operator.editTitle', { name: editing.displayName }) : t('operator.create')
      "
    >
      <ErrorAlert v-if="error" :tip="error" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('operator.account')" for="operator-account">
          <el-input
            id="operator-account"
            v-model="draft.account"
            :disabled="editing !== null"
            maxlength="120"
            required
          />
        </el-form-item>
        <el-form-item :label="t('operator.displayName')" for="operator-display-name">
          <el-input
            id="operator-display-name"
            v-model="draft.displayName"
            maxlength="120"
            required
          />
        </el-form-item>
        <el-form-item v-if="!editing" :label="t('operator.password')" for="operator-password">
          <el-input
            id="operator-password"
            v-model="draft.password"
            type="password"
            minlength="8"
            maxlength="72"
            required
          />
        </el-form-item>
        <el-form-item :label="t('operator.region')" for="operator-region">
          <el-input
            id="operator-region"
            v-model="draft.region"
            :placeholder="t('operator.regionHint')"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item :label="t('operator.roles')">
          <el-checkbox-group v-model="draft.roleIds" data-testid="operator-roles">
            <el-checkbox v-for="role in roles" :key="role.id" :value="role.id">{{
              role.name
            }}</el-checkbox>
          </el-checkbox-group>
          <p class="hint">{{ t('operator.rolesHint') }}</p>
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('operator.save')
        }}</el-button>
      </el-form>
    </el-dialog>

    <el-dialog
      :model-value="passwordTarget !== null"
      width="min(420px, 92vw)"
      :title="t('operator.resetPasswordTitle', { name: passwordTarget?.displayName ?? '' })"
      @close="passwordTarget = null"
    >
      <ErrorAlert v-if="error" :tip="error" />
      <p class="hint">{{ t('operator.resetPasswordHint') }}</p>
      <el-form label-position="top" @submit.prevent="resetPassword">
        <el-form-item :label="t('operator.newPassword')" for="operator-new-password">
          <el-input
            id="operator-new-password"
            v-model="newPassword"
            type="password"
            minlength="8"
            maxlength="72"
            required
          />
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('operator.resetPassword')
        }}</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>

<style scoped>
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hint {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
}
</style>
