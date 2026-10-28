<script setup lang="ts">
import { onUnmounted, provide, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createPlatformClient, platformClientKey } from '@forge/sdk'
import LoginPanel from './LoginPanel.vue'
import TenantPanel from './tenant/TenantPanel.vue'
import { createTenantApi } from './tenant/tenant-api'

const { t } = useI18n()
// Shell 只创建一个 Platform Realm 客户端，刷新队列只在同一实例内合并。
const client = createPlatformClient(sessionStorage)
provide(platformClientKey, client)
const tenantApi = createTenantApi(client)
// 登录后才展示租户管理；会话失效时随之隐藏。
const signedIn = ref(client.session() !== null)
onUnmounted(client.onSessionChange((value) => (signedIn.value = value !== null)))
</script>

<template>
  <el-container>
    <el-main>
      <el-card>
        <h1>{{ t('app.welcome') }}</h1>
        <LoginPanel />
      </el-card>
      <el-card v-if="signedIn" class="tenants">
        <TenantPanel :api="tenantApi" />
      </el-card>
    </el-main>
  </el-container>
</template>

<style scoped>
.tenants {
  margin-top: 16px;
}
</style>
