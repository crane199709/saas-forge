<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@crane199709/sdk'
import {
  ErrorAlert,
  PageHeader,
  PermissionTreeEditor,
  menuRows,
  overlaySize,
  type ErrorTip,
  type PermissionSection,
} from '@crane199709/ui'
import type { CatalogPermissionTree, Plan, PlanApi } from './plan-api'
import {
  planTree,
  retiredCodes,
  selectionAfterSubApps,
  unselectedCodes,
} from './plan-permission-tree'

// subApps：App Registry 中已登记的编码，作为 Feature Pack 的选项；仍可输入尚未登记的编码，登记后随之生效
const props = defineProps<{ api: PlanApi; subApps?: string[] }>()
// 套餐列表变化时通知 Shell，租户面板据此选择与展示套餐
const emit = defineEmits<{ loaded: [plans: Plan[]] }>()
const { t } = useI18n()
const plans = ref<Plan[]>([])
const catalog = ref<CatalogPermissionTree>({ menus: [], others: [] })
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

// 先选 Sub-App，树中只显示所选 Sub-App 的页面；切换 Sub-App 只在前端重新计算，不重新请求。
const tree = computed(() => planTree(catalog.value, draft.value.subApps))
const names = computed(
  () =>
    new Map(
      menuRows(catalog.value.menus).flatMap(({ menu }) =>
        [...(menu.permission ? [menu.permission] : []), ...menu.actions].map(
          (permission) => [permission.code, permission.name] as const,
        ),
      ),
    ),
)
// 不在树中显示但仍勾选的码分区列出，保持勾选、可逐项取消：所属 Sub-App 未选择的，以及已从清单下线的（业务服务重新上报后随之恢复）。
const sections = computed<PermissionSection[]>(() => [
  {
    key: 'unselected',
    title: t('plan.unselectedSubApp'),
    permissions: unselectedCodes(catalog.value, draft.value.subApps, draft.value.permissions).map(
      (code) => ({ code, name: names.value.get(code) ?? code }),
    ),
  },
  {
    key: 'retired',
    title: t('plan.retired'),
    permissions: retiredCodes(catalog.value, draft.value.permissions).map((code) => ({
      code,
      name: code,
    })),
  },
])

function changeSubApps(subApps: string[]) {
  draft.value.permissions = selectionAfterSubApps(
    catalog.value,
    draft.value.permissions,
    draft.value.subApps,
    subApps,
  )
  draft.value.subApps = subApps
}

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
    catalog.value = await props.api.permissionTree()
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
    permissions: [...new Set(permissions)],
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
    <PageHeader :title="t('plan.title')">
      <template #actions>
        <el-button type="primary" :loading="busy" data-testid="plan-create" @click="open(null)">{{
          t('plan.create')
        }}</el-button>
      </template>
    </PageHeader>
    <ErrorAlert v-if="error && !dialogOpen" class="page-error" :tip="error" />

    <el-table :data="plans" row-key="id" :empty-text="t('plan.empty')">
      <el-table-column prop="name" :label="t('plan.name')" min-width="140" show-overflow-tooltip />
      <el-table-column prop="code" :label="t('plan.code')" min-width="110" show-overflow-tooltip />
      <el-table-column :label="t('plan.permissions')" width="100">
        <template #default="{ row }">{{
          t('plan.permissionCount', { count: row.permissions.length })
        }}</template>
      </el-table-column>
      <el-table-column :label="t('plan.subApps')" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ row.subApps.join('、') || '—' }}</template>
      </el-table-column>
      <el-table-column :label="t('plan.memberQuota')" width="110" align="right">
        <template #default="{ row }">{{ quota(row.memberQuota) }}</template>
      </el-table-column>
      <el-table-column :label="t('plan.storageQuota')" width="120" align="right">
        <template #default="{ row }">{{ quota(row.storageQuotaMb, ' MiB') }}</template>
      </el-table-column>
      <el-table-column :label="t('plan.actions')" width="112" fixed="right">
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

    <el-drawer
      v-model="dialogOpen"
      :size="overlaySize.drawerLg"
      :title="editing ? t('plan.editTitle', { name: editing.name }) : t('plan.create')"
    >
      <ErrorAlert v-if="error" :tip="error" />
      <el-alert
        v-if="editing"
        class="page-error"
        type="warning"
        :closable="false"
        :title="t('plan.editHint')"
      />
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
        <el-form-item :label="t('plan.subApps')">
          <el-select
            :model-value="draft.subApps"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            :placeholder="t('plan.subAppsHint')"
            data-testid="plan-sub-apps"
            @update:model-value="changeSubApps"
          >
            <el-option
              v-for="code in props.subApps ?? []"
              :key="code"
              :label="code"
              :value="code"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('plan.permissions')">
          <p v-if="draft.subApps.length === 0" class="hint">{{ t('plan.noSubApp') }}</p>
          <PermissionTreeEditor
            v-model="draft.permissions"
            :tree="tree"
            :sections="sections"
            page-as-child
          />
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
    </el-drawer>
  </section>
</template>

<style scoped>
.page-error {
  margin-bottom: var(--forge-space-3);
}
.hint {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.unit {
  margin-left: var(--forge-space-2);
}
</style>
