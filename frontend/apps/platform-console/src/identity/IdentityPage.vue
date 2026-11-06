<script setup lang="ts">
import { usePlatformClient } from '../shell'
import { createIdentityApi } from './identity-api'
import IdentityPanel from './IdentityPanel.vue'

const api = createIdentityApi(usePlatformClient())
</script>

<template>
  <el-card>
    <IdentityPanel :api="api" />
  </el-card>
</template>
