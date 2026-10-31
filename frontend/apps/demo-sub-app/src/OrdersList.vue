<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElTableColumn } from 'element-plus'
import { ApiError, useTenantClient, type Page } from '@crane199709/sdk'
import { ErrorAlert, ListPage, type ErrorTip } from '@crane199709/ui'

interface Order {
  id: string
  title: string
  amount: number
}

const client = useTenantClient()
const orders = ref<Page<Order> | null>(null)
const busy = ref(false)
const error = ref<ErrorTip | null>(null)
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': {
      title: '订单',
      refresh: '刷新订单',
      name: '标题',
      amount: '金额',
      empty: '暂无订单',
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

onMounted(() => load())
</script>

<template>
  <section :aria-label="t('title')">
    <h3>{{ t('title') }}</h3>
    <ErrorAlert v-if="error" :tip="error" />
    <ListPage :page="orders" :loading="busy" :empty-text="t('empty')" @page-change="load">
      <template #search>
        <el-button :disabled="busy" @click="load()">{{ t('refresh') }}</el-button>
      </template>
      <el-table-column :label="t('name')">
        <template #default="{ row }">
          <span data-testid="order-title">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="amount" :label="t('amount')" />
    </ListPage>
  </section>
</template>
