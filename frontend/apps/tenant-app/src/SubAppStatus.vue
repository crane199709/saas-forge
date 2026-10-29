<script setup lang="ts">
import { computed, inject } from 'vue'
import { useI18n } from 'vue-i18n'
import { subAppHostKey } from './shell'

// Sub-App 加载完成前占据其路由的区域：加载中提示，失败时只在此处报错，Shell 导航与其他 Sub-App 不受影响。
const props = defineProps<{ app: string }>()
const host = inject(subAppHostKey)
const { t } = useI18n()
const state = computed(() => host?.states[props.app] ?? { status: 'loading' as const })
</script>

<template>
  <el-alert
    v-if="state.status === 'failed'"
    :title="t(`subApp.${state.reason}`, { app: props.app, detail: state.detail ?? '' })"
    type="error"
    :closable="false"
    role="alert"
    :data-testid="`sub-app-error-${props.app}`"
  />
  <p v-else :data-testid="`sub-app-loading-${props.app}`">
    {{ t('subApp.loading', { app: props.app }) }}
  </p>
</template>
