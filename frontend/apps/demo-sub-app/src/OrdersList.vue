<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElTableColumn } from 'element-plus'
import { ApiError, useAccessControl, useTenantClient, type Page } from '@crane199709/sdk'
import {
  ErrorAlert,
  ListPage,
  ImportDialog,
  ExportButton,
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
}

const client = useTenantClient()
const access = useAccessControl()
const importOpen = ref(false)
const ioTasks: IoTaskApi = {
  list: async () => (await client.page<IoTask>('/demo/io/tasks?size=10')).items,
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
const error = ref<ErrorTip | null>(null)
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
      closeFailed: '关闭订单失败，请稍后重试',
      failed: '订单加载失败，请稍后重试',
    },
  },
})

async function load(page = orders.value?.page.number ?? 1) {
  busy.value = true
  error.value = null
  try {
    // 数据范围由服务端裁剪；通过 Shell 的共享客户端，让 401 走统一刷新与登出流程。
    orders.value = await client.page<Order>(`/demo/orders?page=${page}&size=20&sort=createdAt,asc`)
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
  if (closing.value) return
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

onMounted(() => load())
</script>

<template>
  <section :aria-label="t('title')">
    <h3>{{ t('title') }}</h3>
    <ErrorAlert v-if="error" :tip="error" />
    <ImportDialog
      v-if="access.has('order:import')"
      v-model="importOpen"
      :tasks="ioTasks"
      :download-template="downloadTemplate"
      :prepare-upload="prepareImport"
      :submit="submitImport"
      @completed="load()"
    />
    <ListPage :page="orders" :loading="busy" :empty-text="t('empty')" @page-change="load">
      <template #search>
        <el-button :disabled="busy" @click="load()">{{ t('refresh') }}</el-button>
        <el-button v-perm="'order:import'" @click="importOpen = true">{{ t('import') }}</el-button>
        <ExportButton v-if="access.has('order:export')" :tasks="ioTasks" :submit="submitExport" />
      </template>
      <el-table-column :label="t('name')">
        <template #default="{ row }">
          <span data-testid="order-title">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="amount" :label="t('amount')" />
      <el-table-column :label="t('status')">
        <template #default="{ row }">{{
          row.status === 'closed' ? t('closed') : t('unpaid')
        }}</template>
      </el-table-column>
      <el-table-column v-if="access.has('order:update')" :label="t('closeOrder')">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'unpaid'"
            :disabled="closing !== null || busy"
            :loading="closing === row.id"
            :data-testid="`order-close-${row.id}`"
            @click="closeOrder(row.id)"
            >{{ t('closeOrder') }}</el-button
          >
        </template>
      </el-table-column>
    </ListPage>
  </section>
</template>
