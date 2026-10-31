<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ResultPage } from '@crane199709/ui'
import { useConsoleAccess } from './shell'

const { t } = useI18n()
const access = useConsoleAccess()
</script>

<template>
  <section>
    <h1>{{ t('app.welcome') }}</h1>
    <!-- 有任一管理页权限时守卫已跳到该页，停在首页说明没有可进入的页面；读取失败由布局提示。 -->
    <ResultPage
      v-if="!access.failed.value"
      status="no-permission"
      :sub-title="t('access.none')"
      data-testid="access-none"
    />
  </section>
</template>
