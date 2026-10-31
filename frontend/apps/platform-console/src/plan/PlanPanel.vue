<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@crane199709/sdk'
import { ErrorAlert, type ErrorTip } from '@crane199709/ui'
import type { CatalogPermission, Plan, PlanApi } from './plan-api'

// subApps：App Registry 中已登记的编码，作为 Feature Pack 的选项；仍可输入尚未登记的编码，登记后随之生效
const props = defineProps<{ api: PlanApi; subApps?: string[] }>()
// 套餐列表变化时通知 Shell，租户面板据此选择与展示套餐
const emit = defineEmits<{ loaded: [plans: Plan[]] }>()
const { t } = useI18n()
const plans = ref<Plan[]>([])
const catalog = ref<CatalogPermission[]>([])
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

// 新建与编辑共用一个对话框：editing 为 null 时新建；编码只在新建时填写。
const dialogOpen = ref(false)
const editing = ref<Plan | null>(null)
const emptyDraft = () => ({
  code: '',
  name: '',
  permissions: [] as string[],
  subApps: [] as string[],
  memberQuota: null as number | null,
  storageQuotaMb: null as number | null,
})
const draft = ref(emptyDraft())

interface PermissionGroup {
  key: string
  title: string
  permissions: { code: string; label: string }[]
}

// 按服务与分组展示目录；套餐中已从清单下线的权限码单独列出，保持勾选，业务服务重新上报后随之恢复。
const groups = computed<PermissionGroup[]>(() => {
  const result = new Map<string, PermissionGroup>()
  for (const permission of catalog.value) {
    const key = `${permission.service}/${permission.group ?? ''}`
    const group = result.get(key) ?? {
      key,
      title: `${permission.service} · ${permission.group ?? t('plan.ungrouped')}`,
      permissions: [],
    }
    group.permissions.push({
      code: permission.code,
      label: `${permission.name}（${permission.code}）`,
    })
    result.set(key, group)
  }
  const known = new Set(catalog.value.map((permission) => permission.code))
  const retired = draft.value.permissions.filter((code) => !known.has(code))
  if (retired.length > 0)
    result.set('retired', {
      key: 'retired',
      title: t('plan.retired'),
      permissions: retired.map((code) => ({ code, label: code })),
    })
  return [...result.values()]
})

const quota = (value: number | null, unit = '') =>
  value === null ? t('plan.unlimited') : `${value}${unit}`

function showError(cause: unknown) {
  const traceId = cause instanceof ApiError ? cause.traceId : undefined
  error.value = {
    message: cause instanceof ApiError && cause.userTip ? cause.userTip : t('error.unknown'),
    traceId,
  }
}

async function run(action: () => Promise<void>) {
  busy.value = true
  error.value = null
  try {
    await action()
    return true
  } catch (cause) {
    showError(cause)
    return false
  } finally {
    busy.value = false
  }
}

async function fetchPlans() {
  plans.value = await props.api.list()
  emit('loaded', plans.value)
}

// 目录随业务服务上报变化，每次打开对话框重新读取
async function open(plan: Plan | null) {
  const loaded = await run(async () => {
    catalog.value = await props.api.permissions()
  })
  if (!loaded) return
  editing.value = plan
  draft.value = plan
    ? {
        code: plan.code,
        name: plan.name,
        permissions: [...plan.permissions],
        subApps: [...plan.subApps],
        memberQuota: plan.memberQuota,
        storageQuotaMb: plan.storageQuotaMb,
      }
    : emptyDraft()
  dialogOpen.value = true
}

async function save() {
  const plan = editing.value
  const { code, name, permissions, subApps, memberQuota, storageQuotaMb } = draft.value
  // el-input-number 清空后为 null 或 undefined，统一按不限提交
  const content = {
    name: name.trim(),
    permissions,
    subApps,
    memberQuota: memberQuota ?? null,
    storageQuotaMb: storageQuotaMb ?? null,
  }
  const done = await run(async () => {
    if (plan) await props.api.update(plan.id, content)
    else await props.api.create({ code: code.trim(), ...content })
    await fetchPlans()
  })
  if (done) dialogOpen.value = false
}

