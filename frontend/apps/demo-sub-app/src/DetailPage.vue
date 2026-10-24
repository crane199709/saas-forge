<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

const route = useRoute()
const { t } = useI18n({
  useScope: 'local',
  messages: { 'zh-CN': { title: '详情 {id}', overview: '返回概览' } },
})
</script>

<template>
  <h3>{{ t('title', { id: route.params.id }) }}</h3>
  <router-link :to="{ name: 'demo:overview' }">{{ t('overview') }}</router-link>
</template>
