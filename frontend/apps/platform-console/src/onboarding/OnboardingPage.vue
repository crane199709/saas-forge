<script setup lang="ts">
import { usePlatformClient } from '../shell'
import { createOnboardingApi } from './onboarding-api'
import OnboardingPanel from './OnboardingPanel.vue'
const api = createOnboardingApi(usePlatformClient())
</script>
<template><OnboardingPanel :api="api" /></template>
