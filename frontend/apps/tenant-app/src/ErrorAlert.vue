<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { ErrorTip } from './error-tip'

const props = defineProps<{ tip: ErrorTip }>()
const { t } = useI18n()

function copy() {
  if (props.tip.traceId) void navigator.clipboard?.writeText(props.tip.traceId)
}
</script>

<template>
  <el-alert :title="tip.message" type="error" :closable="false" role="alert">
    <p v-if="tip.traceId" class="trace">
      {{ t('error.traceId') }}：<code>{{ tip.traceId }}</code>
      <el-button link type="primary" size="small" data-testid="copy-trace-id" @click="copy">{{
        t('error.copyTraceId')
      }}</el-button>
    </p>
  </el-alert>
</template>

<style scoped>
.trace {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
}
</style>
