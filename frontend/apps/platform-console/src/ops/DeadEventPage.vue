<script setup lang="ts">
import { permissions } from '../access'
import { useConsoleAccess, usePlatformClient } from '../shell'
import { createDeadEventApi } from './dead-event-api'
import DeadEventPanel from './DeadEventPanel.vue'

const api = createDeadEventApi(usePlatformClient())
const access = useConsoleAccess()
</script>

<template>
  <el-card>
    <DeadEventPanel :api="api" :can-manage="access.has(permissions.opsManage)" />
  </el-card>
</template>
