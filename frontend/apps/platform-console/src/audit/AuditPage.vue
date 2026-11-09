<script setup lang="ts">
import { usePlatformClient } from '../shell'
import { createAuditApi } from './audit-api'
import AuditPanel from './AuditPanel.vue'

const api = createAuditApi(usePlatformClient())
</script>

<template>
  <AuditPanel :api="api" />
</template>
