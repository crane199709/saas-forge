<script setup lang="ts">
import { permissions } from '../access'
import { useConsoleAccess, usePlatformClient } from '../shell'
import { createDeadEventApi } from './dead-event-api'
import DeadEventPanel from './DeadEventPanel.vue'

const api = createDeadEventApi(usePlatformClient())
const access = useConsoleAccess()
</script>

<template>
  <DeadEventPanel :api="api" :can-manage="access.has(permissions.opsManage)" />
</template>
