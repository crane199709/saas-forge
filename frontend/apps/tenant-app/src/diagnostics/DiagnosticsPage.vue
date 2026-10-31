<script lang="ts">
import { ref } from 'vue'

// 放在模块中而非组件内：离开诊断页去 Sub-App 再回来时，按钮仍知道当前是哪套主题。
const alternateTheme = ref(false)
</script>

<script setup lang="ts">
import { inject } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { applyTheme, themeTokens } from '@crane199709/ui'
import { subAppHostKey } from '../shell'
import zhCN from './zh-CN.json'

// 文案只随诊断页打包，正式构建不包含。
const { t } = useI18n({ useScope: 'local', messages: { 'zh-CN': zhCN } })
const route = useRoute()
const host = inject(subAppHostKey, undefined)

// 主题切换不是产品功能，用来验证 Shell 与 Sub-App 共用主题 token。
function toggleTheme() {
  alternateTheme.value = !alternateTheme.value
  applyTheme(alternateTheme.value ? themeTokens.alternate : themeTokens.primary)
}
</script>

<template>
  <section>
    <h1>{{ t('diagnostics.title') }}</h1>
    <p data-testid="shell-route">{{ route.fullPath }}</p>
    <el-button type="primary" data-testid="shell-theme" @click="toggleTheme">{{
      t('diagnostics.theme')
    }}</el-button>
    <p class="scope-probe" data-testid="shell-scope">{{ t('diagnostics.scope') }}</p>
    <!-- Sub-App 导出 sharedReferences 时展示共享依赖是否为同一运行时实例 -->
    <ul v-for="(checks, app) in host?.checks" :key="app">
      <li v-for="(same, name) in checks" :key="name" :data-testid="`shared-${app}-${name}`">
        {{ t(same ? 'diagnostics.singleton' : 'diagnostics.duplicate', { name }) }}
      </li>
    </ul>
  </section>
</template>

<style scoped>
.scope-probe {
  border: 0;
}
</style>
