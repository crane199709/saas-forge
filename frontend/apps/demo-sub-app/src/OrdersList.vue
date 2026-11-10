<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElDialog, ElTableColumn, ElTag } from 'element-plus'
import { ApiError, useAccessControl, useTenantClient, type Page } from '@crane199709/sdk'
import {
  ErrorAlert,
  ExportButton,
  ImportDialog,
  ListPage,
  formatDateTime,
  overlaySize,
  type IoTaskApi,
  type IoTask,
  type IoUploadTicket,
  type ErrorTip,
} from '@crane199709/ui'

interface Order {
  id: string
  title: string
  amount: number
  status: 'unpaid' | 'closed'
  updatedAt?: string | null
  updatedBy?: string | null
}

const client = useTenantClient()
const access = useAccessControl()
const importOpen = ref(false)
const ioTasks: IoTaskApi = {
  list: (page) => client.page<IoTask>(`/demo/io/tasks?page=${page}&size=10`),
  get: (id) => client.api<IoTask>(`/demo/io/tasks/${id}`),
  download: (id) => client.api<{ url: string }>(`/demo/io/tasks/${id}/download`),
}
const post = <T,>(url: string, body: unknown) =>
  client.api<T>(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
const submitExport = () => post<IoTask>('/demo/orders/export?sort=createdAt,asc', {})
const prepareImport = (file: File) =>
  post<IoUploadTicket>('/demo/orders/import/upload', {
    filename: file.name,
    contentType: file.type || 'application/octet-stream',
    size: file.size,
  })
const submitImport = (objectId: string) => post<IoTask>('/demo/orders/import', { objectId })
const confirmImport = (taskId: string) => post<IoTask>(`/demo/orders/import/${taskId}/confirm`, {})
async function downloadTemplate() {
  const data = await client.api<{ filename: string; base64: string }>(
    '/demo/orders/import/template',
  )
  const bytes = Uint8Array.from(atob(data.base64), (char) => char.charCodeAt(0))
  const url = URL.createObjectURL(
    new Blob([bytes], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }),
  )
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = data.filename
  anchor.click()
  // 下载由浏览器异步发起，立即撤销可能让下载失败；1 秒只是留足余量。
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
const orders = ref<Page<Order> | null>(null)
const busy = ref(false)
const closing = ref<string | null>(null)
const deleting = ref<string | null>(null)
const error = ref<ErrorTip | null>(null)
const deleted = ref(false)
const restoring = ref<Order | null>(null)
const restoreBusy = ref(false)
const restored = ref(false)
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      title: '订单',
      refresh: '刷新订单',
      import: '导入订单',
      name: '标题',
      amount: '金额',
      empty: '暂无订单',
      status: '状态',
      unpaid: '未支付',
      closed: '已关闭',
      closeOrder: '关闭订单',
      actions: '操作',
      closeFailed: '关闭订单失败，请稍后重试',
      delete: '删除',
      deleteFailed: '删除订单失败，请稍后重试',
      failed: '订单加载失败，请稍后重试',
      deletedAt: '删除时间',
      deletedBy: '操作人（身份 ID）',
      system: '系统',
      confirmRestore: '确认恢复',
      restoreMessage: '确认恢复「{title}」？',
      restoreFailed: '恢复订单失败，请稍后重试',
      restored: '恢复成功',
      cancel: '取消',
    },
  },
})

async function load(page = orders.value?.page.number ?? 1) {
  busy.value = true
  error.value = null
  try {
    // 数据范围由服务端裁剪；通过 Shell 的共享客户端，让 401 走统一刷新与登出流程。
    const query = (number: number) =>
      client.page<Order>(
        `/demo/orders?page=${number}&size=20&sort=createdAt,asc${deleted.value ? '&deleted=true' : ''}`,
      )
    const result = await query(page)
    const lastPage = Math.max(1, result.page.totalPages)
    orders.value = page > lastPage ? await query(lastPage) : result
  } catch (cause) {
    orders.value = null
    error.value = {
      message: cause instanceof ApiError && cause.userTip ? cause.userTip : t('failed'),
      traceId: cause instanceof ApiError ? cause.traceId : undefined,
    }
  } finally {
    busy.value = false
  }
}

async function closeOrder(id: string) {
  if (closing.value || busy.value || restoreBusy.value || deleted.value) return
  closing.value = id
  error.value = null
  try {
    await client.api<Order>(`/demo/orders/${id}/close`, { method: 'POST' })
    await load()
  } catch (cause) {
    error.value = {
      message: cause instanceof ApiError && cause.userTip ? cause.userTip : t('closeFailed'),
      traceId: cause instanceof ApiError ? cause.traceId : undefined,
    }
  } finally {
    closing.value = null
  }
}

