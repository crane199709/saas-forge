<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@crane199709/sdk'
import { ErrorAlert, type ErrorTip } from '@crane199709/ui'
import { errorTip } from '../error-tip'
import type { Plan } from '../plan/plan-api'
import PlanImpactDialog from './PlanImpactDialog.vue'
import type {
  CreateTenant,
  InitializationStatus,
  PlanImpactPreview,
  Tenant,
  TenantApi,
  TenantDetail,
  TenantStatus,
} from './tenant-api'

// plans 由 Shell 取得，用于创建时选择、列表与详情中展示及更换套餐；
// canManage 为 false 时只读（缺少 platform:tenant:manage），隐藏创建、更换套餐、变更状态与重试
const props = withDefaults(defineProps<{ api: TenantApi; plans?: Plan[]; canManage?: boolean }>(), {
  plans: () => [],
  canManage: true,
})
const { t } = useI18n()
const size = 20
const tenants = ref<Tenant[]>([])
const page = ref(1)
const total = ref(0)
const detail = ref<TenantDetail | null>(null)
const creating = ref(false)
const emptyDraft = () => ({
  name: '',
  adminContact: '',
  region: '',
  status: 'active' as CreateTenant['status'],
  expiresAt: '',
  planId: '',
})
const draft = ref(emptyDraft())
const emptyTransition = () => ({ status: '' as TenantStatus | '', reason: '', expiresAt: '' })
const transitionDraft = ref(emptyTransition())
const planDraft = ref('')
const pendingPlan = ref<{ tenantId: string; planId: string; preview: PlanImpactPreview } | null>(
  null,
)
const clearing = ref<{ tenantId: string; tenantName: string } | null>(null)
const clearReason = ref('')
const clearError = ref<ErrorTip | null>(null)
const clearBusy = ref(false)
const cleared = ref(false)
watch(
  () => detail.value?.tenant.id,
  () => {
    clearing.value = null
    cleared.value = false
  },
)
function startClear() {
  if (!props.canManage || !detail.value) return
  clearing.value = { tenantId: detail.value.tenant.id, tenantName: detail.value.tenant.name }
  clearReason.value = ''
  clearError.value = null
}
async function confirmClear() {
  const target = clearing.value
  if (!props.canManage || !target || !clearReason.value.trim() || clearBusy.value) return
  if (target.tenantId !== detail.value?.tenant.id) return
  clearBusy.value = true
  clearError.value = null
  try {
    await props.api.clearIpAllowlist(target.tenantId, clearReason.value.trim())
    if (target !== clearing.value) return
    cleared.value = true
    clearing.value = null
  } catch (cause) {
    if (target === clearing.value) clearError.value = errorTip(cause, t)
  } finally {
    clearBusy.value = false
  }
}
const busy = ref(false)
const error = ref<ErrorTip | null>(null)

const statusType: Record<InitializationStatus, 'info' | 'success' | 'danger'> = {
  pending: 'info',
  succeeded: 'success',
  failed: 'danger',
}

// 到期时间只随迁移到试用或正常设置
const expiryApplies = (status: string) => status === 'trial' || status === 'active'
// 冷静期内唯一可选的迁移是回到删除前的状态，即撤回删除
const withdrawing = computed(() => detail.value?.tenant.status === 'deleted')

// datetime-local 输入是本地时间，提交时转为带时区的 ISO 时间
const toInstant = (local: string) => (local ? new Date(local).toISOString() : undefined)
const formatTime = (value: string | null) => (value ? new Date(value).toLocaleString() : '')
const planName = (id: string | null) =>
  id === null
    ? t('tenant.noPlan')
    : (props.plans.find((plan) => plan.id === id)?.name ?? t('tenant.unknownPlan'))

// 优先展示服务端已翻译的 userTip，其余故障显示通用提示。
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
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}

async function fetchPage(number: number) {
  const result = await props.api.list(number, size)
  tenants.value = result.items
  page.value = result.page.number
  total.value = result.page.totalElements
}

const load = (number = page.value) => run(() => fetchPage(number))

const open = (id: string) =>
  run(async () => {
    detail.value = await props.api.get(id)
    transitionDraft.value = emptyTransition()
    planDraft.value = ''
  })

