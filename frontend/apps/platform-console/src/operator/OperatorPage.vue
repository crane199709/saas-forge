<script setup lang="ts">
import { createPlatformRoleApi } from '../role/platform-role-api'
import { useConsoleAccess, usePlatformClient } from '../shell'
import { createOperatorApi } from './operator-api'
import OperatorPanel from './OperatorPanel.vue'

const client = usePlatformClient()
const access = useConsoleAccess()
const api = createOperatorApi(client)
const roleApi = createPlatformRoleApi(client)
</script>

<template>
  <OperatorPanel :api="api" :role-api="roleApi" :can-reset-mfa="access.isSuperAdmin()" />
</template>
