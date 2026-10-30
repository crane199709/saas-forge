<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElPagination } from 'element-plus'
import { ApiError, useTenantClient } from '@forge/sdk'

interface Order {
  id: string
  title: string
  amount: number
}

const client = useTenantClient()
const orders = ref<Order[]>([])
const page = ref(1)
const total = ref(0)
const busy = ref(false)
const error = ref('')
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

async function load() {
  busy.value = true
  error.value = ''
  orders.value = []
  try {
    // 数据范围由服务端裁剪；通过 Shell 的共享客户端，让 401 走统一刷新与登出流程。
    const result = await client.page<Order>(
      `/demo/orders?page=${page.value}&size=20&sort=createdAt,asc`,
    )
    orders.value = result.items
    total.value = result.page.totalElements
  } catch (cause) {
    total.value = 0
    error.value = cause instanceof ApiError && cause.userTip ? cause.userTip : t('failed')
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section :aria-label="t('title')" :aria-busy="busy">
    <h3>{{ t('title') }}</h3>
    <el-button :disabled="busy" @click="load">{{ t('refresh') }}</el-button>
    <p v-if="error" role="alert">{{ error }}</p>
    <table v-else>
      <thead>
        <tr>
          <th>{{ t('name') }}</th>
          <th>{{ t('amount') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="order in orders" :key="order.id">
          <td data-testid="order-title">{{ order.title }}</td>
          <td>{{ order.amount }}</td>
        </tr>
      </tbody>
    </table>
    <p v-if="!busy && !error && !orders.length">{{ t('empty') }}</p>
    <el-pagination
      v-if="total > 20"
      v-model:current-page="page"
      :page-size="20"
      :total="total"
      :disabled="busy"
      layout="prev, pager, next"
      @current-change="load"
    />
  </section>
</template>
