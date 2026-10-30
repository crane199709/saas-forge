<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

defineProps<{ status: '403' | '404' }>()
const { t } = useI18n()
const router = useRouter()
</script>

<template>
  <main class="status-page">
    <el-result
      :icon="status === '403' ? 'warning' : 'info'"
      :title="status"
      :sub-title="t(`result.${status}`)"
      :data-testid="`status-${status}`"
    >
      <template #extra>
        <el-button type="primary" @click="router.push('/')">{{ t('result.home') }}</el-button>
      </template>
    </el-result>
  </main>
</template>

<style scoped>
.status-page {
  padding: 80px 16px;
}
</style>
