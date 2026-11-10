<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import type { NavigationLayout } from '@crane199709/sdk'
import {
  ErrorAlert,
  NavigationLayoutEditor,
  PageHeader,
  overlaySize,
  type ErrorTip,
  type NavigationEntry,
} from '@crane199709/ui'
import { errorTip } from '../error-tip'
import { consolePages } from '../router'
import { useConsoleAccess, usePlatformClient } from '../shell'
import { createNavigationApi } from './navigation-api'

const { t } = useI18n()
const api = createNavigationApi(usePlatformClient())

const layout = ref<NavigationLayout | null>(null)
const loaded = ref(false)
const loadError = ref<ErrorTip | null>(null)
const busy = ref(false)
const confirmReset = ref(false)
const access = useConsoleAccess()

// 目录列出全部平台管理页，不按操作者本人权限裁剪；平台没有清单菜单。
const catalog = computed<NavigationEntry[]>(() =>
  consolePages.map((page) => ({ page: page.path.slice(1), title: t(page.title), path: page.path })),
)

async function load() {
  loadError.value = null
  try {
    layout.value = (await api.read()).layout
    loaded.value = true
  } catch (cause) {
    loadError.value = errorTip(cause, t)
  }
}

// 成功后重新读取权限，操作者自己的导航立即按新布局显示；其他 Operator 在下次读取权限时生效。
// 权限读取失败由 Shell 提示，不影响已保存的结果。
async function run(action: () => Promise<void>) {
  busy.value = true
  try {
    await action()
    void access.reload()
  } catch (cause) {
    ElMessage.error(errorTip(cause, t).message)
  } finally {
    busy.value = false
  }
}

function save(value: NavigationLayout) {
  void run(async () => {
    layout.value = await api.replace(value)
  })
}

function reset() {
  confirmReset.value = false
  void run(async () => {
    await api.reset()
    layout.value = null
  })
}

onMounted(load)
</script>

<template>
  <section class="menu-layout">
    <PageHeader :title="t('menuLayout.title')" />
    <template v-if="loadError">
      <ErrorAlert class="page-error" :tip="loadError" />
      <el-button data-testid="menu-layout-retry" @click="load">{{
        t('menuLayout.retry')
      }}</el-button>
    </template>
    <NavigationLayoutEditor
      v-else-if="loaded"
      :catalog="catalog"
      :layout="layout"
      :busy="busy"
      @save="save"
      @reset="confirmReset = true"
    />
    <el-dialog
      v-model="confirmReset"
      :width="overlaySize.dialogSm"
      :title="t('menuLayout.resetTitle')"
    >
      <p class="reset-impact">{{ t('menuLayout.resetImpact') }}</p>
      <template #footer>
        <el-button @click="confirmReset = false">{{ t('menuLayout.cancel') }}</el-button>
        <el-button type="primary" data-testid="menu-layout-reset-confirm" @click="reset">
          {{ t('menuLayout.resetConfirm') }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.menu-layout {
  max-width: 720px;
}
.page-error {
  margin-bottom: var(--forge-space-3);
}
.reset-impact {
  margin: 0;
  color: var(--el-text-color-regular);
}
</style>
