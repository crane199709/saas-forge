<script setup lang="ts">
import { permissions } from '../access'
import { useConsoleAccess, usePlatformClient } from '../shell'
import { createTaskRunApi } from './task-run-api'
import TaskRunPanel from './TaskRunPanel.vue'

const api = createTaskRunApi(usePlatformClient())
const access = useConsoleAccess()
</script>

<template>
  <el-card>
    <TaskRunPanel :api="api" :can-manage="access.has(permissions.opsManage)" />
  </el-card>
</template>
