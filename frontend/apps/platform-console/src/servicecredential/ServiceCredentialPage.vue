<script setup lang="ts">
import { usePlatformClient } from '../shell'
import { createServiceCredentialApi } from './service-credential-api'
import ServiceCredentialPanel from './ServiceCredentialPanel.vue'
const api = createServiceCredentialApi(usePlatformClient())
</script>
<template><ServiceCredentialPanel :api="api" /></template>
