<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@crane199709/sdk'
import { ErrorAlert } from '@crane199709/ui'
import { errorTip, type ErrorTip } from '../error-tip'
import RoleListPanel from './RoleListPanel.vue'
import RoleMemberPanel from './RoleMemberPanel.vue'
import {
  createRoleApi,
  type ConditionOptions,
  type DataResource,
  type GrantablePermission,
  type Role,
} from './role-api'

const { t } = useI18n()
const client = inject(tenantClientKey)
if (!client) throw new Error('Shell must provide a TenantClient')
const api = createRoleApi(client)
const tab = ref('roles')
const roles = ref<Role[]>([])
const permissions = ref<GrantablePermission[]>([])
const dataResources = ref<DataResource[]>([])
const conditionOptions = ref<ConditionOptions>({
  subject: [],
  environment: [],
  departments: [],
  positions: [],
})
const loaded = ref(false)
const error = ref<ErrorTip | null>(null)

// 成员分配要展示角色名称，角色、可授予的权限码、数据资源与条件编辑选项在页面级统一加载。
async function load() {
  error.value = null
  try {
    ;[roles.value, permissions.value, dataResources.value, conditionOptions.value] =
      await Promise.all([
        api.roles(),
        api.permissions(),
        api.dataResources(),
        api.conditionOptions(),
      ])
    loaded.value = true
  } catch (cause) {
    error.value = errorTip(cause, t)
  }
}

onMounted(load)
</script>

<template>
  <section class="roles">
    <h2>{{ t('role.title') }}</h2>
    <ErrorAlert v-if="error" :tip="error" />
    <el-tabs v-if="loaded" v-model="tab">
      <el-tab-pane :label="t('role.tabs.roles')" name="roles">
        <RoleListPanel
          :api="api"
          :roles="roles"
          :permissions="permissions"
          :data-resources="dataResources"
          :condition-options="conditionOptions"
          @changed="load"
        />
      </el-tab-pane>
      <el-tab-pane :label="t('role.tabs.members')" name="members" lazy>
        <RoleMemberPanel :api="api" :roles="roles" @changed="load" />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>