const remove = (plan: Plan) =>
  run(async () => {
    await props.api.remove(plan.id)
    await fetchPlans()
  })

onMounted(() => run(fetchPlans))
</script>

<template>
  <section class="plan-panel" :aria-label="t('plan.title')">
    <header>
      <h2>{{ t('plan.title') }}</h2>
      <el-button type="primary" :loading="busy" data-testid="plan-create" @click="open(null)">{{
        t('plan.create')
      }}</el-button>
    </header>
    <ErrorAlert v-if="error && !dialogOpen" :tip="error" />

    <el-table :data="plans" :empty-text="t('plan.empty')">
      <el-table-column prop="name" :label="t('plan.name')" />
      <el-table-column prop="code" :label="t('plan.code')" />
      <el-table-column :label="t('plan.permissions')">
        <template #default="{ row }">{{
          t('plan.permissionCount', { count: row.permissions.length })
        }}</template>
      </el-table-column>
      <el-table-column :label="t('plan.subApps')">
        <template #default="{ row }">{{ row.subApps.join('、') }}</template>
      </el-table-column>
      <el-table-column :label="t('plan.memberQuota')">
        <template #default="{ row }">{{ quota(row.memberQuota) }}</template>
      </el-table-column>
      <el-table-column :label="t('plan.storageQuota')">
        <template #default="{ row }">{{ quota(row.storageQuotaMb, ' MiB') }}</template>
      </el-table-column>
      <el-table-column :label="t('plan.actions')">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="busy"
            :data-testid="`plan-edit-${row.code}`"
            @click="open(row as Plan)"
            >{{ t('plan.edit') }}</el-button
          >
          <el-button
            link
            type="danger"
            :disabled="busy"
            :data-testid="`plan-delete-${row.code}`"
            @click="remove(row as Plan)"
            >{{ t('plan.delete') }}</el-button
          >
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogOpen"
      width="720px"
      :title="editing ? t('plan.editTitle', { name: editing.name }) : t('plan.create')"
    >
      <ErrorAlert v-if="error" :tip="error" />
      <p v-if="editing" class="hint">{{ t('plan.editHint') }}</p>
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('plan.code')" for="plan-code">
          <el-input
            id="plan-code"
            v-model="draft.code"
            name="code"
            :disabled="editing !== null"
            :placeholder="t('plan.codeHint')"
            maxlength="64"
            required
          />
        </el-form-item>
        <el-form-item :label="t('plan.name')" for="plan-name">
          <el-input id="plan-name" v-model="draft.name" name="name" maxlength="120" required />
        </el-form-item>
        <el-form-item :label="t('plan.permissions')">
          <el-checkbox-group v-model="draft.permissions" class="permissions">
            <div v-for="group in groups" :key="group.key" class="permission-group">
              <div class="group-title">{{ group.title }}</div>
              <el-checkbox
                v-for="permission in group.permissions"
                :key="permission.code"
                :value="permission.code"
                :data-testid="`plan-perm-${permission.code}`"
                >{{ permission.label }}</el-checkbox
              >
            </div>
          </el-checkbox-group>
          <p class="hint">{{ t('plan.permissionsHint') }}</p>
        </el-form-item>
        <el-form-item :label="t('plan.subApps')">
          <el-select
            v-model="draft.subApps"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            :placeholder="t('plan.subAppsHint')"
            data-testid="plan-sub-apps"
          >
            <el-option
              v-for="code in props.subApps ?? []"
              :key="code"
              :label="code"
              :value="code"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('plan.memberQuota')">
          <el-input-number
            v-model="draft.memberQuota"
            :min="1"
            :step="1"
            step-strictly
            :value-on-clear="null"
            :placeholder="t('plan.unlimited')"
            data-testid="plan-member-quota"
          />
        </el-form-item>
        <el-form-item :label="t('plan.storageQuota')">
          <el-input-number
            v-model="draft.storageQuotaMb"
            :min="1"
            :step="1024"
            :value-on-clear="null"
            :placeholder="t('plan.unlimited')"
            data-testid="plan-storage-quota"
          />
          <span class="unit">MiB</span>
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('plan.save')
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
.permissions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.group-title {
  font-weight: 600;
}
.unit {
  margin-left: 8px;
}
</style>