// 删除是逻辑删除，可在已删除视图恢复，因此不弹确认。
async function deleteOrder(id: string) {
  if (deleting.value || closing.value || busy.value || deleted.value) return
  deleting.value = id
  error.value = null
  restored.value = false
  try {
    await client.api<null>(`/demo/orders/${id}`, { method: 'DELETE' })
    await load()
  } catch (cause) {
    error.value = {
      message: cause instanceof ApiError && cause.userTip ? cause.userTip : t('deleteFailed'),
      traceId: cause instanceof ApiError ? cause.traceId : undefined,
    }
  } finally {
    deleting.value = null
  }
}

async function changeView(value: boolean) {
  if (busy.value || closing.value || restoreBusy.value || !access.has('order:delete')) return
  deleted.value = value
  restored.value = false
  orders.value = null
  await load(1)
}

function requestRestore(order: Order) {
  if (!deleted.value || busy.value || restoreBusy.value || !access.has('order:delete')) return
  restoring.value = order
}

async function restoreOrder() {
  if (!restoring.value || restoreBusy.value) return
  restoreBusy.value = true
  error.value = null
  restored.value = false
  try {
    await client.api<null>(`/demo/orders/${restoring.value.id}/restore`, { method: 'POST' })
    restoring.value = null
    restored.value = true
    await load()
  } catch (cause) {
    restoring.value = null
    error.value = {
      message: cause instanceof ApiError && cause.userTip ? cause.userTip : t('restoreFailed'),
      traceId: cause instanceof ApiError ? cause.traceId : undefined,
    }
  } finally {
    restoreBusy.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <section :aria-label="t('title')">
    <h3>{{ t('title') }}</h3>
    <ErrorAlert v-if="error" class="notice" :tip="error" />
    <p v-if="restored" class="notice" role="status">{{ t('restored') }}</p>
    <ImportDialog
      v-if="!deleted && access.has('order:import')"
      v-model="importOpen"
      :tasks="ioTasks"
      :download-template="downloadTemplate"
      :prepare-upload="prepareImport"
      :submit="submitImport"
      :confirm="confirmImport"
      :membership-id="client.session()?.membershipId ?? ''"
      @completed="load()"
    />
    <ListPage
      :page="orders"
      :loading="busy || restoreBusy || closing !== null || deleting !== null"
      :deleted="access.has('order:delete') ? deleted : undefined"
      :empty-text="t('empty')"
      @page-change="load"
      @deleted-change="changeView"
      @restore="requestRestore"
    >
      <template #search>
        <el-button :disabled="busy || restoreBusy || closing !== null" @click="load()">{{
          t('refresh')
        }}</el-button>
        <el-button v-if="!deleted" v-perm="'order:import'" @click="importOpen = true">{{
          t('import')
        }}</el-button>
        <ExportButton
          v-if="!deleted && access.has('order:export')"
          :tasks="ioTasks"
          :submit="submitExport"
        />
      </template>
      <el-table-column :label="t('name')" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <span data-testid="order-title">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="amount" :label="t('amount')" width="120" align="right" />
      <el-table-column :label="t('status')" width="96">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'closed' ? 'info' : 'warning'">
            {{ row.status === 'closed' ? t('closed') : t('unpaid') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="deleted" :label="t('deletedAt')" min-width="180">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column v-if="deleted" :label="t('deletedBy')" min-width="160">
        <template #default="{ row }">{{ row.updatedBy ?? t('system') }}</template>
      </el-table-column>
      <el-table-column
        v-if="!deleted && (access.has('order:update') || access.has('order:delete'))"
        :label="t('actions')"
        width="144"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'unpaid' && access.has('order:update')"
            link
            type="primary"
            :disabled="closing !== null || deleting !== null || busy"
            :loading="closing === row.id"
            :data-testid="`order-close-${row.id}`"
            @click="closeOrder(row.id)"
            >{{ t('closeOrder') }}</el-button
          >
          <el-button
            v-if="access.has('order:delete')"
            link
            type="danger"
            :disabled="closing !== null || deleting !== null || busy"
            :loading="deleting === row.id"
            :data-testid="`order-delete-${row.id}`"
            @click="deleteOrder(row.id)"
            >{{ t('delete') }}</el-button
          >
        </template>
      </el-table-column>
    </ListPage>
    <ElDialog
      :model-value="restoring !== null"
      :title="t('confirmRestore')"
      :width="overlaySize.dialogSm"
      :close-on-click-modal="!restoreBusy"
      :close-on-press-escape="!restoreBusy"
      :show-close="!restoreBusy"
      @close="restoring = null"
    >
      <p>{{ t('restoreMessage', { title: restoring?.title }) }}</p>
      <template #footer>
        <ElButton :disabled="restoreBusy" @click="restoring = null">{{ t('cancel') }}</ElButton>
        <ElButton type="primary" :loading="restoreBusy" @click="restoreOrder">{{
          t('confirmRestore')
        }}</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
h3 {
  margin: 0 0 var(--forge-space-3);
  font-size: var(--forge-font-size-md);
  font-weight: 600;
}
.notice {
  margin: 0 0 var(--forge-space-3);
}
</style>
