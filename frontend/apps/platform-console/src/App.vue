<script setup lang="ts">
import { provide } from 'vue'
import { useI18n } from 'vue-i18n'
import { createPlatformClient, platformClientKey } from '@forge/sdk'
import LoginPanel from './LoginPanel.vue'

const { t } = useI18n()
// Shell 只创建一个 Platform Realm 客户端，刷新队列只在同一实例内合并。
provide(platformClientKey, createPlatformClient(sessionStorage))
</script>

<template>
  <el-container>
    <el-main>
      <el-card>
        <h1>{{ t('app.welcome') }}</h1>
        <LoginPanel />
      </el-card>
    </el-main>
  </el-container>
</template>