// 新租户排在第一页最前，创建后回到第一页并打开详情查看各服务初始化状态。
const create = () =>
  run(async () => {
    const { name, adminContact, region, status, expiresAt, planId } = draft.value
    detail.value = await props.api.create({
      name,
      adminContact,
      region: region.trim() || undefined,
      status,
      expiresAt: toInstant(expiresAt),
      planId,
    })
    creating.value = false
    draft.value = emptyDraft()
    await fetchPage(1)
  })

const transition = () =>
  run(async () => {
    if (!detail.value || !transitionDraft.value.status) return
    const { status, reason, expiresAt } = transitionDraft.value
    detail.value = await props.api.transition(detail.value.tenant.id, {
      status,
      reason: reason || undefined,
      expiresAt: expiryApplies(status) ? toInstant(expiresAt) : undefined,
    })
    transitionDraft.value = emptyTransition()
    await fetchPage(page.value)
  })

async function applyPlan(tenantId: string, planId: string) {
  detail.value = await props.api.changePlan(tenantId, planId)
  planDraft.value = ''
  await fetchPage(page.value)
}

async function previewPlan(tenantId: string, planId: string): Promise<PlanImpactPreview> {
  try {
    return { ok: true, value: await props.api.planImpact(tenantId, planId) }
  } catch (cause) {
    return { ok: false, tip: errorTip(cause, t) }
  }
}

// 配额降低但未超出时成员不受影响，不算需要确认。
const needsConfirmation = (preview: PlanImpactPreview) =>
  !preview.ok ||
  preview.value.lostPermissions.length > 0 ||
  preview.value.lostSubApps.length > 0 ||
  preview.value.membersOverQuota > 0 ||
  preview.value.affectedMemberships > 0

const changePlan = () =>
  run(async () => {
    if (!detail.value || !planDraft.value) return
    const tenantId = detail.value.tenant.id
    const planId = planDraft.value
    const preview = await previewPlan(tenantId, planId)
    // 预览期间抽屉可能已关闭或换成其他租户，这次更换已作废
    if (detail.value?.tenant.id !== tenantId) return
    if (needsConfirmation(preview)) pendingPlan.value = { tenantId, planId, preview }
    else await applyPlan(tenantId, planId)
  })

function confirmPlan() {
  const { tenantId, planId } = pendingPlan.value!
  pendingPlan.value = null
  return run(() => applyPlan(tenantId, planId))
}

// 重试后状态回到 pending，服务回报前需手动刷新查看结果。
const retry = (service: string) =>
  run(async () => {
    if (!detail.value) return
    const updated = await props.api.retry(detail.value.tenant.id, service)
    detail.value = {
      ...detail.value,
      initializations: detail.value.initializations.map((item) =>
        item.service === service ? updated : item,
      ),
    }
  })

onMounted(() => load(1))
</script>

