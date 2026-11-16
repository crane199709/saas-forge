<script setup lang="ts">
import { permissions } from '../access'
import { useConsoleAccess, usePlatformClient } from '../shell'
import { createAiModelApi } from './ai-model-api'
import AiModelPanel from './AiModelPanel.vue'

const api = createAiModelApi(usePlatformClient())
const access = useConsoleAccess()
</script>

<template>
  <AiModelPanel :api="api" :can-manage="access.has(permissions.opsManage)" />
</template>
