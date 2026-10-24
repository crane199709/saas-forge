<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ElButton } from 'element-plus'

const version = __DEMO_VERSION__
const { t } = useI18n({
  useScope: 'local',
  messages: {
    'zh-CN': { title: '示例子应用 {version}', theme: '子应用主题', scope: '子应用局部样式' },
  },
})
</script>

<template>
  <section>
    <h2>{{ t('title', { version }) }}</h2>
    <el-button type="primary" data-testid="remote-theme">{{ t('theme') }}</el-button>
    <p class="scope-probe" data-testid="remote-scope">{{ t('scope') }}</p>
    <router-view />
  </section>
</template>

<style scoped>
section {
  margin-top: 24px;
  padding: 20px;
  border: 1px solid var(--forge-color-primary);
  border-radius: 8px;
}
.scope-probe {
  border: 7px solid var(--forge-color-primary);
  padding: 8px;
}
</style>
