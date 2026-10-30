<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { usePlatformClient } from '../shell'
import { createSubAppApi } from '../subapp/sub-app-api'
import { createPlanApi } from './plan-api'
import PlanPanel from './PlanPanel.vue'

const client = usePlatformClient()
const api = createPlanApi(client)
const subAppApi = createSubAppApi(client)
// 套餐据此提供可选的 Sub-App 编码。注册表列表只要求已认证，没有注册表管理权限的 Operator 也可读取。
const subApps = ref<string[]>([])

onMounted(async () => {
  try {
    subApps.value = (await subAppApi.list()).map((app) => app.code)
  } catch (error) {
    // 读取失败时仍可手工输入 Sub-App 编码。
    console.error(error)
  }
})
</script>

<template>
  <el-card>
    <PlanPanel :api="api" :sub-apps="subApps" />
  </el-card>
</template>
