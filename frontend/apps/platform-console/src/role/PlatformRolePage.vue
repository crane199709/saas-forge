<script setup lang="ts">
import { usePlatformClient } from '../shell'
import { createPlatformRoleApi } from './platform-role-api'
import PlatformRolePanel from './PlatformRolePanel.vue'

const api = createPlatformRoleApi(usePlatformClient())
</script>

<template>
  <el-card>
    <PlatformRolePanel :api="api" />
  </el-card>
</template>
