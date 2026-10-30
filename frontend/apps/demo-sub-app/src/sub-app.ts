import { sdkRuntime } from '@forge/sdk'
import { themeTokens } from '@forge/ui'
import { ElDialog } from 'element-plus'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter, type RouteRecordRaw } from 'vue-router'
import DemoApp from './DemoApp.vue'
import OverviewPage from './OverviewPage.vue'
import DetailPage from './DetailPage.vue'
import AttachmentsPage from './AttachmentsPage.vue'

export const sharedReferences = {
  vue: ref,
  'vue-router': useRouter,
  pinia: defineStore,
  'element-plus': ElDialog,
  '@forge/sdk': sdkRuntime,
  '@forge/ui': themeTokens,
  'vue-i18n': useI18n,
}
export const routes: RouteRecordRaw[] = [
  {
    path: '',
    component: DemoApp,
    children: [
      { path: 'overview', name: 'demo:overview', component: OverviewPage },
      { path: 'details/:id', name: 'demo:details', component: DetailPage },
      { path: 'attachments', name: 'demo:attachments', component: AttachmentsPage },
    ],
  },
]
