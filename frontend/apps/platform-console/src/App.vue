<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import {
  AppLayout,
  ErrorAlert,
  applyTheme,
  arrangeNavigation,
  themeTokens,
  type MenuItem,
} from '@crane199709/ui'
// Element Plus 内置文案默认是英文，与第一期只支持简体中文保持一致；按需引入时语言包经 ConfigProvider 下发。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { consolePages } from './router'
import { useConsoleAccess } from './shell'
import UserMenu from './UserMenu.vue'

const { t } = useI18n()
const route = useRoute()
const access = useConsoleAccess()

// 菜单按当前 Operator 的平台权限显示，Token 换发后权限变化时随之出现或消失；位置按平台导航布局排列。
const menu = computed<MenuItem[]>(() =>
  arrangeNavigation(
    consolePages
      .filter((page) => access.has(page.permission))
      .map((page) => ({
        page: page.path.slice(1),
        title: t(page.title),
        icon: page.icon,
        path: page.path,
      })),
    access.state.value.layout,
  ),
)

applyTheme(themeTokens.primary)
</script>

<template>
  <el-config-provider :locale="zhCn">
    <router-view v-if="route.meta.standalone" />
    <AppLayout v-else :title="t('app.product')" :menu="menu" :active-path="route.path">
      <template #actions>
        <UserMenu />
      </template>
      <ErrorAlert
        v-if="access.failed.value"
        :tip="{ message: t('access.failed') }"
        class="access-failed"
      />
      <router-view />
    </AppLayout>
  </el-config-provider>
</template>

<style scoped>
.access-failed {
  margin-bottom: var(--forge-space-3);
}
</style>

<style>
/*
 * 表格密度由运营端 Shell 统一设置，页面不再逐表调整（运营端不承载 Sub-App）：
 * 40px 行高 = 单元格上下内边距 --forge-space-2（8px × 2）+ 正文行高 23px + 1px 分隔线；正文字号保持 14px。
 * 展开行（el-table__expanded-cell）自带大内边距，不参与密度覆盖。
 *
 * 括号里的 14px / 8px 是运行时 token 的构建期兜底，值与 theme.ts 的 --forge-font-size-sm /
 * --forge-space-2 完全一致，不改变像素，不要当成冗余删掉：这两个 token 由 applyTheme() 在运行时
 * 写入文档根节点，构建产物里并不存在；el-table 首屏按文字宽度测量并写死列宽，若测量早于 token
 * 注入，列宽会按继承的 16px 字号算出、文字却按 14px 渲染，整列错位，加载瞬间列宽会跳一下。
 */
.el-table {
  --forge-table-row-height: 40px;
  /* stylelint-disable-next-line declaration-property-value-disallowed-list -- 兜底值是 token 的默认值，不是新档位 */
  font-size: var(--forge-font-size-sm, 14px);
}
.el-table .el-table__cell:not(.el-table__expanded-cell) {
  height: var(--forge-table-row-height);
  /* stylelint-disable-next-line declaration-property-value-disallowed-list -- 同上 */
  padding: var(--forge-space-2, 8px) 0;
}
</style>
