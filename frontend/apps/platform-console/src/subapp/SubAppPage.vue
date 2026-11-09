<script setup lang="ts">
import { usePlatformClient } from '../shell'
import { createSubAppApi } from './sub-app-api'
import SubAppPanel from './SubAppPanel.vue'

const api = createSubAppApi(usePlatformClient())
</script>

<template>
  <SubAppPanel :api="api" />
</template>
