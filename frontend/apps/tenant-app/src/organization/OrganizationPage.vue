<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@crane199709/sdk'
import { ErrorAlert, PageHeader } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import AttributePanel from './AttributePanel.vue'
import DepartmentPanel from './DepartmentPanel.vue'
import MemberPanel from './MemberPanel.vue'
import PositionPanel from './PositionPanel.vue'
import {
  createOrganizationApi,
  type Department,
  type ExtendedAttribute,
  type Position,
} from './org-api'

const { t } = useI18n()
const client = inject(tenantClientKey)
if (!client) throw new Error('Shell must provide a TenantClient')
const api = createOrganizationApi(client)
const tab = ref('departments')
const departments = ref<Department[]>([])
const positions = ref<Position[]>([])
const attributes = ref<ExtendedAttribute[]>([])
const loaded = ref(false)
const error = ref<ErrorTip | null>(null)

// 成员分配要展示部门与岗位名称、按定义渲染扩展属性，三类数据在页面级统一加载。
async function load() {
  error.value = null
  try {
    ;[departments.value, positions.value, attributes.value] = await Promise.all([
      api.departments(),
      api.positions(),
      api.attributes(),
    ])
    loaded.value = true
  } catch (cause) {
    error.value = errorTip(cause, t)
  }
}

onMounted(load)
</script>

<template>
  <section>
    <PageHeader :title="t('org.title')" />
    <ErrorAlert v-if="error" :tip="error" />
    <el-tabs v-if="loaded" v-model="tab">
      <el-tab-pane :label="t('org.tabs.departments')" name="departments">
        <DepartmentPanel :api="api" :departments="departments" @changed="load" />
      </el-tab-pane>
      <el-tab-pane :label="t('org.tabs.positions')" name="positions">
        <PositionPanel :api="api" :positions="positions" @changed="load" />
      </el-tab-pane>
      <el-tab-pane :label="t('org.tabs.attributes')" name="attributes">
        <AttributePanel :api="api" :attributes="attributes" @changed="load" />
      </el-tab-pane>
      <el-tab-pane :label="t('org.tabs.members')" name="members" lazy>
        <MemberPanel
          :api="api"
          :departments="departments"
          :positions="positions"
          :attributes="attributes"
        />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>
