<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, ref, watch } from 'vue'
import { ElButton, ElOption, ElSelect } from 'element-plus'
import { ApiError, useTenantClient } from '@forge/sdk'

interface Order {
  id: string
  title: string
}

interface Attachment {
  id: string
  filename: string
  size: number
  createdAt: string
}

interface UploadTicket {
  objectId: string
  url: string
  headers: Record<string, string>
}

const client = useTenantClient()
const orders = ref<Order[]>([])
const orderId = ref('')
const attachments = ref<Attachment[]>([])
const file = ref<File>()
const busy = ref(false)
const error = ref('')
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      title: '订单附件',
      order: '选择订单',
      noOrders: '暂无订单',
      upload: '上传',
      download: '下载',
      remove: '删除',
      empty: '暂无附件',
      uploadFailed: '文件上传失败，请重试',
      failed: '操作失败，请稍后重试',
    },
  },
})

onMounted(async () => {
  try {
    orders.value = (await client.page<Order>('/demo/orders?size=100&sort=createdAt,desc')).items
    orderId.value = orders.value[0]?.id ?? ''
  } catch (cause) {
    showError(cause)
  }
})

watch(orderId, () => void loadAttachments())

async function loadAttachments() {
  attachments.value = []
  if (!orderId.value) return
  try {
    attachments.value = await client.api<Attachment[]>(`/demo/orders/${orderId.value}/attachments`)
  } catch (cause) {
    showError(cause)
  }
}

function pick(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0]
}

// 文件内容经预签名 URL 直传对象存储，不经过网关与 demo-service；该请求不能带租户 Token
async function upload() {
  const selected = file.value
  if (!selected || !orderId.value) return
  const base = `/demo/orders/${orderId.value}/attachments`
  await run(async () => {
    const ticket = await client.api<UploadTicket>(`${base}/uploads`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        filename: selected.name,
        contentType: selected.type || 'application/octet-stream',
        size: selected.size,
      }),
    })
    const stored = await fetch(ticket.url, {
      method: 'PUT',
      headers: ticket.headers,
      body: selected,
    })
    if (!stored.ok) {
      error.value = t('uploadFailed')
      return
    }
    await client.api(base, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ objectId: ticket.objectId }),
    })
    file.value = undefined
    await loadAttachments()
  })
}

// 下载 URL 有效期很短，每次点击时现取
async function download(attachment: Attachment) {
  await run(async () => {
    const ticket = await client.api<{ url: string }>(
      `/demo/orders/${orderId.value}/attachments/${attachment.id}/download`,
    )
    window.open(ticket.url, '_blank', 'noopener')
  })
}

async function remove(attachment: Attachment) {
  await run(async () => {
    await client.api(`/demo/orders/${orderId.value}/attachments/${attachment.id}`, {
      method: 'DELETE',
    })
    await loadAttachments()
  })
}

async function run(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (cause) {
    showError(cause)
  } finally {
    busy.value = false
  }
}

function showError(cause: unknown) {
  error.value = cause instanceof ApiError && cause.userTip ? cause.userTip : t('failed')
}

function formatSize(size: number) {
  return size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`
}
</script>

<template>
  <h3>{{ t('title') }}</h3>
  <p v-if="orders.length === 0">{{ t('noOrders') }}</p>
  <template v-else>
    <el-select v-model="orderId" :aria-label="t('order')" :placeholder="t('order')">
      <el-option v-for="order in orders" :key="order.id" :label="order.title" :value="order.id" />
    </el-select>
    <p class="upload">
      <input type="file" data-testid="attachment-file" @change="pick" />
      <el-button
        v-perm="'order:update'"
        type="primary"
        :disabled="!file || busy"
        data-testid="attachment-upload"
        @click="upload"
        >{{ t('upload') }}</el-button
      >
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="attachments.length === 0">{{ t('empty') }}</p>
    <ul v-else data-testid="attachment-list">
      <li v-for="attachment in attachments" :key="attachment.id">
        {{ attachment.filename }}（{{ formatSize(attachment.size) }}）
        <el-button link type="primary" :disabled="busy" @click="download(attachment)">{{
          t('download')
        }}</el-button>
        <el-button
          v-perm="'order:update'"
          link
          type="danger"
          :disabled="busy"
          @click="remove(attachment)"
          >{{ t('remove') }}</el-button
        >
      </li>
    </ul>
  </template>
</template>

<style scoped>
.upload {
  display: flex;
  gap: 12px;
  align-items: center;
}
.error {
  color: var(--el-color-danger);
}
</style>
