<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { formatDateTime } from '@crane199709/ui'
import type { TenantApi, TenantAiUsage } from './tenant-api'

const props = defineProps<{ tenantId: string; api: Pick<TenantApi, 'aiUsage'> }>()
const month = ref('')
const usage = ref<TenantAiUsage | null>(null)
const busy = ref(false)
const failed = ref(false)
let revision = 0
watch(
  () => props.tenantId,
  () => {
    revision++
    usage.value = null
    busy.value = false
    failed.value = false
    month.value = ''
  },
)
onBeforeUnmount(() => {
  revision++
})
const number = (value: number) => value.toLocaleString('zh-CN')
async function load() {
  const current = ++revision
  const tenantId = props.tenantId
  busy.value = true
  usage.value = null
  failed.value = false
  try {
    const result = await props.api.aiUsage(tenantId, month.value || undefined)
    if (current !== revision) return
    usage.value = result
    month.value = result.month
  } catch {
    if (current === revision) failed.value = true
  } finally {
    if (current === revision) busy.value = false
  }
}
</script>

<template>
  <section class="ai-usage" aria-label="AI 用量">
    <h3>AI 用量</h3>
    <form class="usage-query" @submit.prevent="load">
      <label for="ai-usage-month">月份</label>
      <input id="ai-usage-month" v-model="month" type="month" aria-label="AI 用量月份" />
      <el-button native-type="submit" :loading="busy" data-testid="ai-usage-query">{{
        month ? '查询用量' : '查询本月'
      }}</el-button>
    </form>
    <el-alert v-if="failed" title="用量查询失败，请重试" type="error" :closable="false" />
    <p v-else-if="busy" role="status">正在查询用量…</p>
    <template v-else-if="usage?.received">
      <el-alert
        v-if="usage.unknownUsageTurns"
        :title="`${usage.unknownUsageTurns} 个回合的用量不完整，估计占额与已知 Token 分开显示`"
        type="warning"
        :closable="false"
      />
      <el-descriptions :column="2" border>
        <el-descriptions-item label="已知 Token">{{
          number(usage.totalTokens)
        }}</el-descriptions-item>
        <el-descriptions-item label="估计占额">{{
          number(usage.estimatedTokens)
        }}</el-descriptions-item>
        <el-descriptions-item label="输入 / 输出"
          >{{ number(usage.inputTokens) }} / {{ number(usage.outputTokens) }}</el-descriptions-item
        >
        <el-descriptions-item label="缓存输入">{{
          number(usage.cachedInputTokens)
        }}</el-descriptions-item>
        <el-descriptions-item label="回合 / 工具调用"
          >{{ number(usage.turnCount) }} / {{ number(usage.toolCalls) }}</el-descriptions-item
        >
        <el-descriptions-item label="业务时区">{{ usage.businessZone }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="usage.days" size="small">
        <el-table-column prop="usageDate" label="日期" min-width="108" />
        <el-table-column label="已知 Token" min-width="105" align="right"
          ><template #default="{ row }">{{
            number(row.inputTokens + row.outputTokens)
          }}</template></el-table-column
        >
        <el-table-column label="估计占额" min-width="100" align="right"
          ><template #default="{ row }">{{
            number(row.estimatedTokens)
          }}</template></el-table-column
        >
        <el-table-column label="最后回报" min-width="165"
          ><template #default="{ row }">{{
            formatDateTime(row.reportedAt)
          }}</template></el-table-column
        >
      </el-table>
    </template>
    <p v-else-if="usage">尚未收到该月用量</p>
  </section>
</template>

<style scoped>
.ai-usage {
  margin-top: var(--forge-space-4);
}
.usage-query {
  display: flex;
  align-items: center;
  gap: var(--forge-space-2);
  margin-bottom: var(--forge-space-3);
}
.usage-query input {
  font: inherit;
  padding: var(--forge-space-1) var(--forge-space-2);
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
}
.ai-usage .el-alert {
  margin-bottom: var(--forge-space-3);
}
</style>
