<script setup lang="ts">
import { usePlatformClient } from '../shell'
import { createSubAppApi } from './sub-app-api'
import SubAppPanel from './SubAppPanel.vue'

const api = createSubAppApi(usePlatformClient())
</script>

<template>
  <el-card>
    <SubAppPanel :api="api" />
  </el-card>
</template>
