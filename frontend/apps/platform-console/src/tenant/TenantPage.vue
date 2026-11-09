<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { permissions } from '../access'
import { createPlanApi, type Plan } from '../plan/plan-api'
import { useConsoleAccess, usePlatformClient } from '../shell'
import { createTenantApi } from './tenant-api'
import TenantPanel from './TenantPanel.vue'

const client = usePlatformClient()
const access = useConsoleAccess()
const api = createTenantApi(client)
const planApi = createPlanApi(client)
// 租户据此选择与展示套餐。套餐列表只要求已认证，没有套餐管理权限的 Operator 也可读取。
const plans = ref<Plan[]>([])

onMounted(async () => {
  try {
    plans.value = await planApi.list()
  } catch (error) {
    // 读取失败时租户列表照常可用，只是套餐名称无从展示。
    console.error(error)
  }
})
</script>

<template>
  <TenantPanel :api="api" :plans="plans" :can-manage="access.has(permissions.tenantManage)" />
</template>
