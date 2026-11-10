<script setup lang="ts">
import { computed, inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import {
  tenantClientKey,
  useAccessControl,
  type MenuNode,
  type NavigationLayout,
} from '@crane199709/sdk'
import {
  ErrorAlert,
  NavigationLayoutEditor,
  PageHeader,
  overlaySize,
  type ErrorTip,
  type NavigationEntry,
} from '@crane199709/ui'
import { errorTip } from '../error-tip'
import { createNavigationApi } from './navigation-api'
import { defaultNavigation, shellPages } from './shell-pages'

const { t } = useI18n()
const client = inject(tenantClientKey)
if (!client) throw new Error('Shell must provide a TenantClient')
const api = createNavigationApi(client)
const access = useAccessControl()

const layout = ref<NavigationLayout | null>(null)
const menus = ref<MenuNode[]>([])
const loaded = ref(false)
const loadError = ref<ErrorTip | null>(null)
const busy = ref(false)
const confirmReset = ref(false)

const catalogEntries = (nodes: MenuNode[]): NavigationEntry[] =>
  nodes.map((node) =>
    node.children?.length
      ? { menu: node.code, title: node.name, children: catalogEntries(node.children) }
      : { menu: node.code, title: node.name },
  )

// 目录列出全部 Shell Page，不按操作者本人权限裁剪。
const catalog = computed(() => defaultNavigation(shellPages(t), catalogEntries(menus.value)))

async function load() {
  loadError.value = null
  try {
    const value = await api.read()
    layout.value = value.layout
    menus.value = value.menus
    loaded.value = true
  } catch (cause) {
    loadError.value = errorTip(cause, t)
  }
}

// 成功后重新读取权限，操作者自己的导航立即按新布局显示；其他成员在下次读取权限时生效。
async function run(action: () => Promise<void>) {
  busy.value = true
  try {
    await action()
    // 权限读取失败不影响已保存的结果，导航在下次读取权限时更新。
    void access.load().catch(() => undefined)
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
