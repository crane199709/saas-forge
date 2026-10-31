<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ResultPage } from '@crane199709/ui'

defineProps<{ status: '403' | '404' }>()
const { t } = useI18n()
const router = useRouter()
</script>

<template>
  <main>
    <ResultPage :status="status">
      <el-button type="primary" @click="router.push('/')">{{ t('result.home') }}</el-button>
    </ResultPage>
  </main>
</template>
