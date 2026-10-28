<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ApiError } from '@forge/sdk'
import type { InitializationStatus, Tenant, TenantApi, TenantDetail } from './tenant-api'

const props = defineProps<{ api: TenantApi }>()
const { t } = useI18n()
const size = 20
const tenants = ref<Tenant[]>([])
const page = ref(1)
const total = ref(0)
const detail = ref<TenantDetail | null>(null)
const creating = ref(false)
const draft = ref({ name: '', adminContact: '' })
const busy = ref(false)
const error = ref<{ message: string; traceId?: string } | null>(null)

const statusType: Record<InitializationStatus, 'info' | 'success' | 'danger'> = {
  pending: 'info',
  succeeded: 'success',
  failed: 'danger',
}

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
  })

// 新租户排在第一页最前，创建后回到第一页并打开详情查看各服务初始化状态。
const create = () =>
  run(async () => {
    detail.value = await props.api.create(draft.value.name, draft.value.adminContact)
    creating.value = false
    draft.value = { name: '', adminContact: '' }
    await fetchPage(1)
  })

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
      <el-button type="primary" @click="creating = true">{{ t('tenant.create') }}</el-button>
    </header>
    <el-alert v-if="error" :title="error.message" type="error" :closable="false" role="alert">
      <p v-if="error.traceId" class="trace">
        {{ t('error.traceId') }}：<code>{{ error.traceId }}</code>
      </p>
    </el-alert>

    <el-table :data="tenants" :empty-text="t('tenant.empty')">
      <el-table-column prop="name" :label="t('tenant.name')" />
      <el-table-column prop="adminContact" :label="t('tenant.adminContact')" />
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
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('tenant.submit')
        }}</el-button>
      </el-form>
    </el-dialog>

    <el-drawer :model-value="detail !== null" :title="detail?.tenant.name" @close="detail = null">
      <template v-if="detail">
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
                v-if="row.status === 'failed'"
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
  </section>
</template>

<style scoped>
header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.trace {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
}
.failure {
  margin: 4px 0 0;
  color: var(--el-color-danger);
  overflow-wrap: anywhere;
}
</style>