<template>
  <section class="tenant-panel" :aria-label="t('tenant.title')">
    <header>
      <h2>{{ t('tenant.title') }}</h2>
      <el-button v-if="canManage" type="primary" @click="creating = true">{{
        t('tenant.create')
      }}</el-button>
    </header>
    <ErrorAlert v-if="error" :tip="error" />

    <el-table :data="tenants" :empty-text="t('tenant.empty')">
      <el-table-column prop="name" :label="t('tenant.name')" />
      <el-table-column prop="adminContact" :label="t('tenant.adminContact')" />
      <el-table-column :label="t('tenant.region')">
        <template #default="{ row }">{{ row.region ?? t('tenant.noRegion') }}</template>
      </el-table-column>
      <el-table-column :label="t('tenant.plan')">
        <template #default="{ row }">{{ planName(row.planId) }}</template>
      </el-table-column>
      <el-table-column :label="t('tenant.status')">
        <template #default="{ row }">{{ t(`tenant.statuses.${row.status}`) }}</template>
      </el-table-column>
      <el-table-column :label="t('tenant.actions')">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row.id)">{{ t('tenant.detail') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      layout="prev, pager, next"
      :current-page="page"
      :page-size="size"
      :total="total"
      @current-change="load"
    />

    <el-dialog v-model="creating" :title="t('tenant.create')">
      <el-form label-position="top" @submit.prevent="create">
        <el-form-item :label="t('tenant.name')" for="tenant-name">
          <el-input id="tenant-name" v-model="draft.name" name="name" maxlength="120" required />
        </el-form-item>
        <el-form-item :label="t('tenant.adminContact')" for="tenant-admin-contact">
          <el-input
            id="tenant-admin-contact"
            v-model="draft.adminContact"
            name="adminContact"
            :placeholder="t('tenant.adminContactHint')"
            maxlength="254"
            required
          />
        </el-form-item>
        <el-form-item :label="t('tenant.region')" for="tenant-region">
          <el-input
            id="tenant-region"
            v-model="draft.region"
            name="region"
            :placeholder="t('tenant.regionHint')"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item :label="t('tenant.plan')">
          <el-select
            v-model="draft.planId"
            :placeholder="t('tenant.choosePlan')"
            data-testid="tenant-plan"
          >
            <el-option v-for="plan in plans" :key="plan.id" :label="plan.name" :value="plan.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('tenant.initialStatus')">
          <el-radio-group v-model="draft.status">
            <el-radio-button value="active">{{ t('tenant.statuses.active') }}</el-radio-button>
            <el-radio-button value="trial">{{ t('tenant.statuses.trial') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="t('tenant.expiresAt')" for="tenant-expires-at">
          <input
            id="tenant-expires-at"
            v-model="draft.expiresAt"
            class="native-input"
            name="expiresAt"
            type="datetime-local"
          />
        </el-form-item>
        <el-button native-type="submit" type="primary" :disabled="!draft.planId" :loading="busy">{{
          t('tenant.submit')
        }}</el-button>
      </el-form>
    </el-dialog>

    <el-drawer
      size="min(560px, calc(100vw - 24px))"
      :model-value="detail !== null"
      :title="detail?.tenant.name"
      @close="detail = null"
    >
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('tenant.status')">{{
            t(`tenant.statuses.${detail.tenant.status}`)
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('tenant.region')">{{
            detail.tenant.region ?? t('tenant.noRegion')
          }}</el-descriptions-item>
          <el-descriptions-item :label="t('tenant.plan')">{{
            planName(detail.tenant.planId)
          }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.tenant.expiresAt" :label="t('tenant.expiresAt')">{{
            formatTime(detail.tenant.expiresAt)
          }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.tenant.purgedAt" :label="t('tenant.purgedAt')">{{
            formatTime(detail.tenant.purgedAt)
          }}</el-descriptions-item>
          <el-descriptions-item v-else-if="detail.tenant.purgeAt" :label="t('tenant.purgeAt')">{{
            formatTime(detail.tenant.purgeAt)
          }}</el-descriptions-item>
        </el-descriptions>

        <template v-if="canManage">
          <h3>租户 IP 允许列表</h3>
          <p>成员因来源 IP 被拒时，可紧急清除限制。清除后所有来源 IP 均可访问该租户。</p>
          <el-button type="danger" plain data-testid="ip-clear-open" @click="startClear"
            >紧急清除 IP 允许列表</el-button
          >
          <p v-if="cleared" role="status">租户 IP 允许列表已清除。</p>
          <h3>{{ t('tenant.changePlan') }}</h3>
          <p>{{ t('tenant.changePlanHint') }}</p>
          <el-form class="change-plan" inline @submit.prevent="changePlan">
            <el-select
              v-model="planDraft"
              :placeholder="t('tenant.choosePlan')"
              data-testid="tenant-change-plan"
            >
              <el-option
                v-for="plan in plans.filter((item) => item.id !== detail?.tenant.planId)"
                :key="plan.id"
                :label="plan.name"
                :value="plan.id"
              />
            </el-select>
            <el-button native-type="submit" type="primary" :disabled="!planDraft" :loading="busy">{{
              t('tenant.changePlan')
            }}</el-button>
          </el-form>

          <h3>{{ t('tenant.lifecycle') }}</h3>
          <p v-if="detail.allowedTransitions.length === 0">{{ t('tenant.noTransitions') }}</p>
          <el-form v-else class="transition" label-position="top" @submit.prevent="transition">
            <el-form-item :label="withdrawing ? t('tenant.withdrawTo') : t('tenant.targetStatus')">
              <el-radio-group v-model="transitionDraft.status">
                <el-radio-button
                  v-for="target in detail.allowedTransitions"
                  :key="target"
                  :value="target"
                  >{{ t(`tenant.statuses.${target}`) }}</el-radio-button
                >
              </el-radio-group>
            </el-form-item>
            <p v-if="transitionDraft.status === 'deleted'" class="warning">
              {{ t('tenant.deletionHint') }}
            </p>
            <el-form-item
              v-if="expiryApplies(transitionDraft.status)"
              :label="t('tenant.expiresAt')"
              for="transition-expires-at"
            >
              <input
                id="transition-expires-at"
                v-model="transitionDraft.expiresAt"
                class="native-input"
                name="expiresAt"
                type="datetime-local"
              />
            </el-form-item>
            <el-form-item :label="t('tenant.reason')" for="transition-reason">
              <el-input
                id="transition-reason"
                v-model="transitionDraft.reason"
                name="reason"
                maxlength="500"
              />
            </el-form-item>
            <el-button
              native-type="submit"
              type="primary"
              :disabled="!transitionDraft.status"
              :loading="busy"
              >{{ withdrawing ? t('tenant.withdraw') : t('tenant.transition') }}</el-button
            >
          </el-form>
        </template>

        <h3>{{ t('tenant.history') }}</h3>
        <el-table class="history" :data="detail.statusChanges" :empty-text="t('tenant.noHistory')">
          <el-table-column :label="t('tenant.change')">
            <template #default="{ row }">
              {{ row.fromStatus ? t(`tenant.statuses.${row.fromStatus}`) : t('tenant.created') }}
              → {{ t(`tenant.statuses.${row.toStatus}`) }}
            </template>
          </el-table-column>
          <el-table-column prop="reason" :label="t('tenant.reason')" />
          <el-table-column :label="t('tenant.changedAt')">
            <template #default="{ row }">{{ formatTime(row.changedAt) }}</template>
          </el-table-column>
        </el-table>

        <h3>{{ t('tenant.initialization') }}</h3>
        <p>{{ t('tenant.provisioningHint') }}</p>
        <el-button :loading="busy" @click="open(detail.tenant.id)">{{
          t('tenant.refresh')
        }}</el-button>
        <el-table :data="detail.initializations" :empty-text="t('tenant.noInitializations')">
          <el-table-column prop="service" :label="t('tenant.service')" />
          <el-table-column :label="t('tenant.initialization')">
            <template #default="{ row }">
              <el-tag :type="statusType[row.status as InitializationStatus]">{{
                t(`tenant.initializationStatuses.${row.status}`)
              }}</el-tag>
              <p v-if="row.error" class="failure">{{ row.error }}</p>
            </template>
          </el-table-column>
          <el-table-column :label="t('tenant.actions')">
            <template #default="{ row }">
              <el-button
                v-if="canManage && row.status === 'failed'"
                link
                type="primary"
                :loading="busy"
                @click="retry(row.service)"
                >{{ t('tenant.retry') }}</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
    <el-dialog
      :model-value="clearing !== null"
      title="确认紧急清除 IP 允许列表"
      width="min(480px, calc(100vw - 32px))"
      :close-on-click-modal="false"
      :show-close="!clearBusy"
      :close-on-press-escape="!clearBusy"
      @close="clearing = null"
    >
      <p>
        即将清除「{{ clearing?.tenantName }}」的 IP 允许列表，所有来源 IP
        将立即恢复访问。请确认目标并填写原因。
      </p>
      <ErrorAlert v-if="clearError" :tip="clearError" />
      <el-form label-position="top" @submit.prevent="confirmClear">
        <el-form-item label="清除原因（必填）" for="ip-clear-reason">
          <el-input
            id="ip-clear-reason"
            v-model="clearReason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :disabled="clearBusy"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="clearBusy" @click="clearing = null">取消</el-button>
        <el-button
          type="danger"
          data-testid="ip-clear-confirm"
          :loading="clearBusy"
          :disabled="!clearReason.trim() || clearBusy"
          @click="confirmClear"
          >确认清除</el-button
        >
      </template>
    </el-dialog>
    <PlanImpactDialog
      v-if="pendingPlan"
      :plan-name="planName(pendingPlan.planId)"
      :preview="pendingPlan.preview"
      @confirm="confirmPlan"
      @cancel="pendingPlan = null"
    />
  </section>
</template>

<style scoped>
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.warning {
  color: var(--el-color-warning);
}
.native-input {
  width: 100%;
  padding: 4px 8px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  font: inherit;
}
.failure {
  margin: 4px 0 0;
  color: var(--el-color-danger);
  overflow-wrap: anywhere;
}
</style>
