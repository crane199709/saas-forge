<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<template>
  <el-container>
    <el-main>
      <el-card>
        <h1>{{ t('app.welcome') }}</h1>
      </el-card>
    </el-main>
  </el-container>
</template>
