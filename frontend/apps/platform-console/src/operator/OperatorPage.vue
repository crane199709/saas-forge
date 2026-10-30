<script setup lang="ts">
import { createPlatformRoleApi } from '../role/platform-role-api'
import { usePlatformClient } from '../shell'
import { createOperatorApi } from './operator-api'
import OperatorPanel from './OperatorPanel.vue'

const client = usePlatformClient()
const api = createOperatorApi(client)
const roleApi = createPlatformRoleApi(client)
</script>

<template>
  <el-card>
    <OperatorPanel :api="api" :role-api="roleApi" />
  </el-card>
</template>
