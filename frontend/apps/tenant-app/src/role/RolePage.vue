<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { tenantClientKey } from '@forge/sdk'
import ErrorAlert from '../ErrorAlert.vue'
import { errorTip, type ErrorTip } from '../error-tip'
import RoleListPanel from './RoleListPanel.vue'
import RoleMemberPanel from './RoleMemberPanel.vue'
import { createRoleApi, type GrantablePermission, type Role } from './role-api'

const { t } = useI18n()
const client = inject(tenantClientKey)
if (!client) throw new Error('Shell must provide a TenantClient')
const api = createRoleApi(client)
const tab = ref('roles')
const roles = ref<Role[]>([])
const permissions = ref<GrantablePermission[]>([])
const loaded = ref(false)
const error = ref<ErrorTip | null>(null)

// 成员分配要展示角色名称，角色与可授予的权限码在页面级统一加载。
async function load() {
  error.value = null
  try {
    ;[roles.value, permissions.value] = await Promise.all([api.roles(), api.permissions()])
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
        <RoleListPanel :api="api" :roles="roles" :permissions="permissions" @changed="load" />
      </el-tab-pane>
      <el-tab-pane :label="t('role.tabs.members')" name="members" lazy>
        <RoleMemberPanel :api="api" :roles="roles" @changed="load" />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>
