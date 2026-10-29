<script setup lang="ts">
import { onUnmounted, provide, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createPlatformClient, platformClientKey } from '@forge/sdk'
import { createPlatformAccess, permissions } from './access'
import LoginPanel from './LoginPanel.vue'
import OperatorPanel from './operator/OperatorPanel.vue'
import { createOperatorApi } from './operator/operator-api'
import PlanPanel from './plan/PlanPanel.vue'
import { createPlanApi, type Plan } from './plan/plan-api'
import PlatformRolePanel from './role/PlatformRolePanel.vue'
import { createPlatformRoleApi } from './role/platform-role-api'
import SubAppPanel from './subapp/SubAppPanel.vue'
import { createSubAppApi, type SubApp } from './subapp/sub-app-api'
import TenantPanel from './tenant/TenantPanel.vue'
import { createTenantApi } from './tenant/tenant-api'

const { t } = useI18n()
// Shell 只创建一个 Platform Realm 客户端，刷新队列只在同一实例内合并。
const client = createPlatformClient(sessionStorage)
provide(platformClientKey, client)
const access = createPlatformAccess(client)
const tenantApi = createTenantApi(client)
const planApi = createPlanApi(client)
const subAppApi = createSubAppApi(client)
const operatorApi = createOperatorApi(client)
const roleApi = createPlatformRoleApi(client)
// 租户面板据此选择与展示套餐，套餐面板据此提供可选的 Sub-App 编码；两个列表只要求已认证，
// 没有对应管理面板的 Operator 也由 Shell 读取，有面板时随面板加载或修改更新。
const plans = ref<Plan[]>([])
const subApps = ref<SubApp[]>([])
// 面板按当前 Operator 的平台权限显示；权限读取完成前不显示，会话失效时随之隐藏。
const ready = ref(false)
const failed = ref(false)
let token: string | null = null

// 刷新换发后重新读取时不先隐藏面板，避免每次刷新都重建界面、丢失正在编辑的内容；权限变化时面板随之出现或消失。
async function reload() {
  failed.value = false
  try {
    await access.load()
    const [planList, subAppList] = await Promise.all([planApi.list(), subAppApi.list()])
    plans.value = planList
    subApps.value = subAppList
    ready.value = true
  } catch {
    failed.value = true
  }
}

// 停用或角色变更后旧 Token 失效，客户端刷新换发新 Token；Token 变化即重新读取权限，界面按新权限更新。
function follow(accessToken: string | null) {
  if (accessToken === token) return
  token = accessToken
  if (accessToken === null) {
    access.clear()
    ready.value = false
    failed.value = false
  } else {
    void reload()
  }
}

follow(client.session()?.accessToken ?? null)
onUnmounted(client.onSessionChange((value) => follow(value?.accessToken ?? null)))
</script>

<template>
  <el-container>
    <el-main>
      <el-card>
        <h1>{{ t('app.welcome') }}</h1>
        <LoginPanel />
        <el-alert
          v-if="failed"
          :title="t('access.failed')"
          type="error"
          :closable="false"
          role="alert"
        />
        <p v-else-if="ready && access.state.value.permissions.length === 0" class="hint">
          {{ t('access.none') }}
        </p>
      </el-card>
      <template v-if="ready">
        <el-card v-if="access.has(permissions.subAppManage)" class="panel">
          <SubAppPanel :api="subAppApi" @loaded="(value) => (subApps = value)" />
        </el-card>
        <el-card v-if="access.has(permissions.planManage)" class="panel">
          <PlanPanel
            :api="planApi"
            :sub-apps="subApps.map((app) => app.code)"
            @loaded="(value) => (plans = value)"
          />
        </el-card>
        <el-card v-if="access.has(permissions.tenantRead)" class="panel">
          <TenantPanel
            :api="tenantApi"
            :plans="plans"
            :can-manage="access.has(permissions.tenantManage)"
          />
        </el-card>
        <el-card v-if="access.has(permissions.operatorManage)" class="panel">
          <OperatorPanel :api="operatorApi" :role-api="roleApi" />
        </el-card>
        <el-card v-if="access.has(permissions.roleManage)" class="panel">
          <PlatformRolePanel :api="roleApi" />
        </el-card>
      </template>
    </el-main>
  </el-container>
</template>

<style scoped>
.panel {
  margin-top: 16px;
}
.hint {
  color: var(--el-text-color-secondary);
}
</style>
