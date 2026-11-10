<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ElButton } from 'element-plus'
import { PageHeader } from '@crane199709/ui'

const version = __DEMO_VERSION__
// 主题与样式隔离探针只服务联邦验证，普通构建不展示。
const diagnostics = import.meta.env.VITE_FORGE_DIAGNOSTICS === 'true'
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': { title: '示例子应用 {version}', theme: '子应用主题', scope: '子应用局部样式' },
  },
})
</script>

<template>
  <section>
    <PageHeader :title="t('title', { version })" />
    <template v-if="diagnostics">
      <el-button type="primary" data-testid="remote-theme">{{ t('theme') }}</el-button>
      <p class="scope-probe" data-testid="remote-scope">{{ t('scope') }}</p>
    </template>
    <router-view />
  </section>
</template>

<style scoped>
.scope-probe {
  border: 7px solid var(--forge-color-primary);
  padding: var(--forge-space-2);
}
</style>
